package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ContentDisposition;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

// Настоящий Tomcat: MockMvc не показывает, какие заголовки реально доходят до клиента.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LibraryAcceptanceDownloadNameIntegrationTest extends LibraryAcceptanceSupport {

    private static final byte[] CONTENT = "content".getBytes();

    @LocalServerPort
    int port;

    @Test
    void asciiNameReachesClient() throws Exception {
        assertThat(download("report.txt").headers().firstValue("Content-Disposition").orElse("")).contains("report.txt");
    }

    @Test
    void cyrillicNameReachesClient() throws Exception {
        assertThat(download("Отчёт.txt").headers().firstValue("Content-Disposition").orElse("")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Отчёт.pdf", "Документ 2026.docx", "Äpfel.txt", "plain-ascii.txt", "日本語 😀.txt", "a;b=c'%20,d.txt"})
    void nameIsRestoredExactlyAndBytesAreUnchanged(String name) throws Exception {
        HttpResponse<byte[]> response = download(name);

        assertThat(response.body()).isEqualTo(CONTENT);
        String header = response.headers().firstValue("Content-Disposition").orElseThrow();
        assertThat(ContentDisposition.parse(header).isAttachment()).isTrue();
        assertThat(ContentDisposition.parse(header).getFilename()).isEqualTo(name);
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(name)) {
            assertThat(decodedExtendedFilename(header)).isEqualTo(name);
        } else {
            assertThat(header).doesNotContain("filename*");
        }
        assertThat(header.chars().allMatch(symbol -> symbol >= 0x20 && symbol < 0x7F)).as("заголовок только из ASCII").isTrue();

        // Кодирование затрагивает только заголовок.
        assertThat(fileItemRepository.findAll()).singleElement()
                .satisfies(file -> assertThat(file.getOriginalName()).isEqualTo(name));
    }

    // Camera-имя очищается мягче правил библиотеки: кавычка и управляющие символы не должны разрывать заголовок.
    @Test
    void quotedNameCannotInjectHeader() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
        FileItemDto photo = upload(token, "IMG.jpg", "image/jpeg", jpeg);
        fileItemRepository.findById(photo.getId()).ifPresent(file -> {
            file.setOriginalName("x\"; filename=\"evil.exe");
            fileItemRepository.save(file);
        });

        String header = send(photo.getId()).headers().firstValue("Content-Disposition").orElseThrow();

        assertThat(ContentDisposition.parse(header).getFilename()).isEqualTo("x\"; filename=\"evil.exe");
        assertThat(header).doesNotContain("filename=\"evil.exe");
    }

    private String decodedExtendedFilename(String header) {
        String value = header.substring(header.indexOf("filename*=UTF-8''") + "filename*=UTF-8''".length());
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private HttpResponse<byte[]> download(String name) throws Exception {
        return send(upload(token, name, "text/plain", CONTENT, getRootId(token)).getId());
    }

    private HttpResponse<byte[]> send(Long fileId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/" + fileId + "/download"))
                .header("Authorization", authHeader(token))
                .build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }
}
