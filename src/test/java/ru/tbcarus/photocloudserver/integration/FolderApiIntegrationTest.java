package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FolderApiIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";

    @Test
    void rootIsCreatedOnlyOnce() throws Exception {
        User user = createUser("root@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);

        Long firstRootId = getRootId(token);
        Long secondRootId = getRootId(token);

        assertThat(secondRootId).isEqualTo(firstRootId);
        assertThat(folderRepository.findAll()).filteredOn(folder ->
                folder.getUser().getId().equals(user.getId()) && folder.getFolderType() == FolderType.ROOT).hasSize(1);
    }

    @Test
    void cannotDeleteRootCameraOrFiles() throws Exception {
        String token = createUserAndLogin("delete-system@test.local");
        Long rootId = getRootId(token);
        FileItemDto image = upload(token, "photo.jpg", "image/jpeg", jpegBytes());
        FileItemDto document = upload(token, "document.pdf", "application/pdf", "document".getBytes());
        Long cameraId = findFileItem(image.getId()).getFolder().getId();
        Long filesId = findFileItem(document.getId()).getFolder().getId();

        perform(delete("/api/v1/folders/{id}", rootId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isBadRequest());
        perform(delete("/api/v1/folders/{id}", cameraId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isBadRequest());
        perform(delete("/api/v1/folders/{id}", filesId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotMoveRootCameraOrFiles() throws Exception {
        String token = createUserAndLogin("move-system@test.local");
        Long rootId = getRootId(token);
        Long userFolderId = createFolder(token, rootId, "Albums").get("id").asLong();
        FileItemDto image = upload(token, "photo.jpg", "image/jpeg", jpegBytes());
        FileItemDto document = upload(token, "document.pdf", "application/pdf", "document".getBytes());
        Long cameraId = findFileItem(image.getId()).getFolder().getId();
        Long filesId = findFileItem(document.getId()).getFolder().getId();

        perform(post("/api/v1/folders/{id}/move", rootId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveBody(userFolderId)))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/folders/{id}/move", cameraId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveBody(userFolderId)))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/folders/{id}/move", filesId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveBody(userFolderId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotRenameRootCameraOrFiles() throws Exception {
        String token = createUserAndLogin("rename-system@test.local");
        Long rootId = getRootId(token);
        FileItemDto image = upload(token, "photo.jpg", "image/jpeg", jpegBytes());
        Long cameraId = findFileItem(image.getId()).getFolder().getId();

        perform(patch("/api/v1/folders/{id}", rootId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameBody("New root")))
                .andExpect(status().isBadRequest());
        perform(patch("/api/v1/folders/{id}", cameraId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameBody("New camera")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotCreateFolderInsideCameraOrFiles() throws Exception {
        String token = createUserAndLogin("system-leaf@test.local");
        FileItemDto image = upload(token, "photo.jpg", "image/jpeg", jpegBytes());
        FileItemDto document = upload(token, "document.pdf", "application/pdf", "document".getBytes());

        perform(post("/api/v1/folders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(findFileItem(image.getId()).getFolder().getId(), "Child")))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/folders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(findFileItem(document.getId()).getFolder().getId(), "Child")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateNameInSameParentIsRejectedCaseInsensitive() throws Exception {
        String token = createUserAndLogin("duplicate@test.local");
        Long rootId = getRootId(token);
        createFolder(token, rootId, "Trips");

        perform(post("/api/v1/folders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(rootId, "trips")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void cameraAndFilesNamesAreReservedInRoot() throws Exception {
        String token = createUserAndLogin("reserved@test.local");
        Long rootId = getRootId(token);

        perform(post("/api/v1/folders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(rootId, "Camera")))
                .andExpect(status().isConflict());
        perform(post("/api/v1/folders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(rootId, "files")))
                .andExpect(status().isConflict());
    }

    @Test
    void sameNameIsAllowedInDifferentParents() throws Exception {
        String token = createUserAndLogin("same-name@test.local");
        Long rootId = getRootId(token);
        Long firstParent = createFolder(token, rootId, "First").get("id").asLong();
        Long secondParent = createFolder(token, rootId, "Second").get("id").asLong();

        JsonNode firstChild = createFolder(token, firstParent, "Trips");
        JsonNode secondChild = createFolder(token, secondParent, "Trips");

        assertThat(firstChild.get("id").asLong()).isNotEqualTo(secondChild.get("id").asLong());
        assertThat(firstChild.get("name").asText()).isEqualTo("Trips");
        assertThat(secondChild.get("name").asText()).isEqualTo("Trips");
    }

    @Test
    void cannotMoveFolderIntoItselfOrDescendant() throws Exception {
        String token = createUserAndLogin("move-cycle@test.local");
        Long rootId = getRootId(token);
        Long parentId = createFolder(token, rootId, "Parent").get("id").asLong();
        Long childId = createFolder(token, parentId, "Child").get("id").asLong();

        perform(post("/api/v1/folders/{id}/move", parentId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveBody(parentId)))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/folders/{id}/move", parentId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveBody(childId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotWorkWithForeignFolder() throws Exception {
        String token1 = createUserAndLogin("owner@test.local");
        String token2 = createUserAndLogin("other@test.local");
        Long ownerRootId = getRootId(token1);
        Long ownerFolderId = createFolder(token1, ownerRootId, "Private").get("id").asLong();
        Long otherRootId = getRootId(token2);

        perform(get("/api/v1/folders/{id}/children", ownerFolderId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token2)))
                .andExpect(status().isNotFound());
        perform(post("/api/v1/folders/{id}/move", ownerFolderId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveBody(otherRootId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteOnlyEmptyUserFolder() throws Exception {
        User user = createUser("delete-empty@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        Long rootId = getRootId(token);
        Long parentId = createFolder(token, rootId, "Parent").get("id").asLong();
        createFolder(token, parentId, "Child");

        perform(delete("/api/v1/folders/{id}", parentId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isBadRequest());

        Long folderWithFileId = createFolder(token, rootId, "With file").get("id").asLong();
        FileItemDto uploaded = upload(token, "seed.jpg", "image/jpeg", jpegBytes());
        FileItem seed = findFileItem(uploaded.getId());
        Folder folderWithFile = folderRepository.findById(folderWithFileId).orElseThrow();
        fileItemRepository.save(FileItem.builder()
                .user(user)
                .folder(folderWithFile)
                .storedObject(seed.getStoredObject())
                .checksum(seed.getStoredObject().getChecksum())
                .originalName("logical-copy.jpg")
                .capturedAt(LocalDateTime.now())
                .uploadedAt(LocalDateTime.now())
                .build());

        perform(delete("/api/v1/folders/{id}", folderWithFileId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isBadRequest());

        Long emptyId = createFolder(token, rootId, "Empty").get("id").asLong();
        perform(delete("/api/v1/folders/{id}", emptyId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNoContent());
        assertThat(folderRepository.findById(emptyId)).isEmpty();
    }

    @Test
    void childrenReturnsOnlyCurrentUserFolders() throws Exception {
        String token1 = createUserAndLogin("children1@test.local");
        String token2 = createUserAndLogin("children2@test.local");
        Long root1 = getRootId(token1);
        Long root2 = getRootId(token2);
        JsonNode currentChild = createFolder(token1, root1, "Visible");
        createFolder(token2, root2, "Hidden");

        JsonNode children = getChildren(token1, root1);

        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("id").asLong()).isEqualTo(currentChild.get("id").asLong());
        assertThat(children.toString()).doesNotContain("Hidden");
    }

    @Test
    void uploadContinuesToUseDefaultFolders() throws Exception {
        String token = createUserAndLogin("upload-folder@test.local");

        FileItemDto image = upload(token, "photo.jpg", "image/jpeg", jpegBytes());
        FileItemDto document = upload(token, "document.pdf", "application/pdf", "document".getBytes());

        assertThat(findFileItem(image.getId()).getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
        assertThat(findFileItem(document.getId()).getFolder().getFolderType()).isEqualTo(FolderType.FILES);
    }

    private String createUserAndLogin(String email) throws Exception {
        createUser(email, PASSWORD);
        return loginAndGetAccessToken(email, PASSWORD);
    }

    private Long getRootId(String token) throws Exception {
        String body = perform(get("/api/v1/folders/root")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private JsonNode createFolder(String token, Long parentId, String name) throws Exception {
        return objectMapper.readTree(perform(post("/api/v1/folders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(parentId, name)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private JsonNode getChildren(String token, Long parentId) throws Exception {
        return objectMapper.readTree(perform(get("/api/v1/folders/{id}/children", parentId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private String createBody(Long parentId, String name) {
        return """
                {"parentId":%s,"name":"%s"}
                """.formatted(parentId, name);
    }

    private String renameBody(String name) {
        return """
                {"name":"%s"}
                """.formatted(name);
    }

    private String moveBody(Long targetParentId) {
        return """
                {"targetParentId":%d}
                """.formatted(targetParentId);
    }

    private byte[] jpegBytes() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
                0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
        };
    }
}
