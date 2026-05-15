package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import ru.tbcarus.photocloudserver.model.MediaFile;
import ru.tbcarus.photocloudserver.model.MediaType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.MediaFileDto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MediaFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";

    @Test
    void uploadNewFileStoresMetadataAndPhysicalFile() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        byte[] bytes = "image-bytes-1".getBytes();

        MediaFileDto response = upload(token, "photo.jpg", "image/jpeg", bytes);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getOriginalFilename()).isEqualTo("photo.jpg");
        assertThat(response.getMimeType()).isEqualTo("image/jpeg");
        assertThat(response.getSize()).isEqualTo((long) bytes.length);
        assertThat(response.getType()).isEqualTo(MediaType.IMAGE);
        assertThat(response.getChecksum()).isEqualTo(sha256(bytes));

        MediaFile media = findMedia(response.getId());
        Path storedFile = Path.of(media.getStoragePath());
        assertThat(media.getOriginalFilename()).isEqualTo("photo.jpg");
        assertThat(media.getChecksum()).isEqualTo(sha256(bytes));
        assertThat(Files.exists(storedFile)).isTrue();
        assertThat(Files.readAllBytes(storedFile)).isEqualTo(bytes);
    }

    @Test
    void duplicateUploadReturnsExistingFileWithoutDuplicatingDatabaseOrStorage() throws Exception {
        User user = createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        byte[] bytes = "same-content".getBytes();

        MediaFileDto first = upload(token, "first.jpg", "image/jpeg", bytes);
        long storageFilesAfterFirstUpload = storageFileCount();
        MediaFileDto second = upload(token, "second-name.jpg", "image/jpeg", bytes);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getChecksum()).isEqualTo(first.getChecksum());
        assertThat(mediaFileRepository.findAllByUserId(user.getId())).hasSize(1);
        assertThat(storageFileCount()).isEqualTo(storageFilesAfterFirstUpload);
    }

    @Test
    void listMediaReturnsOnlyCurrentUserFiles() throws Exception {
        User user1 = createUser("user1@test.local", PASSWORD);
        User user2 = createUser("user2@test.local", PASSWORD);
        String token1 = loginAndGetAccessToken(user1.getEmail(), PASSWORD);
        String token2 = loginAndGetAccessToken(user2.getEmail(), PASSWORD);
        MediaFileDto user1First = upload(token1, "one.jpg", "image/jpeg", "one".getBytes());
        MediaFileDto user1Second = upload(token1, "two.png", "image/png", "two".getBytes());
        upload(token2, "other.jpg", "image/jpeg", "other".getBytes());

        MvcResult result = perform(get("/api/v1/media")
                        .param("page", "0")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token1)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        List<Long> ids = content.findValues("id").stream().map(JsonNode::asLong).toList();
        assertThat(ids).containsExactlyInAnyOrder(user1First.getId(), user1Second.getId());
    }

    @Test
    void metadataReturnsCurrentUserFile() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        byte[] bytes = "metadata".getBytes();
        MediaFileDto uploaded = upload(token, "metadata.jpg", "image/jpeg", bytes);

        MvcResult result = perform(get("/api/v1/media/{id}", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        MediaFileDto response = objectMapper.readValue(result.getResponse().getContentAsString(), MediaFileDto.class);
        assertThat(response.getId()).isEqualTo(uploaded.getId());
        assertThat(response.getOriginalFilename()).isEqualTo("metadata.jpg");
        assertThat(response.getChecksum()).isEqualTo(sha256(bytes));
    }

    @Test
    void downloadReturnsOriginalBytesAndContentType() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        byte[] bytes = "download-bytes".getBytes();
        MediaFileDto uploaded = upload(token, "download.jpg", "image/jpeg", bytes);

        perform(get("/api/v1/media/{id}/download", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"download.jpg\""))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void checksumsReturnsOnlyCurrentUserChecksums() throws Exception {
        User user1 = createUser("user1@test.local", PASSWORD);
        User user2 = createUser("user2@test.local", PASSWORD);
        String token1 = loginAndGetAccessToken(user1.getEmail(), PASSWORD);
        String token2 = loginAndGetAccessToken(user2.getEmail(), PASSWORD);
        byte[] currentUserBytes = "current-user".getBytes();
        byte[] otherUserBytes = "other-user".getBytes();
        upload(token1, "current.jpg", "image/jpeg", currentUserBytes);
        upload(token2, "other.jpg", "image/jpeg", otherUserBytes);

        MvcResult result = perform(get("/api/v1/media/checksums")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token1)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response).hasSize(1);
        assertThat(response.get(0).get("originalFilename").asText()).isEqualTo("current.jpg");
        assertThat(response.get(0).get("checksum").asText()).isEqualTo(sha256(currentUserBytes));
        assertThat(response.toString()).doesNotContain(sha256(otherUserBytes));
        assertThat(response.toString()).doesNotContain("other.jpg");
    }

    @Test
    void deleteRemovesDatabaseRowAndPhysicalFileWithoutBody() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        MediaFileDto uploaded = upload(token, "delete.jpg", "image/jpeg", "delete".getBytes());
        Path storedFile = Path.of(findMedia(uploaded.getId()).getStoragePath());

        MvcResult result = perform(delete("/api/v1/media/{id}", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(mediaFileRepository.findById(uploaded.getId())).isEmpty();
        assertThat(Files.exists(storedFile)).isFalse();
    }

    @Test
    void downloadAfterDeleteReturnsNotFound() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        MediaFileDto uploaded = upload(token, "deleted.jpg", "image/jpeg", "deleted".getBytes());

        perform(delete("/api/v1/media/{id}", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNoContent());

        perform(get("/api/v1/media/{id}/download", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteMissingReturnsNotFound() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);

        perform(delete("/api/v1/media/{id}", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUserCannotAccessOrDeleteFile() throws Exception {
        User user1 = createUser("user1@test.local", PASSWORD);
        User user2 = createUser("user2@test.local", PASSWORD);
        String token1 = loginAndGetAccessToken(user1.getEmail(), PASSWORD);
        String token2 = loginAndGetAccessToken(user2.getEmail(), PASSWORD);
        MediaFileDto uploaded = upload(token1, "private.jpg", "image/jpeg", "private".getBytes());
        Path storedFile = Path.of(findMedia(uploaded.getId()).getStoragePath());

        perform(get("/api/v1/media/{id}", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token2)))
                .andExpect(status().isNotFound());

        perform(get("/api/v1/media/{id}/download", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token2)))
                .andExpect(status().isNotFound());

        perform(delete("/api/v1/media/{id}", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token2)))
                .andExpect(status().isNotFound());

        assertThat(mediaFileRepository.findById(uploaded.getId())).isPresent();
        assertThat(Files.exists(storedFile)).isTrue();
    }

    @Test
    void mediaEndpointWithoutTokenReturnsUnauthorized() throws Exception {
        perform(get("/api/v1/media"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mediaEndpointWithInvalidTokenReturnsUnauthorized() throws Exception {
        perform(get("/api/v1/media")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadWithInvalidTokenDoesNotCreatePhysicalFile() throws Exception {
        perform(multipart("/api/v1/media")
                        .file(filePart("invalid.jpg", "image/jpeg", "invalid".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());

        assertThat(storageFileCount()).isZero();
        assertThat(mediaFileRepository.findAll()).isEmpty();
    }

    @Test
    void downloadReturnsNotFoundWhenPhysicalFileIsMissing() throws Exception {
        createUser("user1@test.local", PASSWORD);
        String token = loginAndGetAccessToken("user1@test.local", PASSWORD);
        MediaFileDto uploaded = upload(token, "missing-on-disk.jpg", "image/jpeg", "missing".getBytes());
        deletePhysicalFile(uploaded.getId());

        perform(get("/api/v1/media/{id}/download", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNotFound());

        assertThat(mediaFileRepository.findById(uploaded.getId())).isPresent();
    }
}
