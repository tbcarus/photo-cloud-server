package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.multipart.MultipartFile;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.FileType;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final byte[] JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    @Test
    void uploadNewFileStoresMetadataFoldersAndPhysicalFile() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        byte[] bytes = JPEG_BYTES;

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
        assertThat(result.getResponse().getContentAsString()).doesNotContain("filePath");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("filename");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("storedObject");

        FileItem fileItem = findFileItem(response.getId());
        Path storedFile = storedObjectPath(fileItem.getStoredObject());
        assertThat(fileItem.getOriginalName()).isEqualTo("photo.jpg");
        assertThat(fileItem.getStoredObject().getChecksum()).isEqualTo(sha256(bytes));
        assertThat(fileItem.getStoredObject().getSize()).isEqualTo(bytes.length);
        assertThat(fileItem.getStoredObject().getDetectedMimeType()).isEqualTo("image/jpeg");
        assertThat(fileItem.getStoredObject().getFileType()).isEqualTo(FileType.IMAGE);
        assertThat(fileItem.getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
        assertThat(fileItem.getFolder().getParent().getFolderType()).isEqualTo(FolderType.ROOT);
        assertThat(Files.exists(storedFile)).isTrue();
        assertThat(Files.readAllBytes(storedFile)).isEqualTo(bytes);
    }

    @Test
    void duplicateUploadCreatesNewFileItemWithoutDuplicatingPhysicalStorageOrStoredObject() throws Exception {
        var user = createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        byte[] bytes = "same-content".getBytes();

        FileItemDto first = upload(token, "first.jpg", "image/jpeg", bytes);
        long storageFilesAfterFirstUpload = storageFileCount();
        FileItemDto second = upload(token, "second-name.jpg", "image/jpeg", bytes);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getChecksum()).isEqualTo(first.getChecksum());
        assertThat(findFileItem(second.getId()).getStoredObject().getId())
                .isEqualTo(findFileItem(first.getId()).getStoredObject().getId());
        assertThat(fileItemRepository.findAll()).hasSize(2);
        assertThat(storedObjectRepository.findAll()).hasSize(1);
        assertThat(storageFileCount()).isEqualTo(storageFilesAfterFirstUpload);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void listFilesByFolderReturnsOnlyDirectFilesFromThatFolder() throws Exception {
        createUser("folders-list@test.local", PASSWORD);
        String token = loginAndGetAccessToken("folders-list@test.local", PASSWORD);
        Long rootId = getRootId(token);
        Long firstFolderId = createFolder(token, rootId, "First").get("id").asLong();
        Long secondFolderId = createFolder(token, rootId, "Second").get("id").asLong();
        FileItemDto first = upload(token, "first.txt", "text/plain", "first".getBytes(), firstFolderId);
        upload(token, "second.txt", "text/plain", "second".getBytes(), secondFolderId);

        MvcResult result = perform(get("/api/v1/files")
                        .param("folderId", firstFolderId.toString())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("id").asLong()).isEqualTo(first.getId());
    }

    @Test
    void uploadCanTargetFolderAndDefaultUploadStillUsesSystemFolders() throws Exception {
        createUser("upload-target@test.local", PASSWORD);
        String token = loginAndGetAccessToken("upload-target@test.local", PASSWORD);
        Long rootId = getRootId(token);
        Long targetFolderId = createFolder(token, rootId, "Target").get("id").asLong();

        FileItemDto targeted = upload(token, "target.txt", "text/plain", "target".getBytes(), targetFolderId);
        FileItemDto defaultUpload = upload(token, "default.txt", "text/plain", "default".getBytes());

        assertThat(findFileItem(targeted.getId()).getFolder().getId()).isEqualTo(targetFolderId);
        assertThat(findFileItem(defaultUpload.getId()).getFolder().getFolderType()).isEqualTo(FolderType.FILES);
    }

    @Test
    void duplicateUploadNameConflictsOutsideCameraButCameraAllowsSameName() throws Exception {
        createUser("duplicate-name@test.local", PASSWORD);
        String token = loginAndGetAccessToken("duplicate-name@test.local", PASSWORD);
        upload(token, "same.txt", "text/plain", "one".getBytes());

        perform(multipart("/api/v1/files")
                        .file(filePart("same.txt", "text/plain", "two".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        FileItemDto firstCamera = upload(token, "IMG_0001.jpg", "image/jpeg", JPEG_BYTES);
        FileItemDto secondCamera = upload(token, "IMG_0001.jpg", "image/jpeg", JPEG_BYTES);

        assertThat(firstCamera.getId()).isNotEqualTo(secondCamera.getId());
        assertThat(findFileItem(firstCamera.getId()).getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
        assertThat(findFileItem(secondCamera.getId()).getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
    }

    @Test
    void copyCreatesNewStoredObjectPhysicalFileAndDoesNotUseDedup() throws Exception {
        createUser("copy@test.local", PASSWORD);
        String token = loginAndGetAccessToken("copy@test.local", PASSWORD);
        FileItemDto source = upload(token, "source.txt", "text/plain", "copy-content".getBytes());
        FileItem sourceItem = findFileItem(source.getId());
        Long sourceStoredObjectId = sourceItem.getStoredObject().getId();
        long storageFilesBeforeCopy = storageFileCount();

        MvcResult result = perform(post("/api/v1/files/{id}/copy", source.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalName":"source-copy.txt"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        FileItemDto copied = objectMapper.readValue(result.getResponse().getContentAsString(), FileItemDto.class);
        FileItem copiedItem = findFileItem(copied.getId());
        assertThat(copiedItem.getStoredObject().getId()).isNotEqualTo(sourceStoredObjectId);
        assertThat(copiedItem.getStoredObject().getChecksum()).isEqualTo(sourceItem.getStoredObject().getChecksum());
        assertThat(Files.readAllBytes(storedObjectPath(copiedItem.getStoredObject()))).isEqualTo("copy-content".getBytes());
        assertThat(storageFileCount()).isEqualTo(storageFilesBeforeCopy + 1);
        assertThat(storedObjectRepository.findAll()).hasSize(2);
    }

    @Test
    void copyInSameFolderWithoutNewNameConflictsOutsideCamera() throws Exception {
        createUser("copy-conflict@test.local", PASSWORD);
        String token = loginAndGetAccessToken("copy-conflict@test.local", PASSWORD);
        FileItemDto source = upload(token, "source.txt", "text/plain", "copy-conflict".getBytes());

        perform(post("/api/v1/files/{id}/copy", source.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void moveChangesOnlyFolderAndKeepsStoredObjectAndPhysicalFile() throws Exception {
        createUser("move-file@test.local", PASSWORD);
        String token = loginAndGetAccessToken("move-file@test.local", PASSWORD);
        Long rootId = getRootId(token);
        Long targetFolderId = createFolder(token, rootId, "Moved").get("id").asLong();
        FileItemDto uploaded = upload(token, "move.txt", "text/plain", "move".getBytes());
        FileItem before = findFileItem(uploaded.getId());
        Long storedObjectId = before.getStoredObject().getId();
        Path physicalFile = storedObjectPath(before.getStoredObject());

        MvcResult result = perform(post("/api/v1/files/{id}/move", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetFolderId":%d}
                                """.formatted(targetFolderId)))
                .andExpect(status().isOk())
                .andReturn();

        FileItemDto moved = objectMapper.readValue(result.getResponse().getContentAsString(), FileItemDto.class);
        FileItem after = findFileItem(moved.getId());
        assertThat(after.getFolder().getId()).isEqualTo(targetFolderId);
        assertThat(after.getStoredObject().getId()).isEqualTo(storedObjectId);
        assertThat(Files.exists(physicalFile)).isTrue();
    }

    @Test
    void renameChangesOnlyOriginalNameAndKeepsPhysicalFilename() throws Exception {
        createUser("rename-file@test.local", PASSWORD);
        String token = loginAndGetAccessToken("rename-file@test.local", PASSWORD);
        FileItemDto uploaded = upload(token, "before.txt", "text/plain", "rename".getBytes());
        FileItem before = findFileItem(uploaded.getId());
        String physicalFilename = before.getStoredObject().getFilename();

        MvcResult result = perform(patch("/api/v1/files/{id}", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalName":"after.txt"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        FileItemDto renamed = objectMapper.readValue(result.getResponse().getContentAsString(), FileItemDto.class);
        FileItem after = findFileItem(renamed.getId());
        assertThat(after.getOriginalName()).isEqualTo("after.txt");
        assertThat(after.getStoredObject().getFilename()).isEqualTo(physicalFilename);
    }

    @Test
    void renameConflictIsRejectedOutsideCamera() throws Exception {
        createUser("rename-conflict@test.local", PASSWORD);
        String token = loginAndGetAccessToken("rename-conflict@test.local", PASSWORD);
        FileItemDto first = upload(token, "first.txt", "text/plain", "first".getBytes());
        upload(token, "second.txt", "text/plain", "second".getBytes());

        perform(patch("/api/v1/files/{id}", first.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalName":"second.txt"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void foreignFolderCannotBeUsedForListUploadCopyOrMove() throws Exception {
        createUser("file-owner@test.local", PASSWORD);
        createUser("folder-owner@test.local", PASSWORD);
        String fileOwnerToken = loginAndGetAccessToken("file-owner@test.local", PASSWORD);
        String folderOwnerToken = loginAndGetAccessToken("folder-owner@test.local", PASSWORD);
        Long foreignRootId = getRootId(folderOwnerToken);
        FileItemDto uploaded = upload(fileOwnerToken, "private.txt", "text/plain", "private".getBytes());

        perform(get("/api/v1/files")
                        .param("folderId", foreignRootId.toString())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(fileOwnerToken)))
                .andExpect(status().isNotFound());
        perform(multipart("/api/v1/files")
                        .file(filePart("target.txt", "text/plain", "target".getBytes()))
                        .param("folderId", foreignRootId.toString())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(fileOwnerToken)))
                .andExpect(status().isNotFound());
        perform(post("/api/v1/files/{id}/copy", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(fileOwnerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetFolderId":%d,"originalName":"copy.txt"}
                                """.formatted(foreignRootId)))
                .andExpect(status().isNotFound());
        perform(post("/api/v1/files/{id}/move", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(fileOwnerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetFolderId":%d}
                                """.formatted(foreignRootId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadDoesNotCallMultipartFileGetBytes() throws Exception {
        var user = createUser("stream@test.local", PASSWORD);
        byte[] bytes = "streamed-content".getBytes();
        MultipartFile file = new NoGetBytesMultipartFile("stream.txt", bytes, "image/jpeg");

        FileItemDto uploaded = fileItemService.uploadFile(file, user);

        assertThat(uploaded.getId()).isNotNull();
        assertThat(uploaded.getChecksum()).isEqualTo(sha256(bytes));
        assertThat(uploaded.getSize()).isEqualTo((long) bytes.length);
        assertThat(storageFileCount()).isEqualTo(1);
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void uploadOverMaxFileSizeReturnsErrorAndRemovesTempFile() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        byte[] bytes = new byte[2048];

        perform(multipart("/api/v1/files")
                        .file(filePart("too-big.bin", "application/octet-stream", bytes))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));

        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(storageFileCount()).isZero();
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void tempFileIsRemovedWhenStreamingFails() throws Exception {
        var user = createUser("broken-stream@test.local", PASSWORD);
        MultipartFile file = new FailingInputStreamMultipartFile("broken.bin", "broken".getBytes());

        try {
            fileItemService.uploadFile(file, user);
        } catch (IOException ignored) {
        }

        assertThat(fileItemRepository.findAll()).isEmpty();
        assertThat(storedObjectRepository.findAll()).isEmpty();
        assertThat(storageFileCount()).isZero();
        assertThat(tempFileCount()).isZero();
    }

    @Test
    void mimeTypeIsDetectedFromContentInsteadOfMultipartHeader() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);

        FileItemDto uploaded = upload(token, "real-image.bin", "text/plain", JPEG_BYTES);
        FileItem fileItem = findFileItem(uploaded.getId());

        assertThat(uploaded.getMimeType()).isEqualTo("image/jpeg");
        assertThat(uploaded.getFileType()).isEqualTo(FileType.IMAGE);
        assertThat(fileItem.getStoredObject().getDetectedMimeType()).isEqualTo("image/jpeg");
        assertThat(fileItem.getStoredObject().getFileType()).isEqualTo(FileType.IMAGE);
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
        FileItem fileItem = findFileItem(uploaded.getId());
        jdbcTemplate.update("UPDATE stored_object SET detected_mime_type = ? WHERE id = ?",
                "image/png", fileItem.getStoredObject().getId());

        perform(get("/api/v1/files/{id}/download", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"download.jpg\""))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void fileItemDtoReturnsPhysicalFieldsFromStoredObject() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        FileItemDto uploaded = upload(token, "dto.jpg", "image/jpeg", "dto".getBytes());
        FileItem fileItem = findFileItem(uploaded.getId());
        String storedChecksum = "b".repeat(64);

        jdbcTemplate.update("""
                        UPDATE stored_object
                        SET checksum = ?, size = ?, detected_mime_type = ?, file_type = ?
                        WHERE id = ?
                        """,
                storedChecksum, 777L, "text/plain", "DOCUMENT", fileItem.getStoredObject().getId());

        MvcResult result = perform(get("/api/v1/files/{id}", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        FileItemDto response = objectMapper.readValue(result.getResponse().getContentAsString(), FileItemDto.class);
        assertThat(response.getChecksum()).isEqualTo(storedChecksum);
        assertThat(response.getSize()).isEqualTo(777L);
        assertThat(response.getMimeType()).isEqualTo("text/plain");
        assertThat(response.getFileType()).isEqualTo(FileType.DOCUMENT);
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
    void checksumExistsRequiresAuthentication() throws Exception {
        perform(post("/api/v1/files/checksums/exists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checksumExistsBody(List.of("a".repeat(64)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checksumExistsValidatesRequestBody() throws Exception {
        createUser("checksum-validation@test.local", PASSWORD);
        String token = loginAndGetAccessToken("checksum-validation@test.local", PASSWORD);

        perform(post("/api/v1/files/checksums/exists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checksums":null}
                                """))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/files/checksums/exists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checksums":[]}
                                """))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/files/checksums/exists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checksumExistsBody(List.of("a".repeat(63)))))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/files/checksums/exists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checksumExistsBody(List.of("g".repeat(64)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checksumExistsRejectsBatchOverLimit() throws Exception {
        createUser("checksum-batch@test.local", PASSWORD);
        String token = loginAndGetAccessToken("checksum-batch@test.local", PASSWORD);
        List<String> checksums = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            checksums.add("%064x".formatted(i));
        }

        perform(post("/api/v1/files/checksums/exists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checksumExistsBody(checksums)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checksumExistsReturnsExistingAndMissingChecksumsOnlyForCurrentUser() throws Exception {
        createUser("checksum-user@test.local", PASSWORD);
        createUser("checksum-other@test.local", PASSWORD);
        String token = loginAndGetAccessToken("checksum-user@test.local", PASSWORD);
        String otherToken = loginAndGetAccessToken("checksum-other@test.local", PASSWORD);
        FileItemDto current = upload(token, "current.txt", "text/plain", "current".getBytes());
        FileItemDto other = upload(otherToken, "other.txt", "text/plain", "other".getBytes());
        String missing = "f".repeat(64);

        JsonNode response = checksumExists(token, List.of(current.getChecksum(), other.getChecksum(), missing));

        assertThat(texts(response.get("existing"))).containsExactly(current.getChecksum());
        assertThat(texts(response.get("missing"))).containsExactly(other.getChecksum(), missing);
        assertThat(response.toString()).doesNotContain("fileId");
        assertThat(response.toString()).doesNotContain("folderId");
        assertThat(response.toString()).doesNotContain("originalName");
        assertThat(response.toString()).doesNotContain("storedObject");
    }

    @Test
    void checksumExistsHandlesAllMissingAllExistingDuplicatesAndUppercase() throws Exception {
        createUser("checksum-cases@test.local", PASSWORD);
        String token = loginAndGetAccessToken("checksum-cases@test.local", PASSWORD);
        FileItemDto first = upload(token, "first.txt", "text/plain", "first".getBytes());
        FileItemDto second = upload(token, "second.txt", "text/plain", "second".getBytes());

        JsonNode allMissing = checksumExists(token, List.of("a".repeat(64), "b".repeat(64)));
        assertThat(allMissing.get("existing")).isEmpty();
        assertThat(texts(allMissing.get("missing"))).containsExactly("a".repeat(64), "b".repeat(64));

        JsonNode allExisting = checksumExists(token, List.of(first.getChecksum(), second.getChecksum()));
        assertThat(texts(allExisting.get("existing"))).containsExactly(first.getChecksum(), second.getChecksum());
        assertThat(allExisting.get("missing")).isEmpty();

        JsonNode normalized = checksumExists(token, List.of(first.getChecksum().toUpperCase(), first.getChecksum(), second.getChecksum()));
        assertThat(texts(normalized.get("existing"))).containsExactly(first.getChecksum(), second.getChecksum());
        assertThat(normalized.get("missing")).isEmpty();
    }

    @Test
    void checksumExistsReturnsChecksumOnceWhenSeveralStoredObjectsHaveSameChecksum() throws Exception {
        createUser("checksum-copy@test.local", PASSWORD);
        String token = loginAndGetAccessToken("checksum-copy@test.local", PASSWORD);
        FileItemDto source = upload(token, "source.txt", "text/plain", "same-physical-content".getBytes());

        perform(post("/api/v1/files/{id}/copy", source.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalName":"source-copy.txt"}
                                """))
                .andExpect(status().isOk());

        assertThat(storedObjectRepository.findAll()).hasSize(2);
        JsonNode response = checksumExists(token, List.of(source.getChecksum(), source.getChecksum().toUpperCase()));

        assertThat(texts(response.get("existing"))).containsExactly(source.getChecksum());
        assertThat(response.get("missing")).isEmpty();
    }

    @Test
    void deleteRemovesDatabaseRowsAndPhysicalFileWithoutBody() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        FileItemDto uploaded = upload(token, "delete.jpg", "image/jpeg", "delete".getBytes());
        FileItem fileItem = findFileItem(uploaded.getId());
        Long storedObjectId = fileItem.getStoredObject().getId();
        Path storedFile = storedObjectPath(fileItem.getStoredObject());

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
    void ownerDeleteRemovesAllFileItemsStoredObjectAndPhysicalFile() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        byte[] bytes = "same-delete".getBytes();
        FileItemDto first = upload(token, "first.jpg", "image/jpeg", bytes);
        FileItemDto second = upload(token, "second.jpg", "image/jpeg", bytes);
        FileItem firstItem = findFileItem(first.getId());
        Long storedObjectId = firstItem.getStoredObject().getId();
        Path storedFile = storedObjectPath(firstItem.getStoredObject());

        perform(delete("/api/v1/files/{id}", first.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNoContent());

        assertThat(fileItemRepository.findById(first.getId())).isEmpty();
        assertThat(fileItemRepository.findById(second.getId())).isEmpty();
        assertThat(storedObjectRepository.findById(storedObjectId)).isEmpty();
        assertThat(Files.exists(storedFile)).isFalse();
    }

    @Test
    void nonOwnerDeleteRemovesOnlyFileItemAndKeepsStoredObjectAndPhysicalFile() throws Exception {
        User owner = createUser("owner@test.local", PASSWORD);
        User other = createUser("other@test.local", PASSWORD);
        String ownerToken = loginAndGetAccessToken(owner.getEmail(), PASSWORD);
        String otherToken = loginAndGetAccessToken(other.getEmail(), PASSWORD);

        FileItemDto ownerUpload = upload(ownerToken, "owner.jpg", "image/jpeg", "owner-bytes".getBytes());
        FileItemDto otherSeed = upload(otherToken, "seed.jpg", "image/jpeg", "seed-bytes".getBytes());
        FileItem ownerItem = findFileItem(ownerUpload.getId());
        FileItem otherSeedItem = findFileItem(otherSeed.getId());
        Path storedFile = storedObjectPath(ownerItem.getStoredObject());

        FileItem otherLogicalItem = fileItemRepository.save(FileItem.builder()
                .user(other)
                .folder(otherSeedItem.getFolder())
                .storedObject(ownerItem.getStoredObject())
                .originalName("other-logical.jpg")
                .capturedAt(LocalDateTime.now())
                .uploadedAt(LocalDateTime.now())
                .build());

        perform(delete("/api/v1/files/{id}", otherLogicalItem.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(otherToken)))
                .andExpect(status().isNoContent());

        assertThat(fileItemRepository.findById(otherLogicalItem.getId())).isEmpty();
        assertThat(fileItemRepository.findById(ownerUpload.getId())).isPresent();
        assertThat(storedObjectRepository.findById(ownerItem.getStoredObject().getId())).isPresent();
        assertThat(Files.exists(storedFile)).isTrue();
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
    void defaultFoldersPhysicalPathAndFilenameSafetyAreApplied() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        FileItemDto image = upload(token, "..\\evil/../../photo.jpg", "image/jpeg", JPEG_BYTES);
        FileItemDto document = upload(token, "document.pdf", "application/pdf", "document".getBytes());

        FileItem imageItem = findFileItem(image.getId());
        FileItem documentItem = findFileItem(document.getId());
        assertThat(imageItem.getFolder().getFolderType()).isEqualTo(FolderType.CAMERA);
        assertThat(documentItem.getFolder().getFolderType()).isEqualTo(FolderType.FILES);
        assertThat(folderRepository.findAll()).extracting("folderType")
                .contains(FolderType.ROOT, FolderType.CAMERA, FolderType.FILES);

        String filePath = imageItem.getStoredObject().getFilePath();
        String filename = imageItem.getStoredObject().getFilename();
        assertThat(Path.of(filePath).isAbsolute()).isFalse();
        assertThat(filePath).doesNotContain("..");
        assertThat(filePath).doesNotContain("\\");
        assertThat(filename).doesNotContain("/");
        assertThat(filename).doesNotContain("\\");
        assertThat(filename).contains("_");
        Path resolved = storedObjectPath(imageItem.getStoredObject());
        assertThat(resolved.startsWith(storageRoot())).isTrue();
    }

    @Test
    void physicalFilenameComponentIsLimitedTo255Characters() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        String longFilename = "a".repeat(300) + ".jpg";

        FileItemDto uploaded = upload(token, longFilename, "image/jpeg", "long".getBytes());

        String filename = findFileItem(uploaded.getId()).getStoredObject().getFilename();
        assertThat(filename.length()).isLessThanOrEqualTo(255);
        assertThat(filename).endsWith(".jpg");
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

    private static class NoGetBytesMultipartFile extends MockMultipartFile {

        NoGetBytesMultipartFile(String filename, byte[] content, String contentType) {
            super("file", filename, contentType, content);
        }

        @Override
        public byte[] getBytes() {
            throw new AssertionError("getBytes must not be used by upload pipeline");
        }
    }

    private FileItemDto upload(String accessToken, String filename, String contentType, byte[] bytes, Long folderId) throws Exception {
        MvcResult result = perform(multipart("/api/v1/files")
                        .file(filePart(filename, contentType, bytes))
                        .param("folderId", folderId.toString())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(accessToken)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), FileItemDto.class);
    }

    private Long getRootId(String token) throws Exception {
        MvcResult result = perform(get("/api/v1/folders/root")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode createFolder(String token, Long parentId, String name) throws Exception {
        MvcResult result = perform(post("/api/v1/folders")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":%d,"name":"%s"}
                                """.formatted(parentId, name)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode checksumExists(String token, List<String> checksums) throws Exception {
        MvcResult result = perform(post("/api/v1/files/checksums/exists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checksumExistsBody(checksums)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String checksumExistsBody(List<String> checksums) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("checksums", checksums));
    }

    private List<String> texts(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static class FailingInputStreamMultipartFile extends MockMultipartFile {

        FailingInputStreamMultipartFile(String filename, byte[] content) {
            super("file", filename, "application/octet-stream", content);
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                private boolean firstRead = true;

                @Override
                public int read() throws IOException {
                    if (firstRead) {
                        firstRead = false;
                        return 'x';
                    }
                    throw new IOException("stream failed");
                }
            };
        }
    }
}
