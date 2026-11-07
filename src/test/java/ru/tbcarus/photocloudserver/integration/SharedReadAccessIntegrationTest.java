package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Share;
import ru.tbcarus.photocloudserver.model.ShareKind;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.ShareRepository;
import ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailFixtures;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// USER-доступы создаются напрямую через репозиторий: API выдачи появится в 8.2.
// Дерево владельца: ROOT/{root.txt, Shared/{a.txt, photo.png, Nested/{nested.txt}}, Sibling/{sibling.txt}}.
class SharedReadAccessIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final byte[] A_BYTES = "shared file content".getBytes(StandardCharsets.UTF_8);

    @Autowired
    ShareRepository shareRepository;

    private User owner;
    private User recipient;
    private String ownerToken;
    private String recipientToken;
    private Long rootId;
    private Long sharedId;
    private Long nestedId;
    private Long siblingId;
    private Long rootFileId;
    private Long aFileId;
    private Long photoId;
    private Long nestedFileId;
    private Long siblingFileId;

    @BeforeEach
    void createTree() throws Exception {
        owner = createUser("owner@test.local", PASSWORD);
        recipient = createUser("recipient@test.local", PASSWORD);
        ownerToken = loginAndGetAccessToken("owner@test.local", PASSWORD);
        recipientToken = loginAndGetAccessToken("recipient@test.local", PASSWORD);

        rootId = getRootId(ownerToken);
        sharedId = createFolder(ownerToken, rootId, "Shared").get("id").asLong();
        nestedId = createFolder(ownerToken, sharedId, "Nested").get("id").asLong();
        siblingId = createFolder(ownerToken, rootId, "Sibling").get("id").asLong();
        rootFileId = upload(ownerToken, "root.txt", "text/plain", bytes("root"), rootId).getId();
        aFileId = upload(ownerToken, "a.txt", "text/plain", A_BYTES, sharedId).getId();
        photoId = upload(ownerToken, "photo.png", "image/png", ThumbnailFixtures.png(8, 8), sharedId).getId();
        nestedFileId = upload(ownerToken, "nested.txt", "text/plain", bytes("nested"), nestedId).getId();
        siblingFileId = upload(ownerToken, "sibling.txt", "text/plain", bytes("sibling"), siblingId).getId();
    }

    @Test
    void ownerReadsOwnObjectsAsBefore() throws Exception {
        expectChildren(ownerToken, rootId, "Shared", "Sibling");
        expectFolderFiles(ownerToken, sharedId, "a.txt", "photo.png");
        expectReadable(ownerToken, rootFileId);
        expectReadable(ownerToken, nestedFileId);
        perform(get("/api/v1/files").header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void directlySharedFileIsReadableButItsFolderAndNeighboursAreNot() throws Exception {
        share(file(aFileId), null);

        perform(get("/api/v1/files/{id}", aFileId).header(HttpHeaders.AUTHORIZATION, authHeader(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("a.txt"))
                .andExpect(jsonPath("$.folderId").value(sharedId));
        perform(get("/api/v1/files/{id}/download", aFileId).header(HttpHeaders.AUTHORIZATION, authHeader(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(A_BYTES));
        // Range обслуживается тем же механизмом, что у владельца.
        perform(get("/api/v1/files/{id}/download", aFileId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(recipientToken))
                .header(HttpHeaders.RANGE, "bytes=7-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 7-%d/%d".formatted(A_BYTES.length - 1, A_BYTES.length)))
                .andExpect(content().bytes(Arrays.copyOfRange(A_BYTES, 7, A_BYTES.length)));

        expectFolderHidden(recipientToken, sharedId);
        expectHidden(recipientToken, photoId);
        expectHidden(recipientToken, nestedFileId);
    }

    @Test
    void sharedFolderOpensWholeSubtreeButNotParentsOrNeighbours() throws Exception {
        share(folder(sharedId), null);

        expectChildren(recipientToken, sharedId, "Nested");
        expectChildren(recipientToken, nestedId);
        expectFolderFiles(recipientToken, sharedId, "a.txt", "photo.png");
        expectFolderFiles(recipientToken, nestedId, "nested.txt");
        expectReadable(recipientToken, aFileId);
        expectReadable(recipientToken, nestedFileId);
        perform(get("/api/v1/files/{id}/thumbnail", photoId).header(HttpHeaders.AUTHORIZATION, authHeader(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));

        // Будущие объекты поддерева доступны сразу.
        Long laterId = upload(ownerToken, "later.txt", "text/plain", bytes("later"), nestedId).getId();
        expectReadable(recipientToken, laterId);

        expectFolderHidden(recipientToken, rootId);
        expectFolderHidden(recipientToken, siblingId);
        expectHidden(recipientToken, rootFileId);
        expectHidden(recipientToken, siblingFileId);
        // Глобальные списки остаются собственными: файлы владельца в них не попадают.
        perform(get("/api/v1/files").header(HttpHeaders.AUTHORIZATION, authHeader(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        perform(get("/api/v1/files/checksums").header(HttpHeaders.AUTHORIZATION, authHeader(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void backupSourceIsReturnedToOwnerOnly() throws Exception {
        // Корень источника лежит в AUTO_UPLOADS, который не предоставляется: маркер ставится напрямую, чтобы проверить ответ.
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        jdbcTemplate.update("UPDATE folder SET backup_source = ?::uuid WHERE id = ?", uuid, nestedId);
        share(folder(sharedId), null);

        perform(get("/api/v1/folders/{id}/children", sharedId).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].backupSource").value(uuid));
        perform(get("/api/v1/folders/{id}/children", sharedId).header(HttpHeaders.AUTHORIZATION, authHeader(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(nestedId))
                .andExpect(jsonPath("$[0].backupSource").doesNotExist());
    }

    @Test
    void expiredShareDoesNotWorkAndPermanentDoes() throws Exception {
        share(folder(sharedId), Instant.now().minus(1, ChronoUnit.MINUTES));
        share(folder(siblingId), null);

        expectFolderHidden(recipientToken, sharedId);
        expectHidden(recipientToken, aFileId);
        expectReadable(recipientToken, siblingFileId);
    }

    @Test
    void accessEndsExactlyAtExpiresAt() {
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        share(folder(sharedId), expiresAt);

        assertThat(shareRepository.existsActiveUserShareForFolder(nestedId, recipient.getId(), expiresAt.minusNanos(1000)))
                .isTrue();
        assertThat(shareRepository.existsActiveUserShareForFolder(nestedId, recipient.getId(), expiresAt)).isFalse();
        assertThat(shareRepository.existsActiveUserShareForFile(nestedFileId, recipient.getId(), expiresAt)).isFalse();
    }

    @Test
    void overlappingSharesAreIndependent() throws Exception {
        share(folder(sharedId), Instant.now().minus(1, ChronoUnit.MINUTES));
        Share nestedShare = share(folder(nestedId), null);
        share(file(aFileId), null);

        // Истёкший доступ к Shared не отменяет действующие доступы к вложенной папке и файлу.
        expectFolderHidden(recipientToken, sharedId);
        expectHidden(recipientToken, photoId);
        expectReadable(recipientToken, aFileId);
        expectReadable(recipientToken, nestedFileId);
        expectChildren(recipientToken, nestedId);

        shareRepository.delete(nestedShare);

        expectHidden(recipientToken, nestedFileId);
        expectReadable(recipientToken, aFileId);
    }

    @Test
    void movesChangeFileAccessButMovedSharedFolderKeepsIt() throws Exception {
        share(folder(nestedId), null);

        moveFile(siblingFileId, nestedId);
        expectReadable(recipientToken, siblingFileId);

        moveFile(nestedFileId, siblingId);
        expectHidden(recipientToken, nestedFileId);

        perform(post("/api/v1/folders/{id}/move", nestedId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetParentId", siblingId))))
                .andExpect(status().isOk());
        expectChildren(recipientToken, nestedId);
        expectReadable(recipientToken, siblingFileId);
        // Перемещённая папка не открывает нового родителя.
        expectFolderHidden(recipientToken, siblingId);
    }

    @Test
    void recipientCannotModifySharedObjects() throws Exception {
        share(folder(sharedId), null);
        Long recipientRootId = getRootId(recipientToken);
        Long ownFileId = upload(recipientToken, "own.txt", "text/plain", bytes("own"), recipientRootId).getId();
        String checksum = sha256(A_BYTES);

        List<MockHttpServletRequestBuilder> writes = new ArrayList<>(List.of(
                patch("/api/v1/files/{id}", aFileId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("originalName", "renamed.txt"))),
                post("/api/v1/files/{id}/move", aFileId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetFolderId", recipientRootId))),
                post("/api/v1/files/{id}/copy", aFileId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetFolderId", recipientRootId))),
                delete("/api/v1/files/{id}", aFileId),
                post("/api/v1/files/{id}/move", ownFileId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetFolderId", sharedId))),
                patch("/api/v1/folders/{id}", nestedId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renamed"))),
                post("/api/v1/folders/{id}/move", nestedId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetParentId", recipientRootId))),
                delete("/api/v1/folders/{id}", nestedId),
                delete("/api/v1/folders/{id}/backup", sharedId),
                post("/api/v1/folders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("parentId", sharedId, "name", "Intruder"))),
                multipart("/api/v1/files").file(filePart("intruder.txt", "text/plain", bytes("x")))
                        .param("folderId", sharedId.toString()),
                post("/api/v1/files/checksums/exists").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("folderId", sharedId, "checksums", List.of(checksum))))));

        for (MockHttpServletRequestBuilder write : writes) {
            perform(write.header(HttpHeaders.AUTHORIZATION, authHeader(recipientToken)))
                    .andExpect(status().isNotFound());
        }

        FileItem unchanged = findFileItem(aFileId);
        assertThat(unchanged.getOriginalName()).isEqualTo("a.txt");
        assertThat(unchanged.getFolder().getId()).isEqualTo(sharedId);
        assertThat(findFileItem(ownFileId).getFolder().getId()).isEqualTo(recipientRootId);
        expectChildren(ownerToken, sharedId, "Nested");
        expectFolderFiles(ownerToken, sharedId, "a.txt", "photo.png");
        assertThat(fileItemRepository.count()).isEqualTo(6);
    }

    @Test
    void blockedOrDisabledOwnerClosesGrantedAccess() throws Exception {
        share(folder(sharedId), null);

        jdbcTemplate.update("UPDATE users SET banned = true WHERE id = ?", owner.getId());
        expectFolderHidden(recipientToken, sharedId);
        expectHidden(recipientToken, aFileId);

        jdbcTemplate.update("UPDATE users SET banned = false, enabled = false WHERE id = ?", owner.getId());
        expectHidden(recipientToken, aFileId);

        jdbcTemplate.update("UPDATE users SET enabled = true WHERE id = ?", owner.getId());
        expectReadable(recipientToken, aFileId);
    }

    @Test
    void systemRootAndRecordsWithForeignOwnerGrantNothing() throws Exception {
        User stranger = createUser("stranger@test.local", PASSWORD);
        String insert = "INSERT INTO share (owner_id, folder_id, kind, recipient_id) VALUES (?, ?, 'USER', ?)";
        jdbcTemplate.update(insert, owner.getId(), rootId, recipient.getId());
        // owner записи не совпадает с владельцем папки: такая запись не доказывает права.
        jdbcTemplate.update(insert, stranger.getId(), sharedId, recipient.getId());

        expectFolderHidden(recipientToken, rootId);
        expectFolderHidden(recipientToken, sharedId);
        expectHidden(recipientToken, rootFileId);
        expectHidden(recipientToken, aFileId);
    }

    @Test
    void deletingObjectsOrUsersRemovesShares() throws Exception {
        share(file(aFileId), null);
        share(folder(nestedId), null);
        share(folder(siblingId), null);
        assertThat(shareRepository.count()).isEqualTo(3);

        perform(delete("/api/v1/files/{id}", aFileId).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());
        assertThat(shareRepository.count()).isEqualTo(2);

        // Вложенная Nested удаляется вместе с Shared.
        perform(delete("/api/v1/folders/{id}", sharedId).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());
        assertThat(shareRepository.count()).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", recipient.getId());
        assertThat(shareRepository.count()).isZero();
    }

    @Test
    void shareInvariantsHoldInDatabase() {
        User other = createUser("other@test.local", PASSWORD);
        Long o = owner.getId();
        Long r = recipient.getId();
        String insert = """
                INSERT INTO share (owner_id, folder_id, file_item_id, kind, recipient_id, token_hash, encrypted_token)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        jdbcTemplate.update(insert, o, sharedId, null, "USER", r, null, null);
        jdbcTemplate.update(insert, o, sharedId, null, "USER", other.getId(), null, null);
        jdbcTemplate.update(insert, o, null, aFileId, "USER", r, null, null);
        // Несколько независимых ссылок на один объект.
        jdbcTemplate.update(insert, o, sharedId, null, "LINK", null, "h1", "e1");
        jdbcTemplate.update(insert, o, sharedId, null, "LINK", null, "h2", "e2");

        List<Object[]> invalid = List.of(
                new Object[]{o, sharedId, aFileId, "USER", other.getId(), null, null},
                new Object[]{o, null, null, "USER", r, null, null},
                new Object[]{o, nestedId, null, "USER", null, null, null},
                new Object[]{o, nestedId, null, "USER", r, "h3", null},
                new Object[]{o, nestedId, null, "LINK", null, "h3", null},
                new Object[]{o, nestedId, null, "LINK", r, "h3", "e3"},
                new Object[]{o, nestedId, null, "OTHER", r, null, null},
                new Object[]{o, nestedId, null, "USER", o, null, null},
                new Object[]{o, sharedId, null, "USER", r, null, null},
                new Object[]{o, null, aFileId, "USER", r, null, null},
                new Object[]{o, nestedId, null, "LINK", null, "h1", "e3"});
        for (Object[] row : invalid) {
            assertThatThrownBy(() -> jdbcTemplate.update(insert, row))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private Share share(Share.ShareBuilder target, Instant expiresAt) {
        return shareRepository.save(target
                .owner(owner)
                .kind(ShareKind.USER)
                .recipient(recipient)
                .expiresAt(expiresAt)
                .build());
    }

    private Share.ShareBuilder folder(Long id) {
        return Share.builder().folder(folderRepository.findById(id).orElseThrow());
    }

    private Share.ShareBuilder file(Long id) {
        return Share.builder().fileItem(fileItemRepository.findById(id).orElseThrow());
    }

    private void moveFile(Long fileId, Long targetFolderId) throws Exception {
        perform(post("/api/v1/files/{id}/move", fileId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetFolderId", targetFolderId))))
                .andExpect(status().isOk());
    }

    // Все три read-endpoint файла дают одинаковый результат.
    private void expectReadable(String token, Long fileId) throws Exception {
        for (String url : fileReadUrls()) {
            perform(get(url, fileId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                    .andExpect(status().isOk());
        }
    }

    private void expectHidden(String token, Long fileId) throws Exception {
        for (String url : fileReadUrls()) {
            perform(get(url, fileId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                    .andExpect(status().isNotFound());
        }
        perform(get("/api/v1/files/{id}/thumbnail", fileId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNotFound());
    }

    private List<String> fileReadUrls() {
        return List.of("/api/v1/files/{id}", "/api/v1/files/{id}/download");
    }

    private void expectFolderHidden(String token, Long folderId) throws Exception {
        perform(get("/api/v1/folders/{id}/children", folderId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNotFound());
        perform(get("/api/v1/files").param("folderId", folderId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isNotFound());
    }

    private void expectChildren(String token, Long folderId, String... names) throws Exception {
        String body = perform(get("/api/v1/folders/{id}/children", folderId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(names(objectMapper.readTree(body), "name")).containsExactlyInAnyOrder(names);
    }

    private void expectFolderFiles(String token, Long folderId, String... names) throws Exception {
        String body = perform(get("/api/v1/files").param("folderId", folderId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(names(objectMapper.readTree(body).get("items"), "originalFilename")).containsExactlyInAnyOrder(names);
    }

    private List<String> names(JsonNode array, String field) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.get(field).asText()));
        return result;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
