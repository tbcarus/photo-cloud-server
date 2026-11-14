package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

// Отдельные процессы, настоящий HTTP и дисковое хранилище. Порт 8080 и рабочая БД недоступны этому стенду.
@Testcontainers
class UploadSessionProcessSmokeTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @TempDir Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private Process server;
    private java.util.concurrent.CompletableFuture<Void> logCopy;
    private int port;
    private int starts;
    private String token;

    @Test
    void realHttpSurvivesDisconnectLostResponseAndTwoForcedProcessRestarts() throws Exception {
        byte[] bytes = new byte[4096];
        new Random(91).nextBytes(bytes);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path storage = workspace.resolve("storage");
        try {
            start(false);
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                try (var insert = connection.prepareStatement("INSERT INTO users(email,password,enabled,banned) VALUES (?,?,true,false) RETURNING id")) {
                    insert.setString(1, "smoke@test.local");
                    insert.setString(2, new BCryptPasswordEncoder().encode("pass1"));
                    try (var result = insert.executeQuery()) {
                        result.next();
                        try (var role = connection.prepareStatement("INSERT INTO user_roles(user_id,role) VALUES (?, 'USER')")) {
                            role.setLong(1, result.getLong(1));
                            role.executeUpdate();
                        }
                    }
                }
            }
            token = json(send("POST", "/api/v1/auth/login", "application/json",
                    "{\"email\":\"smoke@test.local\",\"password\":\"pass1\"}".getBytes())).get("accessToken").asText();
            long folderId = json(send("GET", "/api/v1/folders/root", null, null)).get("id").asLong();
            String create = mapper.writeValueAsString(Map.of("clientRequestId", UUID.randomUUID().toString(), "folderId", folderId,
                    "originalFilename", "smoke.bin", "size", bytes.length, "checksum", checksum));
            JsonNode session = json(send("POST", "/api/v1/upload-sessions", "application/json", create.getBytes()));
            String id = session.get("id").asText();
            String path = "/api/v1/upload-sessions/" + id;
            assertThat(json(send("POST", "/api/v1/upload-sessions", "application/json", create.getBytes())).get("id").asText()).isEqualTo(id);

            // Полный первый блок отправлен, HTTP-ответ клиент теряет, затем спрашивает только статус.
            sendRawPatchWithoutReadingResponse(path, 0, Arrays.copyOfRange(bytes, 0, 1024), 1024);
            awaitOffset(path, 1024);
            assertThat(patch(path, 0, Arrays.copyOfRange(bytes, 0, 1024)).statusCode()).isEqualTo(409);
            // Обрыв внутри следующего блока не подтверждает полученную часть.
            sendRawPatchWithoutReadingResponse(path, 1024, Arrays.copyOfRange(bytes, 1024, 1124), 1024);
            kill();
            // Состояние аварии после append, но до COMMIT offset: лишний хвост на диске.
            Files.write(storage.resolve(".upload-sessions/" + id + ".data"), new byte[123], StandardOpenOption.APPEND);
            start(false);
            assertThat(json(send("GET", path, null, null)).get("confirmedOffset").asLong()).isEqualTo(1024);
            assertThat(patch(path, 1024, Arrays.copyOfRange(bytes, 1024, bytes.length)).statusCode()).isEqualTo(200);
            assertThat(send("POST", path + "/complete", null, null).statusCode()).isEqualTo(202);
            kill();
            // Прерванная физическая публикация: журнал уже зафиксирован, Library-транзакция отсутствует.
            Path destination = storage.resolve("1/files/smoke.bin");
            Files.createDirectories(destination.getParent());
            Files.createLink(destination, storage.resolve(".upload-sessions/" + id + ".data"));
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var update = connection.prepareStatement("UPDATE upload_sessions SET publication_path=? WHERE id=?::uuid")) {
                update.setString(1, destination.toString());
                update.setString(2, id);
                update.executeUpdate();
            }
            start(true);
            JsonNode completed = awaitCompleted(path);
            assertThat(completed.get("result").get("checksum").asText()).isEqualTo(checksum);
            long fileId = completed.get("result").get("id").asLong();
            assertThat(send("GET", "/api/v1/files/" + fileId + "/download", null, null).body()).isEqualTo(bytes);
            assertThat(json(send("POST", path + "/complete", null, null)).get("result").get("id").asLong()).isEqualTo(fileId);
            assertThat(send("DELETE", path, null, null).statusCode()).isEqualTo(409);
            assertThat(Files.readAllBytes(destination)).isEqualTo(bytes);
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var result = connection.createStatement().executeQuery("SELECT count(*) FROM file_item")) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(1);
            }
            Path multipart = workspace.resolve("multipart.bin");
            try (RandomAccessFile file = new RandomAccessFile(multipart.toFile(), "rw")) { file.setLength(50L * 1024 * 1024); }
            assertThat(uploadMultipart(multipart, folderId).statusCode()).isEqualTo(200);
            try (RandomAccessFile file = new RandomAccessFile(multipart.toFile(), "rw")) { file.setLength(50L * 1024 * 1024 + 1); }
            assertThat(uploadMultipart(multipart, folderId).statusCode()).isEqualTo(413);
        } finally { kill(); }
    }

    private void start(boolean finalize) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        assertThat(port).isNotEqualTo(8080);
        Path log = workspace.resolve("server-" + (++starts) + ".log");
        List<String> arguments = List.of("-cp", System.getProperty("photocloud.test.classpath"),
                "ru.tbcarus.photocloudserver.PhotoCloudServerApplication", "--spring.profiles.active=test", "--server.port=" + port,
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(), "--storage.root=" + workspace.resolve("storage"),
                "--storage.temp-dir=" + workspace.resolve("storage/tmp"), "--storage.max-file-size-bytes=" + (finalize ? 52428800 : 1024),
                "--upload.maintenance-interval=" + (finalize ? "PT0.2S" : "PT1H"), "--logging.level.root=WARN");
        Path argFile = workspace.resolve("java-" + starts + ".args");
        Files.write(argFile, arguments.stream().map(value -> "\"" + value.replace("\\", "/").replace("\"", "\\\"") + "\"").toList());
        Files.createFile(log);
        server = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + argFile)
                .directory(workspace.toFile()).redirectErrorStream(true).start();
        Process started = server;
        // Родитель явно закрывает журнал до удаления @TempDir: ожидания процесса на Windows недостаточно.
        logCopy = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (InputStream input = started.getInputStream(); OutputStream output = Files.newOutputStream(log)) {
                input.transferTo(output);
            } catch (IOException ex) { throw new UncheckedIOException(ex); }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline && server.isAlive()) {
            try { if (send("GET", "/api/v1/test", null, null).statusCode() == 200) {
                System.out.println("Isolated Server started: port=" + port + ", storage=" + workspace.resolve("storage"));
                return;
            } } catch (IOException ignored) { }
            Thread.sleep(200);
        }
        throw new AssertionError("Isolated Server did not start: " + Files.readString(log));
    }

    private void kill() throws Exception {
        if (server != null) {
            server.destroyForcibly();
            assertThat(server.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            server = null;
            logCopy.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private HttpResponse<byte[]> send(String method, String path, String contentType, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (contentType != null) request.header("Content-Type", contentType);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> patch(String path, long offset, byte[] body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/octet-stream")
                .header("Upload-Offset", Long.toString(offset)).method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> uploadMultipart(Path file, long folderId) throws Exception {
        String boundary = "photocloud-smoke-boundary";
        String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"folderId\"\r\n\r\n" + folderId
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"multipart.bin\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\n";
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix),
                HttpRequest.BodyPublishers.ofFile(file), HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/upload"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private void sendRawPatchWithoutReadingResponse(String path, long offset, byte[] bytes, int length) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream output = socket.getOutputStream();
            output.write(("PATCH " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + token
                    + "\r\nContent-Type: application/octet-stream\r\nUpload-Offset: " + offset
                    + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
            socket.shutdownOutput();
        }
    }

    private JsonNode json(HttpResponse<byte[]> response) throws IOException {
        assertThat(response.statusCode()).isBetween(200, 202);
        return mapper.readTree(response.body());
    }

    private void awaitOffset(String path, long offset) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (json(send("GET", path, null, null)).get("confirmedOffset").asLong() == offset) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Offset was not confirmed");
    }

    private JsonNode awaitCompleted(String path) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            JsonNode session = json(send("GET", path, null, null));
            if (session.get("state").asText().equals("COMPLETED")) return session;
            assertThat(session.get("state").asText()).isEqualTo("FINALIZING");
            Thread.sleep(100);
        }
        throw new AssertionError("Finalization did not complete");
    }
}
