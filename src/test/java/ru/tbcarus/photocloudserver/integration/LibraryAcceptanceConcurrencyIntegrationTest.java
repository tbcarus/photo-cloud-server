package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Запросы стоят в очереди на строке ROOT, которую держит сам тест: upload к этому моменту уже прошёл pre-check
// и опубликовал байты. Порядок очереди FIFO, поэтому оба порядка завершения проверяются явно.
class LibraryAcceptanceConcurrencyIntegrationTest extends LibraryAcceptanceSupport {

    private static final byte[] JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    @RepeatedTest(10)
    void twoUploadsOfSameNameAndContentGiveOneFile() throws Exception {
        Long rootId = getRootId(token);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    uploadCall("a.txt", "same".getBytes(), rootId),
                    uploadCall("A.TXT", "same".getBytes(), rootId)));

            assertThat(statuses(responses)).containsOnly(200);
            assertThat(id(responses.get(0))).isEqualTo(id(responses.get(1)));
        }
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void twoUploadsOfSameNameAndOtherContentGiveOneFileAndConflict() throws Exception {
        Long rootId = getRootId(token);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    uploadCall("a.txt", "first".getBytes(), rootId),
                    uploadCall("a.txt", "second".getBytes(), rootId)));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertThat(fileItemRepository.findAll()).singleElement()
                .satisfies(file -> assertThat(file.getChecksum()).isEqualTo(sha256("first".getBytes())));
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    // Подтверждает смысл очереди: оба запроса прошли проверку до блокировки, но байты публикуются только под ней.
    @RepeatedTest(10)
    void queuedUploadsHavePassedPrecheckAndPublishedBytes() throws Exception {
        Long rootId = getRootId(token);
        try (RowGate gate = holdRoot(rootId)) {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            futures.add(executor.submit(uploadCall("a.txt", "first".getBytes(), rootId)));
            awaitWaiting(1);
            futures.add(executor.submit(uploadCall("a.txt", "second".getBytes(), rootId)));
            awaitWaiting(2);

            assertThat(tempFileCount()).isEqualTo(2);
            assertThat(physicalFiles()).isEmpty();
            gate.commit();

            assertThat(statuses(results(futures))).containsExactly(200, 409);
        }
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void fileFirstThenFolderWithSameName() throws Exception {
        Long rootId = getRootId(token);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    uploadCall("shared", "x".getBytes(), rootId),
                    jsonCall(post("/api/v1/folders"), Map.of("parentId", rootId, "name", "SHARED"))));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void folderFirstThenFileWithSameName() throws Exception {
        Long rootId = getRootId(token);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/folders"), Map.of("parentId", rootId, "name", "SHARED")),
                    uploadCall("shared", "x".getBytes(), rootId)));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(physicalFiles()).isEmpty();
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void twoFoldersWithSameName() throws Exception {
        Long rootId = getRootId(token);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/folders"), Map.of("parentId", rootId, "name", "Dir")),
                    jsonCall(post("/api/v1/folders"), Map.of("parentId", rootId, "name", "DIR"))));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void renameFirstThenUploadIntoSameName() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto file = upload(token, "old.txt", "text/plain", "old".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "New.txt")),
                    uploadCall("new.txt", "other".getBytes(), rootId)));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo("New.txt");
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void uploadFirstThenRenameIntoSameName() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto file = upload(token, "old.txt", "text/plain", "old".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    uploadCall("new.txt", "other".getBytes(), rootId),
                    jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "New.txt"))));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertThat(findFileItem(file.getId()).getOriginalName()).isEqualTo("old.txt");
        assertThat(physicalFiles()).hasSize(2);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void moveFirstThenUploadIntoTargetFolder() throws Exception {
        Long rootId = getRootId(token);
        Long source = createFolder(token, rootId, "Source").get("id").asLong();
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "busy.txt", "text/plain", "moved".getBytes(), source);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target)),
                    uploadCall("BUSY.txt", "other".getBytes(), target)));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(target);
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void uploadFirstThenMoveIntoTargetFolder() throws Exception {
        Long rootId = getRootId(token);
        Long source = createFolder(token, rootId, "Source").get("id").asLong();
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "busy.txt", "text/plain", "moved".getBytes(), source);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    uploadCall("BUSY.txt", "other".getBytes(), target),
                    jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target))));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(source);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void crossMovesOfTwoFoldersCannotCreateCycle() throws Exception {
        Long rootId = getRootId(token);
        Long first = createFolder(token, rootId, "First").get("id").asLong();
        Long second = createFolder(token, rootId, "Second").get("id").asLong();
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/folders/{id}/move", first), Map.of("targetParentId", second)),
                    jsonCall(post("/api/v1/folders/{id}/move", second), Map.of("targetParentId", first))));

            assertThat(statuses(responses)).containsExactly(200, 400);
        }
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void crossMovesThroughDeepChainCannotCreateCycle() throws Exception {
        Long rootId = getRootId(token);
        Long a = createFolder(token, rootId, "A").get("id").asLong();
        Long aChild = createFolder(token, a, "A1").get("id").asLong();
        Long b = createFolder(token, rootId, "B").get("id").asLong();
        Long bChild = createFolder(token, b, "B1").get("id").asLong();
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/folders/{id}/move", a), Map.of("targetParentId", bChild)),
                    jsonCall(post("/api/v1/folders/{id}/move", b), Map.of("targetParentId", aChild))));

            assertThat(statuses(responses)).containsExactly(200, 400);
        }
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void twoCameraUploadsWithExplicitFolderPassPrecheckAndKeepOneFile() throws Exception {
        Long rootId = getRootId(token);
        Long cameraId = upload(token, "seed.jpg", "image/jpeg", JPEG).getFolderId();
        byte[] photo = otherJpeg((byte) 1);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    uploadCall("IMG_1.jpg", photo, cameraId),
                    uploadCall("IMG_2.jpg", photo, cameraId)));

            assertThat(statuses(responses)).containsOnly(200);
            assertThat(id(responses.get(0))).isEqualTo(id(responses.get(1)));
        }
        assertThat(fileItemRepository.findAll()).hasSize(2);
        assertThat(physicalFiles()).hasSize(2);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void twoCameraUploadsWithoutFolderKeepOneFile() throws Exception {
        Long rootId = getRootId(token);
        upload(token, "seed.jpg", "image/jpeg", JPEG);
        byte[] photo = otherJpeg((byte) 1);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    uploadCall("IMG.jpg", photo, null),
                    uploadCall("IMG.jpg", photo, null)));

            assertThat(statuses(responses)).containsOnly(200);
            assertThat(id(responses.get(0))).isEqualTo(id(responses.get(1)));
        }
        assertThat(physicalFiles()).hasSize(2);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void cameraNotCreatedYetIsCreatedOnceByQueuedRequests() throws Exception {
        Long rootId = getRootId(token);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    uploadCall("1.jpg", otherJpeg((byte) 1), null),
                    uploadCall("2.jpg", otherJpeg((byte) 2), null),
                    uploadCall("3.jpg", otherJpeg((byte) 3), null),
                    uploadCall("same-as-3.jpg", otherJpeg((byte) 3), null)));

            assertThat(statuses(responses)).containsOnly(200);
        }
        assertThat(count("SELECT count(*) FROM folder WHERE folder_type = 'CAMERA'")).isEqualTo(1);
        assertThat(fileItemRepository.findAll()).hasSize(3);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void rootNotCreatedYetIsCreatedOnceByMixedRequests() throws Exception {
        try (RowGate gate = holdUncommittedRoot()) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(get("/api/v1/folders/root"), null),
                    uploadCall("1.jpg", otherJpeg((byte) 1), null),
                    jsonCall(post("/api/v1/folders"), Map.of("name", "Dir")),
                    uploadCall("2.jpg", otherJpeg((byte) 2), null)));

            assertThat(statuses(responses)).containsOnly(200);
        }
        assertThat(count("SELECT count(*) FROM folder WHERE folder_type = 'ROOT'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM folder WHERE folder_type = 'CAMERA'")).isEqualTo(1);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void rootInsertRolledBackByCompetitorIsCreatedByWaitingRequest() throws Exception {
        try (RowGate gate = holdUncommittedRoot()) {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            futures.add(executor.submit(uploadCall("1.jpg", otherJpeg((byte) 1), null)));
            awaitWaiting(1);
            futures.add(executor.submit(jsonCall(get("/api/v1/folders/root"), null)));
            awaitWaiting(2);
            gate.rollback();

            assertThat(statuses(results(futures))).containsOnly(200);
        }
        assertThat(count("SELECT count(*) FROM folder WHERE folder_type = 'ROOT'")).isEqualTo(1);
        assertLibraryConsistent();
    }

    @Test
    void uncontrolledBurstOfFirstRootRequestsCreatesOneRoot() throws Exception {
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(jsonCall(get("/api/v1/folders/root"), null));
        }
        List<Future<MockHttpServletResponse>> futures = executor.invokeAll(calls, 30, TimeUnit.SECONDS);

        assertThat(statuses(results(futures))).containsOnly(200);
        assertThat(count("SELECT count(*) FROM folder WHERE folder_type = 'ROOT'")).isEqualTo(1);
    }

    @RepeatedTest(10)
    void copyAndUploadIntoSameName() throws Exception {
        Long rootId = getRootId(token);
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto source = upload(token, "source.txt", "text/plain", "source".getBytes(), rootId);
        // Copy открывает источник под общей блокировкой: в очередь должна встать её публикация.
        try (RowGate gate = holdRootShared(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/files/{id}/copy", source.getId()), copyBody(target, "busy.txt")),
                    uploadCall("BUSY.TXT", "other".getBytes(), target)));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertThat(physicalFiles()).hasSize(2);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void twoCopiesIntoSameNameLeaveOnePhysicalCopy() throws Exception {
        Long rootId = getRootId(token);
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto source = upload(token, "source.txt", "text/plain", "source".getBytes(), rootId);
        // Copy открывает источник под общей блокировкой: в очередь должны встать их публикации.
        try (RowGate gate = holdRootShared(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/files/{id}/copy", source.getId()), copyBody(target, "copy.txt")),
                    jsonCall(post("/api/v1/files/{id}/copy", source.getId()), copyBody(target, "COPY.txt"))));

            assertThat(statuses(responses)).containsExactly(200, 409);
        }
        assertThat(physicalFiles()).hasSize(2);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void folderDeleteFirstMakesQueuedUploadAndMoveFail() throws Exception {
        Long rootId = getRootId(token);
        Long doomed = createFolder(token, rootId, "Doomed").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(delete("/api/v1/folders/{id}", doomed), null),
                    uploadCall("b.txt", "b".getBytes(), doomed),
                    jsonCall(post("/api/v1/files/{id}/copy", file.getId()), copyBody(doomed, "c.txt")),
                    jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", doomed))));

            assertThat(statuses(responses)).containsExactly(204, 404, 404, 404);
        }
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    // Каждая операция обязана ждать блокировку; иначе запрос завершился бы при занятой строке ROOT.
    @DisplayName("Операция структуры ждёт блокировку ROOT")
    @ParameterizedTest
    @ValueSource(strings = {"createFolder", "renameFolder", "moveFolder", "deleteFolder", "uploadLibrary", "uploadCameraExplicit",
            "uploadCameraDefault", "copy", "renameFile", "renameCameraFile", "moveFile", "deleteFile", "camera"})
    void everyStructuralOperationWaitsForRootLock(String operation) throws Exception {
        Map<String, Callable<MockHttpServletResponse>> operations = operations();
        Long rootId = getRootId(token);

        try (RowGate gate = holdRoot(rootId)) {
            Future<MockHttpServletResponse> future = executor.submit(operations.get(operation));
            awaitWaiting(1);
            assertThat(future.isDone()).isFalse();
            System.out.println("LOCK-SQL " + operation + " : " + jdbcTemplate.queryForObject(
                    "SELECT query FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", String.class));
            gate.commit();

            assertThat(future.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(operation.startsWith("delete") ? 204 : 200);
        }
        assertLibraryConsistent();
    }

    // Отрицательный контроль: чтение очередь не занимает, поэтому ожидание выше не артефакт теста.
    @Test
    void readsDoNotWaitForRootLock() throws Exception {
        Long rootId = getRootId(token);
        try (RowGate gate = holdRoot(rootId)) {
            assertThat(executor.submit(jsonCall(get("/api/v1/folders/{id}/children", rootId), null))
                    .get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            assertThat(executor.submit(jsonCall(get("/api/v1/folders/root"), null))
                    .get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            gate.commit();
        }
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void renameFirstThenDelete() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "b.txt")),
                    jsonCall(delete("/api/v1/files/{id}", file.getId()), null)));

            assertThat(statuses(responses)).containsExactly(200, 204);
        }
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(physicalFiles()).isEmpty();
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void deleteFirstThenRename() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(delete("/api/v1/files/{id}", file.getId()), null),
                    jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "b.txt"))));

            assertThat(statuses(responses)).containsExactly(204, 404);
        }
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(physicalFiles()).isEmpty();
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void moveFirstThenDelete() throws Exception {
        Long rootId = getRootId(token);
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target)),
                    jsonCall(delete("/api/v1/files/{id}", file.getId()), null)));

            assertThat(statuses(responses)).containsExactly(200, 204);
        }
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(physicalFiles()).isEmpty();
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void deleteFirstThenMove() throws Exception {
        Long rootId = getRootId(token);
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(delete("/api/v1/files/{id}", file.getId()), null),
                    jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target))));

            assertThat(statuses(responses)).containsExactly(204, 404);
        }
        assertThat(physicalFiles()).isEmpty();
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void twoDeletesOfOneFile() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto file = upload(token, "a.txt", "text/plain", "a".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(delete("/api/v1/files/{id}", file.getId()), null),
                    jsonCall(delete("/api/v1/files/{id}", file.getId()), null)));

            assertThat(statuses(responses)).containsExactly(204, 404);
        }
        assertThat(physicalFiles()).isEmpty();
        assertLibraryConsistent();
    }

    // Upload прошёл предпроверку на свободном имени; rename занимает имя раньше него, delete решает исход.
    @RepeatedTest(10)
    void deleteFreesNameBeforeQueuedUploadInSameFolder() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto file = upload(token, "old.txt", "text/plain", "old".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "busy.txt")),
                    jsonCall(delete("/api/v1/files/{id}", file.getId()), null),
                    uploadCall("BUSY.txt", "new".getBytes(), rootId)));

            assertThat(statuses(responses)).containsExactly(200, 204, 200);
        }
        assertThat(fileItemRepository.findAll()).singleElement()
                .satisfies(item -> assertThat(item.getChecksum()).isEqualTo(sha256("new".getBytes())));
        assertThat(physicalFiles()).hasSize(1);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void uploadBeforeDeleteConflictsWithExistingName() throws Exception {
        Long rootId = getRootId(token);
        FileItemDto file = upload(token, "old.txt", "text/plain", "old".getBytes(), rootId);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "busy.txt")),
                    uploadCall("BUSY.txt", "new".getBytes(), rootId),
                    jsonCall(delete("/api/v1/files/{id}", file.getId()), null)));

            assertThat(statuses(responses)).containsExactly(200, 409, 204);
        }
        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(physicalFiles()).isEmpty();
        assertLibraryConsistent();
    }

    // Запрос уже принял файл и ждёт блокировку: путь вычисляется после переименования родителя.
    @RepeatedTest(10)
    void folderRenameBeforeQueuedUploadIntoChild() throws Exception {
        Long rootId = getRootId(token);
        Long parent = createFolder(token, rootId, "Parent").get("id").asLong();
        Long child = createFolder(token, parent, "Child").get("id").asLong();
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(patch("/api/v1/folders/{id}", parent), Map.of("name", "Renamed")),
                    uploadCall("a.txt", "x".getBytes(), child)));

            assertThat(statuses(responses)).containsExactly(200, 200);
        }
        assertThat(storageRoot().resolve(userId + "/files/Renamed/Child/a.txt")).exists();
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void folderMoveAfterQueuedUploadIntoChild() throws Exception {
        Long rootId = getRootId(token);
        Long parent = createFolder(token, rootId, "Parent").get("id").asLong();
        Long child = createFolder(token, parent, "Child").get("id").asLong();
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    uploadCall("a.txt", "x".getBytes(), child),
                    jsonCall(post("/api/v1/folders/{id}/move", parent), Map.of("targetParentId", target))));

            assertThat(statuses(responses)).containsExactly(200, 200);
        }
        assertThat(storageRoot().resolve(userId + "/files/Target/Parent/Child/a.txt")).exists();
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void folderDeleteBeforeQueuedOperationsInside() throws Exception {
        Long rootId = getRootId(token);
        Long parent = createFolder(token, rootId, "Parent").get("id").asLong();
        Long child = createFolder(token, parent, "Child").get("id").asLong();
        FileItemDto file = upload(token, "a.txt", "text/plain", "x".getBytes(), child);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(delete("/api/v1/folders/{id}", parent), null),
                    uploadCall("b.txt", "y".getBytes(), child),
                    jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "c.txt")),
                    jsonCall(post("/api/v1/folders"), Map.of("parentId", child, "name", "New"))));

            assertThat(statuses(responses)).containsExactly(204, 404, 404, 404);
        }
        assertThat(tempFileCount()).isZero();
        pendingDeleteCleaner.clean();
        assertThat(pendingDeleteCount()).isZero();
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void parallelCameraRequestsReturnOneFolderWhenCameraIsMissing() throws Exception {
        Long rootId = getRootId(token);
        try (RowGate gate = holdRoot(rootId)) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/folders/camera"), null),
                    jsonCall(post("/api/v1/folders/camera"), null),
                    jsonCall(post("/api/v1/folders/camera"), null),
                    jsonCall(post("/api/v1/folders/camera"), null)));

            assertThat(statuses(responses)).containsOnly(200);
            assertThat(responses.stream().map(this::folderId).distinct()).hasSize(1);
        }
        assertThat(count("SELECT count(*) FROM folder WHERE folder_type = 'CAMERA'")).isEqualTo(1);
        assertLibraryConsistent();
    }

    @RepeatedTest(10)
    void parallelCameraRequestsCreateRootAndCameraOnEmptyAccount() throws Exception {
        try (RowGate gate = holdUncommittedRoot()) {
            List<MockHttpServletResponse> responses = runQueued(gate, List.of(
                    jsonCall(post("/api/v1/folders/camera"), null),
                    uploadCall("1.jpg", otherJpeg((byte) 1), null),
                    jsonCall(post("/api/v1/folders/camera"), null)));

            assertThat(statuses(responses)).containsOnly(200);
            assertThat(folderId(responses.get(0))).isEqualTo(folderId(responses.get(2)));
        }
        assertThat(count("SELECT count(*) FROM folder WHERE folder_type = 'ROOT'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM folder WHERE folder_type = 'CAMERA'")).isEqualTo(1);
        assertLibraryConsistent();
    }

    private Map<String, Callable<MockHttpServletResponse>> operations() throws Exception {
        Long rootId = getRootId(token);
        Long source = createFolder(token, rootId, "Source").get("id").asLong();
        Long target = createFolder(token, rootId, "Target").get("id").asLong();
        Long victim = createFolder(token, rootId, "Victim").get("id").asLong();
        FileItemDto file = upload(token, "file.txt", "text/plain", "file".getBytes(), rootId);
        FileItemDto photo = upload(token, "photo.jpg", "image/jpeg", JPEG);

        Map<String, Callable<MockHttpServletResponse>> operations = new LinkedHashMap<>();
        operations.put("createFolder", jsonCall(post("/api/v1/folders"), Map.of("parentId", rootId, "name", "New")));
        operations.put("renameFolder", jsonCall(patch("/api/v1/folders/{id}", source), Map.of("name", "Renamed")));
        operations.put("moveFolder", jsonCall(post("/api/v1/folders/{id}/move", source), Map.of("targetParentId", target)));
        operations.put("deleteFolder", jsonCall(delete("/api/v1/folders/{id}", victim), null));
        operations.put("uploadLibrary", uploadCall("new.txt", "new".getBytes(), rootId));
        operations.put("uploadCameraExplicit", uploadCall("new.jpg", otherJpeg((byte) 1), photo.getFolderId()));
        operations.put("uploadCameraDefault", uploadCall("new.jpg", otherJpeg((byte) 2), null));
        operations.put("copy", jsonCall(post("/api/v1/files/{id}/copy", file.getId()), copyBody(target, "copy.txt")));
        operations.put("renameFile", jsonCall(patch("/api/v1/files/{id}", file.getId()), Map.of("originalName", "renamed.txt")));
        operations.put("renameCameraFile", jsonCall(patch("/api/v1/files/{id}", photo.getId()), Map.of("originalName", "other.jpg")));
        operations.put("moveFile", jsonCall(post("/api/v1/files/{id}/move", file.getId()), Map.of("targetFolderId", target)));
        operations.put("deleteFile", jsonCall(delete("/api/v1/files/{id}", file.getId()), null));
        operations.put("camera", jsonCall(post("/api/v1/folders/camera"), null));
        return operations;
    }

    private Long folderId(MockHttpServletResponse response) {
        try {
            return id(response);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private byte[] otherJpeg(byte marker) {
        byte[] bytes = JPEG.clone();
        bytes[bytes.length - 3] = marker;
        return bytes;
    }
}
