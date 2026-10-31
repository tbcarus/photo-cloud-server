package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Параллельные запросы идут через реальный MVC-слой и реальные транзакции PostgreSQL.
class LibraryConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final int THREADS = 6;
    private static final byte[] JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    private String token;

    @BeforeEach
    void login() throws Exception {
        createUser("race@test.local", PASSWORD);
        token = loginAndGetAccessToken("race@test.local", PASSWORD);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @RepeatedTest(3)
    void parallelFilesWithOneNameLeaveOneFile() throws Exception {
        Long folderId = getRootId(token);
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(uploadCall(i % 2 == 0 ? "Same.txt" : "same.TXT", ("content-" + i).getBytes(), folderId));
        }

        assertThat(statuses(runTogether(calls))).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);

        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(storedObjectRepository.findAll()).hasSize(1);
        assertThat(storageFileCount()).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @RepeatedTest(3)
    void parallelRepeatsOfOneFileReturnOneFile() throws Exception {
        Long folderId = getRootId(token);
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(uploadCall("same.txt", "same content".getBytes(), folderId));
        }

        List<MockHttpServletResponse> responses = runTogether(calls);

        assertThat(statuses(responses)).containsOnly(200);
        assertThat(ids(responses)).hasSize(1);
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(storageFileCount()).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @RepeatedTest(3)
    void parallelFileAndFolderWithOneNameLeaveOneObject() throws Exception {
        Long parentId = getRootId(token);
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(i % 2 == 0
                    ? uploadCall("Shared", ("content-" + i).getBytes(), parentId)
                    : createFolderCall("SHARED", parentId));
        }

        assertThat(statuses(runTogether(calls))).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);

        assertThat(namedObjects(parentId, "shared")).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @RepeatedTest(3)
    void parallelFoldersWithOneNameLeaveOneFolder() throws Exception {
        Long parentId = getRootId(token);
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(createFolderCall("Dir", parentId));
        }

        assertThat(statuses(runTogether(calls))).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);

        assertThat(namedObjects(parentId, "dir")).isEqualTo(1);
    }

    @RepeatedTest(3)
    void parallelRenamesAndMovesIntoOneNameLeaveOneObject() throws Exception {
        Long rootId = getRootId(token);
        Long source = createFolder(token, rootId, "Source").get("id").asLong();
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        // Разные объекты занимают одно имя в target разными операциями.
        FileItemDto renamedInPlace = upload(token, "in-place.txt", "text/plain", "1".getBytes(), target);
        calls.add(call(patch("/api/v1/files/{id}", renamedInPlace.getId()), Map.of("originalName", "Busy")));
        Long renamedFolder = createFolder(token, target, "in-place-dir").get("id").asLong();
        calls.add(call(patch("/api/v1/folders/{id}", renamedFolder), Map.of("name", "BUSY")));
        FileItemDto movedFile = upload(token, "busy", "text/plain", "2".getBytes(), source);
        calls.add(call(post("/api/v1/files/{id}/move", movedFile.getId()), Map.of("targetFolderId", target)));
        Long movedFolder = createFolder(token, rootId, "bUsY").get("id").asLong();
        calls.add(call(post("/api/v1/folders/{id}/move", movedFolder), Map.of("targetParentId", target)));

        assertThat(statuses(runTogether(calls))).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);

        assertThat(namedObjects(target, "busy")).isEqualTo(1);
    }

    @RepeatedTest(3)
    void parallelCrossMovesDoNotCreateCycle() throws Exception {
        Long rootId = getRootId(token);
        Long first = createFolder(token, rootId, "First").get("id").asLong();
        Long second = createFolder(token, rootId, "Second").get("id").asLong();

        List<MockHttpServletResponse> responses = runTogether(List.of(
                call(post("/api/v1/folders/{id}/move", first), Map.of("targetParentId", second)),
                call(post("/api/v1/folders/{id}/move", second), Map.of("targetParentId", first))));

        assertThat(statuses(responses)).containsExactlyInAnyOrder(200, 400);
        Folder firstParent = folderRepository.findById(first).orElseThrow();
        Folder secondParent = folderRepository.findById(second).orElseThrow();
        assertThat(List.of(parentId(firstParent), parentId(secondParent))).contains(rootId);
    }

    @RepeatedTest(3)
    void parallelUploadAndFolderDeleteKeepFolderOrFailCleanly() throws Exception {
        Long folderId = createFolder(token, getRootId(token), "Temporary").get("id").asLong();

        List<MockHttpServletResponse> responses = runTogether(List.of(
                uploadCall("a.txt", "content".getBytes(), folderId),
                call(MockMvcRequestBuilders.delete("/api/v1/folders/{id}", folderId), null)));

        assertThat(statuses(responses)).isIn(List.of(200, 400), List.of(404, 204));
        boolean folderExists = folderRepository.findById(folderId).isPresent();
        assertThat(fileItemRepository.findAll()).hasSize(folderExists ? 1 : 0);
        assertThat(storageFileCount()).isEqualTo(folderExists ? 1 : 0);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void parallelCameraRepeatsBeforeRootAndCameraExistCreateOneFile() throws Exception {
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(uploadCall(i % 2 == 0 ? "IMG_1.jpg" : "IMG_2.jpg", JPEG_BYTES, null));
        }

        List<MockHttpServletResponse> responses = runTogether(calls);

        assertThat(statuses(responses)).containsOnly(200);
        assertThat(ids(responses)).hasSize(1);
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(folderRepository.findAll()).hasSize(2);
        assertThat(storageFileCount()).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @RepeatedTest(3)
    void parallelCameraRepeatsOfExistingFolderCreateOneFilePerChecksum() throws Exception {
        Long cameraId = upload(token, "seed.jpg", "image/jpeg", JPEG_BYTES).getFolderId();
        byte[] otherJpeg = JPEG_BYTES.clone();
        otherJpeg[otherJpeg.length - 3] = 0x01;
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(uploadCall("IMG.jpg", otherJpeg, cameraId));
        }

        List<MockHttpServletResponse> responses = runTogether(calls);

        assertThat(statuses(responses)).containsOnly(200);
        assertThat(ids(responses)).hasSize(1);
        assertThat(fileItemRepository.findAll()).hasSize(2);
        assertThat(storageFileCount()).isEqualTo(2);
        assertThat(tempFileCount()).isZero();
    }

    private Long parentId(Folder folder) {
        return jdbcTemplate.queryForObject("SELECT parent_id FROM folder WHERE id = ?", Long.class, folder.getId());
    }

    private long namedObjects(Long parentId, String lowerName) {
        Long files = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM file_item WHERE folder_id = ? AND lower(original_name) = ?", Long.class, parentId, lowerName);
        Long folders = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM folder WHERE parent_id = ? AND lower(name) = ?", Long.class, parentId, lowerName);
        return Objects.requireNonNull(files) + Objects.requireNonNull(folders);
    }

    private Callable<MockHttpServletResponse> uploadCall(String name, byte[] bytes, Long folderId) {
        var request = multipart("/api/v1/files")
                .file(filePart(name, "application/octet-stream", bytes))
                .header(HttpHeaders.AUTHORIZATION, authHeader(token));
        if (folderId != null) {
            request.param("folderId", folderId.toString());
        }
        return () -> mockMvc.perform(request).andReturn().getResponse();
    }

    private Callable<MockHttpServletResponse> createFolderCall(String name, Long parentId) throws Exception {
        return call(post("/api/v1/folders"), Map.of("parentId", parentId, "name", name));
    }

    private Callable<MockHttpServletResponse> call(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            Map<String, Object> body) throws Exception {
        builder.header(HttpHeaders.AUTHORIZATION, authHeader(token));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        RequestBuilder request = builder;
        return () -> mockMvc.perform(request).andReturn().getResponse();
    }

    private List<MockHttpServletResponse> runTogether(List<Callable<MockHttpServletResponse>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (Callable<MockHttpServletResponse> call : calls) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return call.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> future : futures) {
            responses.add(future.get(60, TimeUnit.SECONDS));
        }
        return responses;
    }

    private List<Integer> statuses(List<MockHttpServletResponse> responses) {
        return responses.stream().map(MockHttpServletResponse::getStatus).toList();
    }

    private List<Long> ids(List<MockHttpServletResponse> responses) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (MockHttpServletResponse response : responses) {
            ids.add(objectMapper.readTree(response.getContentAsString()).get("id").asLong());
        }
        return ids.stream().distinct().toList();
    }
}
