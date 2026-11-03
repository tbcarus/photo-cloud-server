package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Регистрация автоматических источников: системный контейнер AutoUploads и корни источников в нём.
class AutoUploadsIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final String BACKUP_URL = "/api/v1/folders/backup";
    private static final int THREADS = 6;
    private static final byte[] JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void firstSourceCreatesContainerAndSourceRoot() throws Exception {
        User user = createUser("first@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        Long rootId = getRootId(token);
        UUID uuid = UUID.randomUUID();

        JsonNode source = register(token, "Documents", uuid);

        Folder container = autoUploads(user);
        assertThat(container.getName()).isEqualTo("AutoUploads");
        assertThat(container.getParent().getId()).isEqualTo(rootId);
        assertThat(container.getBackupSource()).isNull();
        assertThat(source.get("parentId").asLong()).isEqualTo(container.getId());
        assertThat(source.get("name").asText()).isEqualTo("Documents");
        assertThat(source.get("folderType").asText()).isEqualTo("USER");
        assertThat(source.get("backupSource").asText()).isEqualTo(uuid.toString());
        assertThat(folderRepository.findById(source.get("id").asLong()).orElseThrow().getBackupSource()).isEqualTo(uuid);
        assertThat(Files.isDirectory(sourcePath(user, "Documents"))).isTrue();
        assertThat(libraryDirectories(user)).containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents");
    }

    @Test
    void secondSourceUsesSameContainer() throws Exception {
        User user = createUser("second@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);

        JsonNode documents = register(token, "Documents", UUID.randomUUID());
        JsonNode projects = register(token, "Projects", UUID.randomUUID());

        assertThat(projects.get("parentId").asLong()).isEqualTo(documents.get("parentId").asLong());
        assertThat(foldersOfType(user, FolderType.AUTO_UPLOADS)).hasSize(1);
        assertThat(getChildren(token, documents.get("parentId").asLong()))
                .extracting(child -> child.get("name").asText())
                .containsExactly("Documents", "Projects");
        assertThat(libraryDirectories(user))
                .containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents", "AutoUploads/Projects");
    }

    @Test
    void repeatWithSameUuidAndNameReturnsSameFolder() throws Exception {
        User user = createUser("repeat@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        UUID uuid = UUID.randomUUID();

        Long first = register(token, "Documents", uuid).get("id").asLong();
        Long second = register(token, "Documents", uuid).get("id").asLong();

        assertThat(second).isEqualTo(first);
        assertThat(folderRepository.findAll()).hasSize(3);
        assertThat(libraryDirectories(user)).containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents");
    }

    @Test
    void occupiedNameIsConflictForAnotherSourceIgnoringCase() throws Exception {
        User user = createUser("occupied@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        register(token, "Documents", UUID.randomUUID());

        registerRequest(token, "Documents", UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        registerRequest(token, "dOCUMENTS", UUID.randomUUID()).andExpect(status().isConflict());

        assertThat(folderRepository.findAll()).hasSize(3);
        assertThat(libraryDirectories(user)).containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents");
    }

    @Test
    void sameUuidWithAnotherNameIsConflictAndSourceIsNotRenamed() throws Exception {
        User user = createUser("rename@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        UUID uuid = UUID.randomUUID();
        Long id = register(token, "Documents", uuid).get("id").asLong();

        registerRequest(token, "Projects", uuid).andExpect(status().isConflict());
        // Имя сравнивается точно: смена только регистра — тоже другое имя.
        registerRequest(token, "documents", uuid).andExpect(status().isConflict());

        assertThat(folderRepository.findById(id).orElseThrow().getName()).isEqualTo("Documents");
        assertThat(folderRepository.findAll()).hasSize(3);
        assertThat(libraryDirectories(user)).containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents");
    }

    @Test
    void usersAreIsolated() throws Exception {
        User first = createUser("isolated-1@test.local", PASSWORD);
        User second = createUser("isolated-2@test.local", PASSWORD);
        String firstToken = loginAndGetAccessToken(first.getEmail(), PASSWORD);
        String secondToken = loginAndGetAccessToken(second.getEmail(), PASSWORD);
        UUID uuid = UUID.randomUUID();

        JsonNode firstSource = register(firstToken, "Documents", uuid);
        JsonNode secondSource = register(secondToken, "Documents", uuid);

        assertThat(secondSource.get("id").asLong()).isNotEqualTo(firstSource.get("id").asLong());
        assertThat(secondSource.get("parentId").asLong()).isEqualTo(autoUploads(second).getId());
        assertThat(autoUploads(first).getId()).isNotEqualTo(autoUploads(second).getId());
        perform(get("/api/v1/folders/{id}/children", firstSource.get("id").asLong())
                .header(HttpHeaders.AUTHORIZATION, authHeader(secondToken)))
                .andExpect(status().isNotFound());
        perform(get("/api/v1/folders/{id}/children", firstSource.get("parentId").asLong())
                .header(HttpHeaders.AUTHORIZATION, authHeader(secondToken)))
                .andExpect(status().isNotFound());
        assertThat(Files.isDirectory(sourcePath(first, "Documents"))).isTrue();
        assertThat(Files.isDirectory(sourcePath(second, "Documents"))).isTrue();
    }

    @RepeatedTest(3)
    void parallelRepeatsOfOneSourceCreateOneFolder() throws Exception {
        User user = createUser("race-same@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        UUID uuid = UUID.randomUUID();
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(registerCall(token, "Documents", uuid));
        }

        List<MockHttpServletResponse> responses = runTogether(calls);

        assertThat(statuses(responses)).containsOnly(200);
        assertThat(ids(responses)).hasSize(1);
        assertThat(foldersOfType(user, FolderType.AUTO_UPLOADS)).hasSize(1);
        assertThat(folderRepository.findAll()).hasSize(3);
        assertThat(libraryDirectories(user)).containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents");
    }

    @RepeatedTest(3)
    void parallelSourcesWithOneNameLeaveOneSource() throws Exception {
        User user = createUser("race-name@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(registerCall(token, i % 2 == 0 ? "Documents" : "documents", UUID.randomUUID()));
        }

        assertThat(statuses(runTogether(calls))).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);

        assertThat(foldersOfType(user, FolderType.AUTO_UPLOADS)).hasSize(1);
        assertThat(folderRepository.findAll()).hasSize(3);
        assertThat(libraryDirectories(user)).hasSize(2);
    }

    @RepeatedTest(3)
    void parallelDifferentSourcesShareOneContainer() throws Exception {
        User user = createUser("race-container@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(registerCall(token, "Source-" + i, UUID.randomUUID()));
        }

        List<MockHttpServletResponse> responses = runTogether(calls);

        assertThat(statuses(responses)).containsOnly(200);
        assertThat(ids(responses)).hasSize(THREADS);
        assertThat(foldersOfType(user, FolderType.AUTO_UPLOADS)).hasSize(1);
        assertThat(libraryDirectories(user)).hasSize(THREADS + 1);
    }

    @Test
    void existingUserFolderNamedAutoUploadsIsKept() throws Exception {
        User user = createUser("user-folder@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        Long rootId = getRootId(token);
        Long userFolderId = createFolder(token, rootId, "autoUploads").get("id").asLong();
        FileItemDto file = upload(token, "note.txt", "text/plain", "keep".getBytes(), userFolderId);

        registerRequest(token, "Documents", UUID.randomUUID())
                .andExpect(status().isConflict());

        Folder kept = folderRepository.findById(userFolderId).orElseThrow();
        assertThat(kept.getFolderType()).isEqualTo(FolderType.USER);
        assertThat(kept.getName()).isEqualTo("autoUploads");
        assertThat(foldersOfType(user, FolderType.AUTO_UPLOADS)).isEmpty();
        assertThat(folderRepository.findAll()).hasSize(2);
        assertThat(findFileItem(file.getId()).getFolder().getId()).isEqualTo(userFolderId);
        assertThat(Files.readString(physicalPath(findFileItem(file.getId())))).isEqualTo("keep");
        assertThat(libraryDirectories(user)).containsExactly("autoUploads");
    }

    @Test
    void fileNamedAutoUploadsInRootIsConflict() throws Exception {
        User user = createUser("user-file@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        upload(token, "AutoUploads", "text/plain", "file".getBytes(), getRootId(token));

        registerRequest(token, "Documents", UUID.randomUUID()).andExpect(status().isConflict());

        assertThat(foldersOfType(user, FolderType.AUTO_UPLOADS)).isEmpty();
        assertThat(libraryDirectories(user)).isEmpty();
    }

    @Test
    void invalidRequestsAreRejectedWithoutChanges() throws Exception {
        User user = createUser("invalid@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        getRootId(token);

        registerRequest(token, "a/b", UUID.randomUUID()).andExpect(status().isBadRequest());
        registerRequest(token, "trailing.", UUID.randomUUID()).andExpect(status().isBadRequest());
        registerRequest(token, "   ", UUID.randomUUID()).andExpect(status().isBadRequest());
        postBackup(token, """
                {"name":"Documents","sourceUuid":"not-a-uuid"}
                """).andExpect(status().isBadRequest());
        postBackup(token, """
                {"name":"Documents"}
                """).andExpect(status().isBadRequest());

        assertThat(folderRepository.findAll()).hasSize(1);
        assertThat(libraryDirectories(user)).isEmpty();
    }

    @Test
    void nameOccupiedOnlyOnDiskIsConflictAndKeepsForeignDirectory() throws Exception {
        User user = createUser("disk@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        register(token, "Documents", UUID.randomUUID());
        Path orphan = sourcePath(user, "Orphan");
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("inside.txt"), "foreign");

        registerRequest(token, "Orphan", UUID.randomUUID()).andExpect(status().isConflict());

        assertThat(folderRepository.findAll()).hasSize(3);
        assertThat(Files.readString(orphan.resolve("inside.txt"))).isEqualTo("foreign");
        assertThat(libraryDirectories(user))
                .containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents", "AutoUploads/Orphan");
    }

    @Test
    void containerOccupiedOnlyOnDiskIsConflictWithoutRecords() throws Exception {
        User user = createUser("disk-container@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        getRootId(token);
        Files.createDirectories(libraryRoot(user).resolve("AutoUploads"));

        registerRequest(token, "Documents", UUID.randomUUID()).andExpect(status().isConflict());

        assertThat(foldersOfType(user, FolderType.AUTO_UPLOADS)).isEmpty();
        assertThat(folderRepository.findAll()).hasSize(1);
        assertThat(libraryDirectories(user)).containsExactly("AutoUploads");
    }

    @Test
    void containerAcceptsOnlySourceRoots() throws Exception {
        User user = createUser("container@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        Long rootId = getRootId(token);
        JsonNode source = register(token, "Documents", UUID.randomUUID());
        Long containerId = source.get("parentId").asLong();
        Long sourceId = source.get("id").asLong();
        Long albumsId = createFolder(token, rootId, "Albums").get("id").asLong();
        FileItemDto note = upload(token, "note.txt", "text/plain", "note".getBytes(), rootId);

        perform(post("/api/v1/folders")
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":%d,\"name\":\"Extra\"}".formatted(containerId)))
                .andExpect(status().isBadRequest());
        perform(multipart("/api/v1/files")
                .file(filePart("direct.txt", "text/plain", "direct".getBytes()))
                .param("folderId", containerId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/files/{id}/move", note.getId())
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetFolderId\":%d}".formatted(containerId)))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/files/{id}/copy", note.getId())
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetFolderId\":%d}".formatted(containerId)))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/folders/{id}/move", albumsId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetParentId\":%d}".formatted(containerId)))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/folders/{id}/move", sourceId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetParentId\":%d}".formatted(albumsId)))
                .andExpect(status().isBadRequest());

        assertThat(getChildren(token, containerId)).extracting(child -> child.get("id").asLong()).containsExactly(sourceId);
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(findFileItem(note.getId()).getFolder().getId()).isEqualTo(rootId);
        assertThat(libraryDirectories(user)).containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents", "Albums");
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void containerCannotBeRenamedMovedOrDeleted() throws Exception {
        User user = createUser("system@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        Long albumsId = createFolder(token, getRootId(token), "Albums").get("id").asLong();
        Long containerId = register(token, "Documents", UUID.randomUUID()).get("parentId").asLong();

        perform(patch("/api/v1/folders/{id}", containerId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Other\"}"))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/folders/{id}/move", containerId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetParentId\":%d}".formatted(albumsId)))
                .andExpect(status().isBadRequest());
        perform(delete("/api/v1/folders/{id}", containerId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isBadRequest());

        assertThat(autoUploads(user).getName()).isEqualTo("AutoUploads");
        assertThat(libraryDirectories(user)).containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents", "Albums");
    }

    @Test
    void nestedFoldersAndFilesOfSourceUseOrdinaryLibraryRules() throws Exception {
        User user = createUser("nested@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        Long sourceId = register(token, "Documents", UUID.randomUUID()).get("id").asLong();

        JsonNode nested = createFolder(token, sourceId, "Чертежи");
        FileItemDto file = upload(token, "plan.txt", "text/plain", "plan".getBytes(), nested.get("id").asLong());

        assertThat(nested.get("folderType").asText()).isEqualTo("USER");
        assertThat(nested.get("backupSource").isNull()).isTrue();
        assertThat(physicalPath(findFileItem(file.getId())))
                .isEqualTo(libraryRoot(user).resolve("AutoUploads/Documents/Чертежи/plan.txt"));
        assertThat(Files.readString(physicalPath(findFileItem(file.getId())))).isEqualTo("plan");
    }

    @Test
    void cameraRootAndOrdinaryFoldersKeepWorking() throws Exception {
        User user = createUser("regression@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        Long rootId = getRootId(token);
        register(token, "Documents", UUID.randomUUID());

        Long cameraId = objectMapper.readTree(perform(post("/api/v1/folders/camera")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        FileItemDto photo = upload(token, "IMG_1.jpg", "image/jpeg", JPEG_BYTES);
        Long workId = createFolder(token, rootId, "Работа").get("id").asLong();
        perform(patch("/api/v1/folders/{id}", workId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Работа 2026\"}"))
                .andExpect(status().isOk());

        assertThat(photo.getFolderId()).isEqualTo(cameraId);
        assertThat(getRootId(token)).isEqualTo(rootId);
        assertThat(getChildren(token, rootId)).extracting(child -> child.get("folderType").asText())
                .containsExactlyInAnyOrder("CAMERA", "AUTO_UPLOADS", "USER");
        assertThat(getChildren(token, rootId)).allMatch(child -> child.get("backupSource").isNull());
        assertThat(libraryDirectories(user))
                .containsExactlyInAnyOrder("AutoUploads", "AutoUploads/Documents", "Работа 2026");
    }

    private JsonNode register(String token, String name, UUID uuid) throws Exception {
        return objectMapper.readTree(registerRequest(token, name, uuid)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions registerRequest(String token, String name, UUID uuid)
            throws Exception {
        return postBackup(token, body(name, uuid));
    }

    private org.springframework.test.web.servlet.ResultActions postBackup(String token, String body) throws Exception {
        return perform(post(BACKUP_URL)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String body(String name, UUID uuid) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("name", name, "sourceUuid", uuid.toString()));
    }

    private Callable<MockHttpServletResponse> registerCall(String token, String name, UUID uuid) throws Exception {
        String body = body(name, uuid);
        return () -> mockMvc.perform(post(BACKUP_URL)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
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

    private List<JsonNode> getChildren(String token, Long parentId) throws Exception {
        JsonNode children = objectMapper.readTree(perform(get("/api/v1/folders/{id}/children", parentId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<JsonNode> result = new ArrayList<>();
        children.forEach(result::add);
        return result;
    }

    private Folder autoUploads(User user) {
        List<Folder> containers = foldersOfType(user, FolderType.AUTO_UPLOADS);
        assertThat(containers).hasSize(1);
        return containers.get(0);
    }

    private List<Folder> foldersOfType(User user, FolderType type) {
        return folderRepository.findAll().stream()
                .filter(folder -> folder.getUser().getId().equals(user.getId()) && folder.getFolderType() == type)
                .toList();
    }

    private Path libraryRoot(User user) {
        return storageRoot().resolve(user.getId() + "/files");
    }

    private Path sourcePath(User user, String name) {
        return libraryRoot(user).resolve("AutoUploads").resolve(name);
    }

    // Каталоги физического дерева пользователя относительно {userId}/files, через '/'.
    private List<String> libraryDirectories(User user) throws Exception {
        Path root = libraryRoot(user);
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .toList();
        }
    }
}
