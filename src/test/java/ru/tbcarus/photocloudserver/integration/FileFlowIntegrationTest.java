package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.FileType;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";

    @Test
    void uploadNewFileStoresMetadataFoldersAndPhysicalFile() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        byte[] bytes = "image-bytes-1".getBytes();

        FileItemDto response = upload(token, "photo.jpg", "image/jpeg", bytes);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getFolderId()).isNotNull();
        assertThat(response.getOriginalFilename()).isEqualTo("photo.jpg");
        assertThat(response.getMimeType()).isEqualTo("image/jpeg");
        assertThat(response.getSize()).isEqualTo((long) bytes.length);
        assertThat(response.getFileType()).isEqualTo(FileType.IMAGE);
        assertThat(response.getChecksum()).isEqualTo(sha256(bytes));
        assertThat(response.getUploadedAt()).isNotNull();
        assertThat(response.getCapturedAt()).isNotNull();
        assertThat(response.getDeletedAt()).isNull();
        assertThat(response.getMetadata()).isNull();

        MvcResult result = perform(get("/api/v1/files/{id}", response.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("storageKey");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("storedObject");

        FileItem fileItem = findFileItem(response.getId());
        Path storedFile = storageRoot().resolve(fileItem.getStoredObject().getStorageKey());
        assertThat(fileItem.getOriginalFilename()).isEqualTo("photo.jpg");
        assertThat(fileItem.getChecksum()).isEqualTo(sha256(bytes));
        assertThat(fileItem.getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
        assertThat(fileItem.getFolder().getParent().getFolderType()).isEqualTo(FolderType.ROOT);
        assertThat(Files.exists(storedFile)).isTrue();
        assertThat(Files.readAllBytes(storedFile)).isEqualTo(bytes);
    }

    @Test
    void duplicateUploadReturnsExistingFileWithoutDuplicatingDatabaseStorageOrStoredObject() throws Exception {
        var user = createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        byte[] bytes = "same-content".getBytes();

        FileItemDto first = upload(token, "first.jpg", "image/jpeg", bytes);
        long storageFilesAfterFirstUpload = storageFileCount();
        FileItemDto second = upload(token, "second-name.jpg", "image/jpeg", bytes);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getChecksum()).isEqualTo(first.getChecksum());
        assertThat(fileItemRepository.findAll()).hasSize(1);
        assertThat(storedObjectRepository.findAll()).hasSize(1);
        assertThat(storageFileCount()).isEqualTo(storageFilesAfterFirstUpload);
    }

    @Test
    void listFilesReturnsOnlyCurrentUserFilesAndStablePaginationFields() throws Exception {
        var user1 = createUser("user1@test.local", PASSWORD);
        var user2 = createUser("user2@test.local", PASSWORD);
        String token1 = loginAndGetAccessToken(user1.getEmail(), PASSWORD);
        String token2 = loginAndGetAccessToken(user2.getEmail(), PASSWORD);
        FileItemDto user1First = upload(token1, "one.jpg", "image/jpeg", "one".getBytes());
        FileItemDto user1Second = upload(token1, "two.png", "image/png", "two".getBytes());
        upload(token2, "other.jpg", "image/jpeg", "other".getBytes());

        MvcResult result = perform(get("/api/v1/files")
                        .param("page", "0")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token1)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = response.get("items");
        List<Long> ids = items.findValues("id").stream().map(JsonNode::asLong).toList();
        assertThat(ids).containsExactlyInAnyOrder(user1First.getId(), user1Second.getId());
        assertThat(response.get("page").asInt()).isZero();
        assertThat(response.get("size").asInt()).isEqualTo(10);
        assertThat(response.get("totalElements").asLong()).isEqualTo(2);
        assertThat(response.has("pageable")).isFalse();
        assertThat(response.has("sort")).isFalse();
    }

    @Test
    void listFilesSortsByCapturedAtUploadedAtAndIdDescending() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        FileItemDto first = upload(token, "first.jpg", "image/jpeg", "first".getBytes());
        FileItemDto second = upload(token, "second.jpg", "image/jpeg", "second".getBytes());
        FileItemDto third = upload(token, "third.jpg", "image/jpeg", "third".getBytes());

        LocalDateTime sameCapturedAt = LocalDateTime.of(2026, 5, 20, 10, 0);
        jdbcTemplate.update("UPDATE file_item SET captured_at = ?, uploaded_at = ? WHERE id = ?",
                sameCapturedAt.minusDays(1), sameCapturedAt.plusMinutes(1), first.getId());
        jdbcTemplate.update("UPDATE file_item SET captured_at = ?, uploaded_at = ? WHERE id = ?",
                sameCapturedAt, sameCapturedAt.plusMinutes(1), second.getId());
        jdbcTemplate.update("UPDATE file_item SET captured_at = ?, uploaded_at = ? WHERE id = ?",
                sameCapturedAt, sameCapturedAt.plusMinutes(2), third.getId());

        MvcResult result = perform(get("/api/v1/files")
                        .param("page", "0")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        List<Long> ids = items.findValues("id").stream().map(JsonNode::asLong).toList();
        assertThat(ids).containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    void metadataReturnsCurrentUserFile() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        byte[] bytes = "metadata".getBytes();
        FileItemDto uploaded = upload(token, "metadata.jpg", "image/jpeg", bytes);

        MvcResult result = perform(get("/api/v1/files/{id}", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        FileItemDto response = objectMapper.readValue(result.getResponse().getContentAsString(), FileItemDto.class);
        assertThat(response.getId()).isEqualTo(uploaded.getId());
        assertThat(response.getOriginalFilename()).isEqualTo("metadata.jpg");
        assertThat(response.getChecksum()).isEqualTo(sha256(bytes));
    }

    @Test
    void downloadReturnsOriginalBytesAndContentType() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        byte[] bytes = "download-bytes".getBytes();
        FileItemDto uploaded = upload(token, "download.jpg", "image/jpeg", bytes);

        perform(get("/api/v1/files/{id}/download", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"download.jpg\""))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void checksumsReturnsOnlyCurrentUserChecksumsWithIds() throws Exception {
        var user1 = createUser("user1@test.local", PASSWORD);
        var user2 = createUser("user2@test.local", PASSWORD);
        String token1 = loginAndGetAccessToken(user1.getEmail(), PASSWORD);
        String token2 = loginAndGetAccessToken(user2.getEmail(), PASSWORD);
        byte[] currentUserBytes = "current-user".getBytes();
        byte[] otherUserBytes = "other-user".getBytes();
        FileItemDto current = upload(token1, "current.jpg", "image/jpeg", currentUserBytes);
        upload(token2, "other.jpg", "image/jpeg", otherUserBytes);

        MvcResult result = perform(get("/api/v1/files/checksums")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token1)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response).hasSize(1);
        assertThat(response.get(0).get("id").asLong()).isEqualTo(current.getId());
        assertThat(response.get(0).get("originalFilename").asText()).isEqualTo("current.jpg");
        assertThat(response.get(0).get("checksum").asText()).isEqualTo(sha256(currentUserBytes));
        assertThat(response.toString()).doesNotContain(sha256(otherUserBytes));
    }

    @Test
    void deleteRemovesDatabaseRowsAndPhysicalFileWithoutBody() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        FileItemDto uploaded = upload(token, "delete.jpg", "image/jpeg", "delete".getBytes());
        FileItem fileItem = findFileItem(uploaded.getId());
        Long storedObjectId = fileItem.getStoredObject().getId();
        Path storedFile = storageRoot().resolve(fileItem.getStoredObject().getStorageKey());

        MvcResult result = perform(delete("/api/v1/files/{id}", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(fileItemRepository.findById(uploaded.getId())).isEmpty();
        assertThat(storedObjectRepository.findById(storedObjectId)).isEmpty();
        assertThat(Files.exists(storedFile)).isFalse();
    }

    @Test
    void foreignMissingAndMissingPhysicalFilesReturnNotFound() throws Exception {
        var user1 = createUser("user1@test.local", PASSWORD);
        var user2 = createUser("user2@test.local", PASSWORD);
        String token1 = loginAndGetAccessToken(user1.getEmail(), PASSWORD);
        String token2 = loginAndGetAccessToken(user2.getEmail(), PASSWORD);
        FileItemDto uploaded = upload(token1, "private.jpg", "image/jpeg", "private".getBytes());

        perform(get("/api/v1/files/{id}", uploaded.getId()).header(HttpHeaders.AUTHORIZATION, authHeader(token2)))
                .andExpect(status().isNotFound());
        perform(get("/api/v1/files/{id}/download", uploaded.getId()).header(HttpHeaders.AUTHORIZATION, authHeader(token2)))
                .andExpect(status().isNotFound());
        perform(delete("/api/v1/files/{id}", uploaded.getId()).header(HttpHeaders.AUTHORIZATION, authHeader(token2)))
                .andExpect(status().isNotFound());
        perform(delete("/api/v1/files/{id}", 999999L).header(HttpHeaders.AUTHORIZATION, authHeader(token1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_ITEM_NOT_FOUND"));

        deletePhysicalFile(uploaded.getId());
        perform(get("/api/v1/files/{id}/download", uploaded.getId()).header(HttpHeaders.AUTHORIZATION, authHeader(token1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileEndpointWithoutOrInvalidTokenReturnsUnauthorized() throws Exception {
        perform(get("/api/v1/files"))
                .andExpect(status().isUnauthorized());

        perform(get("/api/v1/files")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());

        perform(multipart("/api/v1/files")
                        .file(filePart("invalid.jpg", "image/jpeg", "invalid".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());

        assertThat(storageFileCount()).isZero();
        assertThat(fileItemRepository.findAll()).isEmpty();
    }

    @Test
    void defaultFoldersStorageKeyAndFilenameSafetyAreApplied() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        FileItemDto image = upload(token, "..\\evil/../../photo.jpg", "image/jpeg", "image".getBytes());
        FileItemDto document = upload(token, "document.pdf", "application/pdf", "document".getBytes());

        FileItem imageItem = findFileItem(image.getId());
        FileItem documentItem = findFileItem(document.getId());
        assertThat(imageItem.getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
        assertThat(documentItem.getFolder().getFolderType()).isEqualTo(FolderType.FILES);
        assertThat(folderRepository.findAll()).extracting("folderType")
                .contains(FolderType.ROOT, FolderType.CAMERA, FolderType.FILES);

        String storageKey = imageItem.getStoredObject().getStorageKey();
        assertThat(Path.of(storageKey).isAbsolute()).isFalse();
        assertThat(storageKey).doesNotContain("..");
        assertThat(storageKey).doesNotContain("\\");
        Path resolved = storageRoot().resolve(storageKey).normalize();
        assertThat(resolved.startsWith(storageRoot())).isTrue();
    }

    @Test
    void storageKeyFilenameComponentIsLimitedTo255Characters() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        String longFilename = "a".repeat(300) + ".jpg";

        FileItemDto uploaded = upload(token, longFilename, "image/jpeg", "long".getBytes());

        String storageKey = findFileItem(uploaded.getId()).getStoredObject().getStorageKey();
        String filenameComponent = storageKey.substring(storageKey.lastIndexOf('/') + 1);
        assertThat(filenameComponent.length()).isLessThanOrEqualTo(255);
    }

    @Test
    void capturedAtFallsBackToUploadedAtAndMetadataIsNullableWhenExtractionHasNoData() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);

        FileItemDto uploaded = upload(token, "notes.txt", "text/plain", "plain".getBytes());

        assertThat(uploaded.getMetadata()).isNull();
        assertThat(uploaded.getCapturedAt()).isEqualTo(uploaded.getUploadedAt());
        assertThat(findFileItem(uploaded.getId()).getMetadata()).isNull();
    }

    @Test
    void metadataExtractionFailureDoesNotFailUpload() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);

        FileItemDto uploaded = upload(token, "broken.jpg", "image/jpeg", "not-a-real-jpeg".getBytes());

        assertThat(uploaded.getMetadata()).isNull();
        assertThat(uploaded.getCapturedAt()).isEqualTo(uploaded.getUploadedAt());
    }
}
