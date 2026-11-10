package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.RequestBuilder;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.ShareKind;
import ru.tbcarus.photocloudserver.model.Share;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.ShareRepository;
import ru.tbcarus.photocloudserver.service.storage.LibraryStorage;
import ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailFixtures;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Идентичность источника байтов: путь библиотеки вычисляется из имени, поэтому между проверкой файла A и открытием
// его байтов имя A может освободиться и достаться файлу B. Барьер останавливает читателя ровно в момент вычисления
// пути A и выполняет в другом потоке «A уходит со своего имени, B занимает его». Читатель продолжает, когда эта
// замена завершилась либо ждёт блокировки дерева, которую держит сам читатель.
class FileSourceIdentityIntegrationTest extends LibraryAcceptanceSupport {

    private static final byte[] A_BYTES = "AAAA original of file A".getBytes(StandardCharsets.UTF_8);
    private static final byte[] B_BYTES = "BBBB another file B".getBytes(StandardCharsets.UTF_8);

    @MockitoSpyBean
    LibraryStorage storage;

    @Autowired
    ShareRepository shareRepository;

    private Long rootId;
    private Future<?> race;

    @BeforeEach
    void loadRoot() throws Exception {
        rootId = getRootId(token);
    }

    // T1. Download владельца, Range и HEAD.

    @Test
    void downloadOfRenamedFileNeverReturnsBytesOfFileThatTookItsName() throws Exception {
        Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();
        Long b = upload(token, "b.txt", "text/plain", B_BYTES, rootId).getId();

        armRace(a, () -> renameAwayAndTakeName(a, b));
        MockHttpServletResponse response = call(get("/api/v1/files/{id}/download", a).header(HttpHeaders.AUTHORIZATION, authHeader(token)));

        finishRace();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(A_BYTES);
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(A_BYTES.length));
        assertRaceHappened(a, b);
    }

    @Test
    void rangeOfMovedFileReadsOnlyItsOwnBytes() throws Exception {
        Long other = createFolder(token, rootId, "Other").get("id").asLong();
        Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();
        Long b = upload(token, "b.txt", "text/plain", B_BYTES, rootId).getId();

        armRace(a, () -> {
            ok(jsonCall(post("/api/v1/files/{id}/move", a), Map.of("targetFolderId", other)));
            ok(jsonCall(patch("/api/v1/files/{id}", b), Map.of("originalName", "x.txt")));
        });
        MockHttpServletResponse response = call(get("/api/v1/files/{id}/download", a)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .header(HttpHeaders.RANGE, "bytes=5-"));

        finishRace();
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-%d/%d".formatted(A_BYTES.length - 1, A_BYTES.length));
        assertThat(response.getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(A_BYTES, 5, A_BYTES.length));
        assertThat(findFileItem(a).getFolder().getId()).isEqualTo(other);
        assertThat(findFileItem(b).getOriginalName()).isEqualTo("x.txt");
        assertLibraryConsistent();
    }

    @Test
    void fileDeletedBeforeOpeningGivesNotFoundInsteadOfNewFileWithItsName() throws Exception {
        Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();

        armRace(a, () -> {
            ok(jsonCall(delete("/api/v1/files/{id}", a), null));
            ok(uploadCall("x.txt", B_BYTES, rootId));
        });
        MockHttpServletResponse response = call(get("/api/v1/files/{id}/download", a).header(HttpHeaders.AUTHORIZATION, authHeader(token)));

        finishRace();
        // Читатель открыл A до удаления — его байты; иначе 404. Байты нового x.txt недопустимы в обоих случаях.
        assertThat(response.getStatus()).isIn(200, 404);
        assertThat(response.getContentAsByteArray()).isNotEqualTo(B_BYTES);
        if (response.getStatus() == 200) {
            assertThat(response.getContentAsByteArray()).isEqualTo(A_BYTES);
        }
        assertLibraryConsistent();
    }

    @Test
    void headAndRangeKeepContract() throws Exception {
        Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();

        MockHttpServletResponse headResponse = call(head("/api/v1/files/{id}/download", a).header(HttpHeaders.AUTHORIZATION, authHeader(token)));
        assertThat(headResponse.getStatus()).isEqualTo(200);
        assertThat(headResponse.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(A_BYTES.length));
        assertThat(headResponse.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"x.txt\"");
        // Тело HEAD отбрасывает servlet-контейнер; MockMvc его не отбрасывает, поэтому проверяются только заголовки.
        assertThat(headResponse.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");

        MockHttpServletResponse ranges = call(get("/api/v1/files/{id}/download", a)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .header(HttpHeaders.RANGE, "bytes=0-3,5-8"));
        assertThat(ranges.getStatus()).isEqualTo(206);
        String body = ranges.getContentAsString();
        assertThat(body).contains("Content-Range: bytes 0-3/" + A_BYTES.length).contains("AAAA")
                .contains("Content-Range: bytes 5-8/" + A_BYTES.length).contains("orig");

        MockHttpServletResponse unsatisfiable = call(get("/api/v1/files/{id}/download", a)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .header(HttpHeaders.RANGE, "bytes=500-"));
        assertThat(unsatisfiable.getStatus()).isEqualTo(416);
        assertThat(unsatisfiable.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */" + A_BYTES.length);
    }

    // T3. USER- и LINK-доступ, миниатюры.

    @Test
    void sharedRecipientReadsOnlyTheSharedFile() throws Exception {
        Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();
        Long b = upload(token, "b.txt", "text/plain", B_BYTES, rootId).getId();
        User recipient = createUser("recipient@test.local", PASSWORD);
        String recipientToken = loginAndGetAccessToken("recipient@test.local", PASSWORD);
        shareRepository.save(Share.builder()
                .fileItem(fileItemRepository.findById(a).orElseThrow())
                .owner(userRepository.findById(userId).orElseThrow())
                .kind(ShareKind.USER)
                .recipient(recipient)
                .build());

        armRace(a, () -> renameAwayAndTakeName(a, b));
        MockHttpServletResponse response = call(get("/api/v1/files/{id}/download", a).header(HttpHeaders.AUTHORIZATION, authHeader(recipientToken)));

        finishRace();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(A_BYTES);
        assertRaceHappened(a, b);
    }

    @Test
    void publicLinkDownloadReadsOnlyLinkedFile() throws Exception {
        Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();
        Long b = upload(token, "b.txt", "text/plain", B_BYTES, rootId).getId();
        String link = linkToken(a);

        armRace(a, () -> renameAwayAndTakeName(a, b));
        MockHttpServletResponse response = call(get("/api/v1/public/s/{token}/files/{id}/download", link, a));

        finishRace();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(A_BYTES);
        assertRaceHappened(a, b);
    }

    @Test
    void directPublicLinkReadsOnlyLinkedFile() throws Exception {
        Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();
        Long b = upload(token, "b.txt", "text/plain", B_BYTES, rootId).getId();
        String link = linkToken(a);

        armRace(a, () -> renameAwayAndTakeName(a, b));
        MockHttpServletResponse response = call(get("/s/{token}", link));

        finishRace();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(A_BYTES);
        assertRaceHappened(a, b);
    }

    @Test
    void thumbnailIsBuiltFromSameFileAsItsEtag() throws Exception {
        Long a = upload(token, "x.png", "image/png", solidPng(Color.RED), rootId).getId();
        Long b = upload(token, "b.png", "image/png", solidPng(Color.BLUE), rootId).getId();
        String etag = call(get("/api/v1/files/{id}/thumbnail", a).header(HttpHeaders.AUTHORIZATION, authHeader(token))).getHeader(HttpHeaders.ETAG);

        armRace(a, () -> renameAwayAndTakeName(a, b, "x-archived.png", "x.png"));
        MockHttpServletResponse response = call(get("/api/v1/files/{id}/thumbnail", a).header(HttpHeaders.AUTHORIZATION, authHeader(token)));

        finishRace();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ETAG)).isEqualTo(etag);
        assertThat(centerColor(response)).matches(rgb -> ThumbnailFixtures.near(rgb, Color.RED), "красный центр файла A");
        assertThat(findFileItem(b).getOriginalName()).isEqualTo("x.png");
    }

    @Test
    void publicThumbnailIsBuiltFromLinkedFile() throws Exception {
        Long a = upload(token, "x.png", "image/png", solidPng(Color.RED), rootId).getId();
        Long b = upload(token, "b.png", "image/png", solidPng(Color.BLUE), rootId).getId();
        String link = linkToken(a);

        armRace(a, () -> renameAwayAndTakeName(a, b, "x-archived.png", "x.png"));
        MockHttpServletResponse response = call(get("/api/v1/public/s/{token}/files/{id}/thumbnail", link, a));

        finishRace();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(centerColor(response)).matches(rgb -> ThumbnailFixtures.near(rgb, Color.RED), "красный центр файла A");
    }

    // T2. Copy.

    @Test
    void copyTakesBytesOfSourceEvenIfItsNameIsTakenMeanwhile() throws Exception {
        Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();
        Long b = upload(token, "b.txt", "text/plain", B_BYTES, rootId).getId();

        armRace(a, () -> renameAwayAndTakeName(a, b));
        MockHttpServletResponse response = call(post("/api/v1/files/{id}/copy", a)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(copyBody(rootId, "copy.txt"))));

        finishRace();
        assertThat(response.getStatus()).isEqualTo(200);
        FileItem copy = findFileItem(id(response));
        assertThat(Files.readAllBytes(physicalPath(copy))).isEqualTo(A_BYTES);
        assertThat(copy.getChecksum()).isEqualTo(sha256(A_BYTES));
        assertThat(copy.getStoredObject().getChecksum()).isEqualTo(sha256(A_BYTES));
        assertThat(copy.getStoredObject().getSize()).isEqualTo(A_BYTES.length);
        assertRaceHappened(a, b);
    }

    @Test
    void copyOfSourceWhoseBytesDoNotMatchItsRecordIsRejectedWithoutTraces() throws Exception {
        for (byte[] damaged : new byte[][]{"ZZZZ original of file A".getBytes(StandardCharsets.UTF_8), "short".getBytes(StandardCharsets.UTF_8)}) {
            cleanupDbAndStorage();
            userId = createUser("accept@test.local", PASSWORD).getId();
            token = loginAndGetAccessToken("accept@test.local", PASSWORD);
            rootId = getRootId(token);
            Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();
            // Байты изменены вне приложения: запись и диск расходятся.
            Files.write(physicalPath(findFileItem(a)), damaged);
            long items = fileItemRepository.count();
            long objects = storedObjectRepository.count();

            MockHttpServletResponse response = call(post("/api/v1/files/{id}/copy", a)
                    .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(copyBody(rootId, "copy.txt"))));

            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(fileItemRepository.count()).isEqualTo(items);
            assertThat(storedObjectRepository.count()).isEqualTo(objects);
            assertThat(Files.exists(physicalPath(findFileItem(a)).resolveSibling("copy.txt"))).isFalse();
            assertThat(tempFileCount()).isZero();
        }
    }

    @Test
    void copyOfSourceWithoutBytesStaysServerError() throws Exception {
        Long a = upload(token, "x.txt", "text/plain", A_BYTES, rootId).getId();
        deletePhysicalFile(a);
        long items = fileItemRepository.count();
        long objects = storedObjectRepository.count();

        // Запись есть, байтов нет — ошибка хранилища, а не «файл не найден».
        assertThat(call(copyRequest(a)).getStatus()).isEqualTo(500);
        assertThat(fileItemRepository.count()).isEqualTo(items);
        assertThat(storedObjectRepository.count()).isEqualTo(objects);
        assertThat(tempFileCount()).isZero();
    }

    private RequestBuilder copyRequest(Long fileId) throws Exception {
        return post("/api/v1/files/{id}/copy", fileId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(copyBody(rootId, "copy.txt")));
    }

    // T4. Ресурсы: на Windows открытый файл не даёт переименовать его папку, поэтому утечка канала видна как 409.

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void everyReadReleasesFileSoItsFolderCanBeRenamed() throws Exception {
        Long folder = createFolder(token, rootId, "F").get("id").asLong();
        Long text = upload(token, "x.txt", "text/plain", A_BYTES, folder).getId();
        Long image = upload(token, "x.png", "image/png", solidPng(Color.RED), folder).getId();
        Long broken = upload(token, "broken.png", "image/png", ThumbnailFixtures.pngHeader(8, 8), folder).getId();
        String link = linkToken(text);
        String auth = authHeader(token);

        Map<String, RequestBuilder> reads = new java.util.LinkedHashMap<>();
        reads.put("download", get("/api/v1/files/{id}/download", text).header(HttpHeaders.AUTHORIZATION, auth));
        reads.put("head", head("/api/v1/files/{id}/download", text).header(HttpHeaders.AUTHORIZATION, auth));
        reads.put("range", get("/api/v1/files/{id}/download", text).header(HttpHeaders.AUTHORIZATION, auth).header(HttpHeaders.RANGE, "bytes=1-2"));
        reads.put("range 416", get("/api/v1/files/{id}/download", text).header(HttpHeaders.AUTHORIZATION, auth).header(HttpHeaders.RANGE, "bytes=500-"));
        reads.put("link", get("/s/{token}", link));
        reads.put("link head", head("/s/{token}", link));
        reads.put("thumbnail", get("/api/v1/files/{id}/thumbnail", image).header(HttpHeaders.AUTHORIZATION, auth));
        reads.put("unreadable thumbnail", get("/api/v1/files/{id}/thumbnail", broken).header(HttpHeaders.AUTHORIZATION, auth));
        reads.put("copy", post("/api/v1/files/{id}/copy", text).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType("application/json").content(objectMapper.writeValueAsString(copyBody(rootId, "copied.txt"))));

        String name = "F";
        for (Map.Entry<String, RequestBuilder> read : reads.entrySet()) {
            call(read.getValue());
            String next = name.equals("F") ? "G" : "F";
            assertThat(call(patch("/api/v1/folders/{id}", folder).header(HttpHeaders.AUTHORIZATION, auth)
                    .contentType("application/json").content("{\"name\":\"" + next + "\"}")).getStatus())
                    .as("папка переименовывается после «%s»", read.getKey())
                    .isEqualTo(200);
            name = next;
        }
        assertLibraryConsistent();
    }

    // --- барьер гонки ---

    // Один раз: когда этот поток вычисляет путь файла fileId, в другом потоке выполняется change.
    private void armRace(Long fileId, Step change) {
        Thread reader = Thread.currentThread();
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            FileItem file = invocation.getArgument(0);
            if (Thread.currentThread() == reader && fileId.equals(file.getId()) && armed.compareAndSet(true, false)) {
                race = executor.submit(() -> {
                    change.run();
                    return null;
                });
                awaitRaceDoneOrQueued();
            }
            return invocation.callRealMethod();
        }).when(storage).physicalPath(any(FileItem.class));
    }

    private void awaitRaceDoneOrQueued() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (race.isDone()) {
                race.get();
                return;
            }
            if (waitingBackends() > 0) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Конкурирующее изменение не завершилось и не встало в очередь блокировки");
    }

    private void finishRace() throws Exception {
        assertThat(race).as("барьер сработал").isNotNull();
        race.get(30, TimeUnit.SECONDS);
    }

    private void renameAwayAndTakeName(Long a, Long b) throws Exception {
        renameAwayAndTakeName(a, b, "x-archived.txt", "x.txt");
    }

    private void renameAwayAndTakeName(Long a, Long b, String archived, String taken) throws Exception {
        ok(jsonCall(patch("/api/v1/files/{id}", a), Map.of("originalName", archived)));
        ok(jsonCall(patch("/api/v1/files/{id}", b), Map.of("originalName", taken)));
    }

    private void assertRaceHappened(Long a, Long b) throws Exception {
        assertThat(findFileItem(a).getOriginalName()).isEqualTo("x-archived.txt");
        assertThat(findFileItem(b).getOriginalName()).isEqualTo("x.txt");
        assertThat(Files.readAllBytes(physicalPath(findFileItem(b)))).isEqualTo(B_BYTES);
        assertLibraryConsistent();
    }

    private static void ok(Callable<MockHttpServletResponse> call) throws Exception {
        MockHttpServletResponse response = call.call();
        assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(200, 299);
    }

    private MockHttpServletResponse call(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String linkToken(Long fileId) throws Exception {
        MockHttpServletResponse response = call(post("/api/v1/shares/links")
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType("application/json")
                .content("{\"fileId\":" + fileId + "}"));
        assertThat(response.getStatus()).isEqualTo(200);
        String url = objectMapper.readTree(response.getContentAsString()).get("url").asText();
        return url.substring(url.indexOf("/s/") + 3);
    }

    private static byte[] solidPng(Color color) throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 16, 16);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static int centerColor(MockHttpServletResponse response) {
        BufferedImage thumbnail = ThumbnailFixtures.read(response.getContentAsByteArray());
        return thumbnail.getRGB(thumbnail.getWidth() / 2, thumbnail.getHeight() / 2);
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }
}
