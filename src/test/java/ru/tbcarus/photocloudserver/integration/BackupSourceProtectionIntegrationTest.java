package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Защита дерева активного автоматического источника, операции резервирования и отключение источника.
class BackupSourceProtectionIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final int THREADS = 6;
    private static final byte[] JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User user;
    private String token;
    private Long rootId;
    private UUID uuid;
    private Long sourceId;
    private Long nestedId;
    private Long deepId;
    private Long workId;
    private FileItemDto rootFile;
    private FileItemDto deepFile;
    private FileItemDto workFile;

    // Источник Documents/Проекты/Чертежи с файлами на двух уровнях и обычная папка Работа.
    @BeforeEach
    void createTree() throws Exception {
        user = createUser("managed@test.local", PASSWORD);
        token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        rootId = getRootId(token);
        uuid = UUID.randomUUID();
        sourceId = register(token, "Documents", uuid).get("id").asLong();
        nestedId = createFolderAs(token, sourceId, "Проекты", uuid).andExpect(status().isOk()).idOf();
        deepId = createFolderAs(token, nestedId, "Чертежи", uuid).andExpect(status().isOk()).idOf();
        rootFile = uploadAs(token, sourceId, "readme.txt", "root", uuid);
        deepFile = uploadAs(token, deepId, "plan.txt", "plan", uuid);
        workId = createFolder(token, rootId, "Работа").get("id").asLong();
        workFile = upload(token, "note.txt", "text/plain", "note".getBytes(), workId);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void manualChangesOfActiveTreeAreForbiddenOnEveryLevel() throws Exception {
        for (Long folderId : List.of(sourceId, nestedId, deepId)) {
            expectManaged(uploadRequest(token, folderId, "manual.txt", "manual", null));
            expectManaged(createFolderRequest(token, folderId, "Manual", null));
        }
        for (FileItemDto file : List.of(rootFile, deepFile)) {
            expectManaged(renameFileRequest(token, file.getId(), "renamed.txt", null));
            expectManaged(perform(authorized(delete("/api/v1/files/{id}", file.getId()))));
        }
        for (Long folderId : List.of(sourceId, nestedId, deepId)) {
            expectManaged(renameFolderRequest(token, folderId, "Renamed", null));
            expectManaged(perform(authorized(delete("/api/v1/folders/{id}", folderId))));
        }

        assertTreeUnchanged();
    }

    @Test
    void moveAndCopyCannotCrossOrStayInsideActiveTree() throws Exception {
        // Из дерева, внутри него и в него — для файлов и папок, включая перенос родителя и корня.
        expectManaged(moveFile(deepFile.getId(), workId));
        expectManaged(moveFile(deepFile.getId(), nestedId));
        expectManaged(moveFile(workFile.getId(), deepId));
        expectManaged(copyFile(deepFile.getId(), workId));
        expectManaged(copyFile(deepFile.getId(), deepId));
        expectManaged(copyFile(workFile.getId(), nestedId));
        expectManaged(moveFolder(nestedId, workId));
        expectManaged(moveFolder(deepId, sourceId));
        expectManaged(moveFolder(workId, deepId));
        expectManaged(moveFolder(sourceId, rootId));

        assertTreeUnchanged();
        assertThat(findFileItem(workFile.getId()).getFolder().getId()).isEqualTo(workId);
        assertThat(folderRepository.findById(workId).orElseThrow().getParent().getId()).isEqualTo(rootId);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void browseAndDownloadOfActiveTreeWork() throws Exception {
        perform(authorized(get("/api/v1/folders/{id}/children", nestedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Чертежи"));
        perform(authorized(get("/api/v1/files").param("folderId", deepId.toString()).param("sort", "name")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].originalFilename").value("plan.txt"));
        perform(authorized(get("/api/v1/files/{id}", deepFile.getId()))).andExpect(status().isOk());
        byte[] body = perform(authorized(get("/api/v1/files/{id}/download", deepFile.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(body)).isEqualTo("plan");
    }

    @Test
    void backupOperationsWithOwnUuidChangeTree() throws Exception {
        Long createdId = createFolderAs(token, deepId, "2026", uuid).andExpect(status().isOk()).idOf();
        FileItemDto uploaded = uploadAs(token, createdId, "new.txt", "new", uuid);
        renameFileRequest(token, deepFile.getId(), "plan_20261102-120000.txt", uuid).andExpect(status().isOk());
        renameFolderRequest(token, nestedId, "Проекты 2026", uuid).andExpect(status().isOk());

        Path deep = libraryRoot().resolve("AutoUploads/Documents/Проекты 2026/Чертежи");
        assertThat(physicalPath(findFileItem(deepFile.getId()))).isEqualTo(deep.resolve("plan_20261102-120000.txt"));
        assertThat(Files.readString(deep.resolve("plan_20261102-120000.txt"))).isEqualTo("plan");
        assertThat(physicalPath(findFileItem(uploaded.getId()))).isEqualTo(deep.resolve("2026/new.txt"));
        assertThat(folderRepository.findById(createdId).orElseThrow().getBackupSource()).isNull();
        // Повтор той же загрузки резервирования возвращает прежний файл.
        assertThat(uploadAs(token, createdId, "new.txt", "new", uuid).getId()).isEqualTo(uploaded.getId());
    }

    @Test
    void rootOfActiveSourceCannotBeRenamedEvenWithUuid() throws Exception {
        expectManaged(renameFolderRequest(token, sourceId, "Other", uuid));

        assertThat(folderRepository.findById(sourceId).orElseThrow().getName()).isEqualTo("Documents");
    }

    @Test
    void wrongOrMissingUuidDoesNotAllowBackupOperations() throws Exception {
        UUID otherUuid = UUID.randomUUID();
        Long otherSourceId = register(token, "Projects", otherUuid).get("id").asLong();

        expectManaged(uploadRequest(token, deepId, "x.txt", "x", UUID.randomUUID()));
        // UUID другого источника не открывает это дерево и наоборот.
        expectManaged(uploadRequest(token, deepId, "x.txt", "x", otherUuid));
        expectManaged(createFolderRequest(token, otherSourceId, "X", uuid));
        expectManaged(renameFileRequest(token, deepFile.getId(), "x.txt", otherUuid));
        expectManaged(renameFolderRequest(token, nestedId, "X", otherUuid));
        // UUID вне активного дерева тоже отклоняется: это не обычная операция библиотеки.
        expectManaged(uploadRequest(token, workId, "x.txt", "x", uuid));
        expectManaged(createFolderRequest(token, workId, "X", uuid));
        expectManaged(renameFileRequest(token, workFile.getId(), "x.txt", uuid));
        perform(multipart("/api/v1/files/upload")
                .file(filePart("x.txt", "text/plain", "x".getBytes()))
                .param("folderId", deepId.toString())
                .param("backupSource", "not-a-uuid")
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isBadRequest());
        perform(authorized(post("/api/v1/folders"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":%d,\"name\":\"X\",\"backupSource\":\"not-a-uuid\"}".formatted(deepId)))
                .andExpect(status().isBadRequest());

        assertTreeUnchanged();
        assertThat(fileItemRepository.findAll()).hasSize(3);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void backupUploadWithoutFolderIdIsRejectedBeforeCameraIsCreated() throws Exception {
        long foldersBefore = folderRepository.count();
        long storageFilesBefore = storageFileCount();

        for (String url : List.of("/api/v1/files", "/api/v1/files/upload")) {
            perform(multipart(url)
                    .file(filePart("IMG.jpg", "image/jpeg", JPEG_BYTES))
                    .param("backupSource", uuid.toString())
                    .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        assertThat(folderRepository.findAll()).noneMatch(folder -> folder.getFolderType() == FolderType.CAMERA);
        assertThat(folderRepository.count()).isEqualTo(foldersBefore);
        assertThat(fileItemRepository.findAll()).hasSize(3);
        assertThat(storageFileCount()).isEqualTo(storageFilesBefore);
        assertThat(Files.exists(storageRoot().resolve(user.getId() + "/camera"))).isFalse();
        assertThat(tempFileCount()).isZero();
        // Обычная загрузка без backupSource по-прежнему уходит в Camera, создавая её.
        FileItemDto photo = upload(token, "IMG.jpg", "image/jpeg", JPEG_BYTES);
        assertThat(findFileItem(photo.getId()).getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
    }

    @Test
    void otherUserHasNoAccessToTree() throws Exception {
        createUser("stranger@test.local", PASSWORD);
        String stranger = loginAndGetAccessToken("stranger@test.local", PASSWORD);

        perform(get("/api/v1/folders/{id}/children", sourceId).header(HttpHeaders.AUTHORIZATION, authHeader(stranger)))
                .andExpect(status().isNotFound());
        perform(get("/api/v1/files/{id}/download", deepFile.getId()).header(HttpHeaders.AUTHORIZATION, authHeader(stranger)))
                .andExpect(status().isNotFound());
        uploadRequest(stranger, deepId, "x.txt", "x", uuid).andExpect(status().isNotFound());
        createFolderRequest(stranger, deepId, "X", uuid).andExpect(status().isNotFound());
        renameFileRequest(stranger, deepFile.getId(), "x.txt", uuid).andExpect(status().isNotFound());
        perform(delete("/api/v1/folders/{id}/backup", sourceId).header(HttpHeaders.AUTHORIZATION, authHeader(stranger)))
                .andExpect(status().isNotFound());

        assertThat(folderRepository.findById(sourceId).orElseThrow().getBackupSource()).isEqualTo(uuid);
        assertTreeUnchanged();
    }

    @Test
    void disableClearsMarkerAndKeepsTree() throws Exception {
        Path deepPath = physicalPath(findFileItem(deepFile.getId()));

        disable(sourceId).andExpect(status().isNoContent());
        disable(sourceId).andExpect(status().isNoContent());

        assertThat(folderRepository.findById(sourceId).orElseThrow().getBackupSource()).isNull();
        assertThat(folderRepository.findById(sourceId).orElseThrow().getName()).isEqualTo("Documents");
        assertThat(folderRepository.findById(sourceId).orElseThrow().getParent().getId()).isEqualTo(containerId());
        assertThat(findFileItem(deepFile.getId()).getFolder().getId()).isEqualTo(deepId);
        assertThat(physicalPath(findFileItem(deepFile.getId()))).isEqualTo(deepPath);
        assertThat(Files.readString(deepPath)).isEqualTo("plan");
        assertThat(fileItemRepository.findAll()).hasSize(3);
        assertThat(perform(authorized(get("/api/v1/folders/{id}/children", containerId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("\"backupSource\":null");
    }

    @Test
    void ordinaryOperationsWorkAfterDisable() throws Exception {
        disable(sourceId).andExpect(status().isNoContent());

        FileItemDto manual = upload(token, "manual.txt", "text/plain", "manual".getBytes(), deepId);
        Long manualFolderId = createFolder(token, nestedId, "Manual").get("id").asLong();
        renameFileRequest(token, deepFile.getId(), "plan-old.txt", null).andExpect(status().isOk());
        moveFile(deepFile.getId(), workId).andExpect(status().isOk());
        copyFile(workFile.getId(), deepId).andExpect(status().isOk());
        moveFile(workFile.getId(), manualFolderId).andExpect(status().isOk());
        perform(authorized(delete("/api/v1/files/{id}", rootFile.getId()))).andExpect(status().isNoContent());
        renameFolderRequest(token, deepId, "Чертежи (архив)", null).andExpect(status().isOk());
        moveFolder(manualFolderId, workId).andExpect(status().isOk());
        renameFolderRequest(token, sourceId, "OldDocuments", null).andExpect(status().isOk());
        // Бывший корень можно вынести из AutoUploads в обычную библиотеку.
        moveFolder(sourceId, rootId).andExpect(status().isOk());

        assertThat(folderRepository.findById(sourceId).orElseThrow().getParent().getId()).isEqualTo(rootId);
        Path deep = libraryRoot().resolve("OldDocuments/Проекты/Чертежи (архив)");
        assertThat(Files.readString(physicalPath(findFileItem(manual.getId())))).isEqualTo("manual");
        assertThat(physicalPath(findFileItem(manual.getId()))).isEqualTo(deep.resolve("manual.txt"));
        assertThat(physicalPath(findFileItem(deepFile.getId()))).isEqualTo(libraryRoot().resolve("Работа/plan-old.txt"));
        assertThat(physicalPath(findFileItem(workFile.getId()))).isEqualTo(libraryRoot().resolve("Работа/Manual/note.txt"));
        assertThat(Files.exists(deep.resolve("note.txt"))).isTrue();
        assertThat(Files.exists(libraryRoot().resolve("AutoUploads/Documents"))).isFalse();

        perform(authorized(delete("/api/v1/folders/{id}", sourceId))).andExpect(status().isNoContent());
        assertThat(folderRepository.findById(sourceId)).isEmpty();
    }

    @Test
    void backupOperationsWithOldUuidStopAfterDisable() throws Exception {
        disable(sourceId).andExpect(status().isNoContent());

        expectManaged(uploadRequest(token, deepId, "late.txt", "late", uuid));
        expectManaged(createFolderRequest(token, deepId, "Late", uuid));
        expectManaged(renameFileRequest(token, deepFile.getId(), "late.txt", uuid));
        expectManaged(renameFolderRequest(token, nestedId, "Late", uuid));

        assertTreeUnchanged();
    }

    @Test
    void disableIsOnlyForSourceRoots() throws Exception {
        Long cameraId = upload(token, "IMG.jpg", "image/jpeg", JPEG_BYTES).getFolderId();

        for (Long id : List.of(rootId, cameraId, containerId(), workId, nestedId, deepId)) {
            disable(id).andExpect(status().isBadRequest());
        }
        disable(Long.MAX_VALUE).andExpect(status().isNotFound());

        assertThat(folderRepository.findById(sourceId).orElseThrow().getBackupSource()).isEqualTo(uuid);
    }

    @Test
    void containerStaysImmutableAfterDisable() throws Exception {
        disable(sourceId).andExpect(status().isNoContent());
        Long containerId = containerId();

        createFolderRequest(token, containerId, "Manual", null).andExpect(status().isBadRequest());
        uploadRequest(token, containerId, "manual.txt", "manual", null).andExpect(status().isBadRequest());
        moveFolder(workId, containerId).andExpect(status().isBadRequest());
        renameFolderRequest(token, containerId, "Other", null).andExpect(status().isBadRequest());
        perform(authorized(delete("/api/v1/folders/{id}", containerId))).andExpect(status().isBadRequest());
        // Вынесенный бывший корень обратно в AutoUploads не возвращается.
        moveFolder(sourceId, rootId).andExpect(status().isOk());
        moveFolder(sourceId, containerId).andExpect(status().isBadRequest());

        assertThat(folderRepository.findById(containerId).orElseThrow().getName()).isEqualTo("AutoUploads");
    }

    @Test
    void repeatedRegistrationDoesNotRestoreDisabledSource() throws Exception {
        disable(sourceId).andExpect(status().isNoContent());

        perform(authorized(post("/api/v1/folders/backup"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Documents", "sourceUuid", uuid.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(folderRepository.findById(sourceId).orElseThrow().getBackupSource()).isNull();
        assertThat(folderRepository.findAll()).filteredOn(folder -> uuid.equals(folder.getBackupSource())).isEmpty();
    }

    @RepeatedTest(5)
    void disableDuringBackupUploadsKeepsStorageConsistent() throws Exception {
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS - 1; i++) {
            calls.add(call(multipart("/api/v1/files")
                    .file(filePart("race-" + i + ".txt", "text/plain", ("race-" + i).getBytes()))
                    .param("folderId", deepId.toString())
                    .param("backupSource", uuid.toString())));
        }
        calls.add(call(delete("/api/v1/folders/{id}/backup", sourceId)));

        List<MockHttpServletResponse> responses = runTogether(calls);

        assertThat(responses.get(THREADS - 1).getStatus()).isEqualTo(204);
        List<Integer> uploads = responses.subList(0, THREADS - 1).stream().map(MockHttpServletResponse::getStatus).toList();
        assertThat(uploads).isSubsetOf(200, 409);
        long accepted = uploads.stream().filter(code -> code == 200).count();
        assertThat(fileItemRepository.findAll()).hasSize(3 + (int) accepted);
        assertThat(storageFileCount()).isEqualTo(3 + accepted);
        assertThat(tempFileCount()).isZero();
        for (FileItem file : fileItemRepository.findAll()) {
            assertThat(Files.exists(physicalPath(findFileItem(file.getId())))).isTrue();
        }
        assertThat(folderRepository.findById(sourceId).orElseThrow().getBackupSource()).isNull();
        expectManaged(uploadRequest(token, deepId, "after.txt", "after", uuid));
    }

    @Test
    void uploadWaitingForLockAfterEarlyCheckIsRejectedByDisable() throws Exception {
        // Транзакция держит блокировку ROOT, как отключение источника: upload проходит раннюю проверку и ждёт её.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> disabler = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.queryForList("SELECT id FROM folder WHERE id = ? FOR NO KEY UPDATE", rootId);
            locked.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
            jdbcTemplate.update("UPDATE folder SET backup_source = NULL WHERE id = ?", sourceId);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<MockHttpServletResponse> upload = executor.submit(call(multipart("/api/v1/files")
                .file(filePart("late.txt", "text/plain", "late".getBytes()))
                .param("folderId", deepId.toString())
                .param("backupSource", uuid.toString())));
        awaitLockWaiter();
        release.countDown();
        disabler.get(30, TimeUnit.SECONDS);

        MockHttpServletResponse response = upload.get(30, TimeUnit.SECONDS);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("FOLDER_MANAGED");
        assertThat(fileItemRepository.findAll()).hasSize(3);
        assertThat(storageFileCount()).isEqualTo(3);
        assertThat(tempFileCount()).isZero();
        assertThat(Files.exists(libraryRoot().resolve("AutoUploads/Documents/Проекты/Чертежи/late.txt"))).isFalse();
    }

    // Upload дошёл до блокировки ROOT и ждёт её в PostgreSQL.
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Upload did not wait for the user tree lock");
    }

    // Записи и байты дерева из createTree не изменились.
    private void assertTreeUnchanged() throws Exception {
        assertThat(folderRepository.findById(sourceId).orElseThrow().getName()).isEqualTo("Documents");
        assertThat(folderRepository.findById(nestedId).orElseThrow().getName()).isEqualTo("Проекты");
        assertThat(folderRepository.findById(nestedId).orElseThrow().getParent().getId()).isEqualTo(sourceId);
        assertThat(folderRepository.findById(deepId).orElseThrow().getParent().getId()).isEqualTo(nestedId);
        assertThat(findFileItem(deepFile.getId()).getOriginalName()).isEqualTo("plan.txt");
        assertThat(findFileItem(deepFile.getId()).getFolder().getId()).isEqualTo(deepId);
        assertThat(findFileItem(rootFile.getId()).getFolder().getId()).isEqualTo(sourceId);
        Path source = libraryRoot().resolve("AutoUploads/Documents");
        assertThat(Files.readString(source.resolve("readme.txt"))).isEqualTo("root");
        assertThat(Files.readString(source.resolve("Проекты/Чертежи/plan.txt"))).isEqualTo("plan");
        try (var children = Files.list(source.resolve("Проекты/Чертежи"))) {
            assertThat(children.map(path -> path.getFileName().toString())).containsExactly("plan.txt");
        }
        try (var children = Files.list(source.resolve("Проекты"))) {
            assertThat(children.map(path -> path.getFileName().toString())).containsExactly("Чертежи");
        }
        assertThat(pendingDeleteCount()).isZero();
    }

    private void expectManaged(ResultActions actions) throws Exception {
        actions.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FOLDER_MANAGED"));
    }

    private JsonNode register(String token, String name, UUID sourceUuid) throws Exception {
        return objectMapper.readTree(perform(post("/api/v1/folders/backup")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "sourceUuid", sourceUuid.toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private Long containerId() {
        return folderRepository.findById(sourceId).orElseThrow().getParent().getId();
    }

    private IdResult createFolderAs(String token, Long parentId, String name, UUID sourceUuid) throws Exception {
        return new IdResult(createFolderRequest(token, parentId, name, sourceUuid));
    }

    private ResultActions createFolderRequest(String token, Long parentId, String name, UUID sourceUuid) throws Exception {
        return perform(post("/api/v1/folders")
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("parentId", parentId, "name", name, "backupSource", sourceUuid)));
    }

    private FileItemDto uploadAs(String token, Long folderId, String name, String content, UUID sourceUuid) throws Exception {
        return objectMapper.readValue(uploadRequest(token, folderId, name, content, sourceUuid)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), FileItemDto.class);
    }

    private ResultActions uploadRequest(String token, Long folderId, String name, String content, UUID sourceUuid)
            throws Exception {
        var request = multipart("/api/v1/files/upload")
                .file(filePart(name, "text/plain", content.getBytes()))
                .param("folderId", folderId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(token));
        if (sourceUuid != null) {
            request.param("backupSource", sourceUuid.toString());
        }
        return perform(request);
    }

    private ResultActions renameFileRequest(String token, Long fileId, String name, UUID sourceUuid) throws Exception {
        return perform(patch("/api/v1/files/{id}", fileId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("originalName", name, "backupSource", sourceUuid)));
    }

    private ResultActions renameFolderRequest(String token, Long folderId, String name, UUID sourceUuid) throws Exception {
        return perform(patch("/api/v1/folders/{id}", folderId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("name", name, "backupSource", sourceUuid)));
    }

    private ResultActions moveFile(Long fileId, Long targetFolderId) throws Exception {
        return perform(authorized(post("/api/v1/files/{id}/move", fileId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("targetFolderId", targetFolderId)));
    }

    private ResultActions copyFile(Long fileId, Long targetFolderId) throws Exception {
        return perform(authorized(post("/api/v1/files/{id}/copy", fileId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("targetFolderId", targetFolderId)));
    }

    private ResultActions moveFolder(Long folderId, Long targetParentId) throws Exception {
        return perform(authorized(post("/api/v1/folders/{id}/move", folderId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("targetParentId", targetParentId)));
    }

    private ResultActions disable(Long folderId) throws Exception {
        return perform(authorized(delete("/api/v1/folders/{id}/backup", folderId)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorized(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, authHeader(token));
    }

    // null-значения не попадают в тело: так запрос без backupSource совпадает с прежним клиентом.
    private String json(Object... pairs) throws Exception {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                body.put((String) pairs[i], pairs[i + 1] instanceof UUID ? pairs[i + 1].toString() : pairs[i + 1]);
            }
        }
        return objectMapper.writeValueAsString(body);
    }

    private Callable<MockHttpServletResponse> call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        RequestBuilder request = builder.header(HttpHeaders.AUTHORIZATION, authHeader(token));
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

    private Path libraryRoot() {
        return storageRoot().resolve(user.getId() + "/files");
    }

    private final class IdResult {
        private final ResultActions actions;

        private IdResult(ResultActions actions) {
            this.actions = actions;
        }

        IdResult andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        Long idOf() throws Exception {
            return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asLong();
        }
    }
}
