package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import ru.tbcarus.photocloudserver.model.Share;
import ru.tbcarus.photocloudserver.model.ShareKind;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.ShareRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// API управления USER-доступами. Правила самого ReadAccess проверяет SharedReadAccessIntegrationTest;
// здесь чтение проверяется только как следствие выдачи, отзыва и истечения.
// Дерево владельца: ROOT/{Shared/{a.txt, b.txt}, Other/{c.txt}}.
class ShareApiIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";

    @Autowired
    ShareRepository shareRepository;

    private User owner;
    private User alice;
    private String ownerToken;
    private String aliceToken;
    private String bobToken;
    private Long rootId;
    private Long sharedId;
    private Long otherId;
    private Long aFileId;
    private Long cFileId;

    @BeforeEach
    void createTree() throws Exception {
        owner = createUser("owner@test.local", PASSWORD);
        alice = createUser("alice@test.local", PASSWORD);
        createUser("bob@test.local", PASSWORD);
        createUser("carol@test.local", PASSWORD, false, false);
        createUser("dave@test.local", PASSWORD, true, true);
        ownerToken = loginAndGetAccessToken("owner@test.local", PASSWORD);
        aliceToken = loginAndGetAccessToken("alice@test.local", PASSWORD);
        bobToken = loginAndGetAccessToken("bob@test.local", PASSWORD);

        rootId = getRootId(ownerToken);
        sharedId = createFolder(ownerToken, rootId, "Shared").get("id").asLong();
        otherId = createFolder(ownerToken, rootId, "Other").get("id").asLong();
        aFileId = upload(ownerToken, "a.txt", "text/plain", bytes("a"), sharedId).getId();
        upload(ownerToken, "b.txt", "text/plain", bytes("b"), sharedId);
        cFileId = upload(ownerToken, "c.txt", "text/plain", bytes("c"), otherId).getId();
    }

    @Test
    void grantsToSeveralRecipientsWithoutCopies() throws Exception {
        grant(ownerToken, folder(sharedId), List.of("alice@test.local", "BOB@test.local", "Alice@Test.Local"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].recipient.email").value("alice@test.local"))
                .andExpect(jsonPath("$[1].recipient.email").value("bob@test.local"))
                .andExpect(jsonPath("$[0].resourceType").value("FOLDER"))
                .andExpect(jsonPath("$[0].resourceId").value(sharedId))
                .andExpect(jsonPath("$[0].resourceName").value("Shared"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].expiresAt").doesNotExist())
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());

        assertThat(shareRepository.count()).isEqualTo(2);
        assertThat(fileItemRepository.count()).isEqualTo(3);
        expectFileReadable(aliceToken, aFileId, true);
        expectChildrenReadable(bobToken, sharedId, true);
    }

    @Test
    void invalidInputRejectsWholeOperation() throws Exception {
        grant(ownerToken, folder(sharedId),
                List.of("alice@test.local", "nobody@test.local", "carol@test.local", "dave@test.local", "owner@test.local"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHARE_RECIPIENT_UNAVAILABLE"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(4))
                .andExpect(jsonPath("$.fieldErrors['nobody@test.local']").value("Recipient is not available"))
                .andExpect(jsonPath("$.fieldErrors['dave@test.local']").value("Recipient is not available"))
                .andExpect(jsonPath("$.fieldErrors['owner@test.local']").value("Cannot share with yourself"));
        grant(ownerToken, folder(sharedId), List.of(), null).andExpect(status().isBadRequest());
        grant(ownerToken, folder(sharedId), List.of("alice@test.local"), Instant.now().minusSeconds(60).toString())
                .andExpect(status().isBadRequest());
        Map<String, Object> both = folder(sharedId);
        both.put("fileId", aFileId);
        grant(ownerToken, both, List.of("alice@test.local"), null).andExpect(status().isBadRequest());
        grant(ownerToken, new HashMap<>(), List.of("alice@test.local"), null).andExpect(status().isBadRequest());

        assertThat(shareRepository.count()).isZero();
        expectFileReadable(aliceToken, aFileId, false);
    }

    @Test
    void repeatedGrantUpdatesExpiryAndExtendsExpiredShare() throws Exception {
        Share expired = insertShare(otherId, null, alice, Instant.now().minus(1, ChronoUnit.HOURS));
        expectFileReadable(aliceToken, cFileId, false);

        // Смещение в запросе: в ответе тот же момент в UTC.
        grant(ownerToken, folder(otherId), List.of("alice@test.local"), "2031-05-01T12:00:00+03:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(expired.getId()))
                .andExpect(jsonPath("$[0].expiresAt").value("2031-05-01T09:00:00Z"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        grant(ownerToken, folder(otherId), List.of("alice@test.local"), null)
                .andExpect(jsonPath("$[0].id").value(expired.getId()))
                .andExpect(jsonPath("$[0].expiresAt").doesNotExist());

        assertThat(shareRepository.count()).isEqualTo(1);
        expectFileReadable(aliceToken, cFileId, true);
    }

    @Test
    void ownerListsFiltersAndChangesExpiry() throws Exception {
        grant(ownerToken, file(aFileId), List.of("alice@test.local"), "2031-01-01T00:00:00Z").andExpect(status().isOk());
        grant(ownerToken, folder(otherId), List.of("bob@test.local"), null).andExpect(status().isOk());
        Share expired = insertShare(otherId, null, alice, Instant.now().minus(1, ChronoUnit.MINUTES));

        perform(get("/api/v1/shares").header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        perform(get("/api/v1/shares").param("fileId", aFileId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].resourceType").value("FILE"))
                .andExpect(jsonPath("$.items[0].resourceName").value("a.txt"));
        perform(get("/api/v1/shares").param("folderId", otherId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[?(@.id == %d)].status".formatted(expired.getId())).value("EXPIRED"));

        expectFileReadable(aliceToken, cFileId, false);
        // Явный null снимает срок, отсутствующее поле — ошибка, прошлое — ошибка.
        updateExpiry(ownerToken, expired.getId(), "{\"expiresAt\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
        expectFileReadable(aliceToken, cFileId, true);
        updateExpiry(ownerToken, expired.getId(), "{}").andExpect(status().isBadRequest());
        updateExpiry(ownerToken, expired.getId(), "{\"expiresAt\":\"2020-01-01T00:00:00Z\"}").andExpect(status().isBadRequest());
        updateExpiry(ownerToken, expired.getId(), "{\"expiresAt\":\"2032-02-02T00:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value("2032-02-02T00:00:00Z"));

        // Наступление срока закрывает чтение сразу, без фоновой обработки; запись остаётся владельцу.
        jdbcTemplate.update("UPDATE share SET expires_at = now() - interval '1 second' WHERE id = ?", expired.getId());
        expectFileReadable(aliceToken, cFileId, false);
        perform(get("/api/v1/shares").param("folderId", otherId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void revokesOneRecipientAndThenAllOfResource() throws Exception {
        List<Long> ids = ids(grant(ownerToken, folder(sharedId), List.of("alice@test.local", "bob@test.local"), null));
        grant(ownerToken, folder(otherId), List.of("alice@test.local"), null);

        perform(delete("/api/v1/shares/{id}", ids.get(0)).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());
        expectChildrenReadable(aliceToken, sharedId, false);
        expectChildrenReadable(bobToken, sharedId, true);
        perform(delete("/api/v1/shares/{id}", ids.get(0)).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNotFound());

        perform(delete("/api/v1/shares/users").param("folderId", sharedId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());
        expectChildrenReadable(bobToken, sharedId, false);
        // Доступ к другому ресурсу не затронут.
        expectChildrenReadable(aliceToken, otherId, true);
        assertThat(shareRepository.count()).isEqualTo(1);
    }

    @Test
    void sharedWithMeListsOnlyDirectActiveShares() throws Exception {
        grant(ownerToken, folder(sharedId), List.of("alice@test.local"), null);
        grant(ownerToken, file(aFileId), List.of("alice@test.local"), "2031-01-01T00:00:00Z");
        grant(ownerToken, file(cFileId), List.of("bob@test.local"), null);
        insertShare(otherId, null, alice, Instant.now().minus(1, ChronoUnit.MINUTES));
        // Доступ от владельца, заблокированного после выдачи, не показывается.
        User other = createUser("other-owner@test.local", PASSWORD);
        String otherToken = loginAndGetAccessToken("other-owner@test.local", PASSWORD);
        Long otherFolderId = createFolder(otherToken, getRootId(otherToken), "Blocked").get("id").asLong();
        grant(otherToken, folder(otherFolderId), List.of("alice@test.local"), null).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE users SET banned = true WHERE id = ?", other.getId());

        String body = perform(get("/api/v1/shared-with-me").header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].resourceType").value("FILE"))
                .andExpect(jsonPath("$.items[0].resourceId").value(aFileId))
                .andExpect(jsonPath("$.items[0].name").value("a.txt"))
                .andExpect(jsonPath("$.items[0].file.mimeType").value("text/plain"))
                .andExpect(jsonPath("$.items[0].file.size").value(1))
                .andExpect(jsonPath("$.items[0].expiresAt").value("2031-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.items[0].owner.email").value("owner@test.local"))
                .andExpect(jsonPath("$.items[1].resourceType").value("FOLDER"))
                .andExpect(jsonPath("$.items[1].resourceId").value(sharedId))
                .andExpect(jsonPath("$.items[1].file").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("backupSource", "tokenHash", "encryptedToken", "b.txt");

        perform(get("/api/v1/shared-with-me").header(HttpHeaders.AUTHORIZATION, authHeader(bobToken)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].resourceId").value(cFileId));
        perform(get("/api/v1/shared-with-me").param("size", "1")
                .header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void recipientRefusesOnlyOwnShareAndKeepsIndependentOnes() throws Exception {
        Long aliceFolderShare = ids(grant(ownerToken, folder(sharedId), List.of("alice@test.local", "bob@test.local"), null)).get(0);
        Long bobFolderShare = shareRepository.findAll().stream()
                .filter(share -> !share.getId().equals(aliceFolderShare)).findFirst().orElseThrow().getId();
        grant(ownerToken, file(aFileId), List.of("alice@test.local"), null);

        perform(delete("/api/v1/shared-with-me/{id}", bobFolderShare).header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNotFound());
        perform(delete("/api/v1/shared-with-me/{id}", aliceFolderShare).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNotFound());
        perform(delete("/api/v1/shared-with-me/{id}", aliceFolderShare).header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNoContent());

        // Файл остаётся доступен по отдельному доступу, папка — нет; доступ Bob и сам ресурс не изменились.
        expectChildrenReadable(aliceToken, sharedId, false);
        expectFileReadable(aliceToken, aFileId, true);
        expectChildrenReadable(bobToken, sharedId, true);
        expectChildrenReadable(ownerToken, sharedId, true);
        assertThat(fileItemRepository.count()).isEqualTo(3);
    }

    @Test
    void onlyOwnerSharesAllowedFoldersAndFiles() throws Exception {
        grant(ownerToken, folder(sharedId), List.of("alice@test.local"), null);

        // Получатель не может передать доступ дальше; чужой и отсутствующий ресурс — 404.
        grant(aliceToken, folder(sharedId), List.of("bob@test.local"), null).andExpect(status().isNotFound());
        grant(aliceToken, file(aFileId), List.of("bob@test.local"), null).andExpect(status().isNotFound());
        grant(ownerToken, folder(999_999L), List.of("alice@test.local"), null).andExpect(status().isNotFound());

        grant(ownerToken, folder(rootId), List.of("alice@test.local"), null).andExpect(status().isBadRequest());
        JsonNode source = registerBackupSource(ownerToken);
        grant(ownerToken, folder(source.get("parentId").asLong()), List.of("alice@test.local"), null)
                .andExpect(status().isBadRequest());
        grant(ownerToken, folder(source.get("id").asLong()), List.of("alice@test.local"), null).andExpect(status().isOk());
        Long cameraId = objectMapper.readTree(perform(post("/api/v1/folders/camera")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        grant(ownerToken, folder(cameraId), List.of("alice@test.local"), null).andExpect(status().isOk());

        assertThat(shareRepository.count()).isEqualTo(3);
    }

    @Test
    void foreignSharesCannotBeSeenOrChanged() throws Exception {
        Long shareId = ids(grant(ownerToken, folder(sharedId), List.of("alice@test.local"), null)).get(0);

        // Получатель не управляет доступом как владелец; Bob не видит чужой доступ ни в одном списке.
        updateExpiry(aliceToken, shareId, "{\"expiresAt\":null}").andExpect(status().isNotFound());
        perform(delete("/api/v1/shares/{id}", shareId).header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNotFound());
        perform(delete("/api/v1/shares/users").param("folderId", sharedId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNotFound());
        perform(get("/api/v1/shares").param("folderId", sharedId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNotFound());
        perform(get("/api/v1/shares").header(HttpHeaders.AUTHORIZATION, authHeader(bobToken)))
                .andExpect(jsonPath("$.totalElements").value(0));
        perform(delete("/api/v1/shared-with-me/{id}", shareId).header(HttpHeaders.AUTHORIZATION, authHeader(bobToken)))
                .andExpect(status().isNotFound());

        assertThat(shareRepository.findById(shareId)).isPresent();
        expectChildrenReadable(aliceToken, sharedId, true);
    }

    private ResultActions grant(String token, Map<String, Object> resource, List<String> emails, String expiresAt) throws Exception {
        Map<String, Object> body = new HashMap<>(resource);
        body.put("emails", emails);
        body.put("expiresAt", expiresAt);
        return perform(post("/api/v1/shares/users")
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions updateExpiry(String token, Long shareId, String body) throws Exception {
        return perform(patch("/api/v1/shares/{id}", shareId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<Long> ids(ResultActions grantResult) throws Exception {
        JsonNode array = objectMapper.readTree(grantResult.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> result = new ArrayList<>();
        array.forEach(node -> result.add(node.get("id").asLong()));
        return result;
    }

    // Истёкший доступ API создать не позволяет: такая запись появляется только со временем.
    private Share insertShare(Long folderId, Long fileId, User recipient, Instant expiresAt) {
        return shareRepository.save(Share.builder()
                .owner(owner)
                .folder(folderId == null ? null : folderRepository.findById(folderId).orElseThrow())
                .fileItem(fileId == null ? null : fileItemRepository.findById(fileId).orElseThrow())
                .kind(ShareKind.USER)
                .recipient(recipient)
                .expiresAt(expiresAt)
                .build());
    }

    private JsonNode registerBackupSource(String token) throws Exception {
        return objectMapper.readTree(perform(post("/api/v1/folders/backup")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Documents\",\"sourceUuid\":\"550e8400-e29b-41d4-a716-446655440000\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void expectFileReadable(String token, Long fileId, boolean readable) throws Exception {
        perform(get("/api/v1/files/{id}/download", fileId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(readable ? status().isOk() : status().isNotFound());
    }

    private void expectChildrenReadable(String token, Long folderId, boolean readable) throws Exception {
        perform(get("/api/v1/folders/{id}/children", folderId).header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(readable ? status().isOk() : status().isNotFound());
    }

    private static Map<String, Object> folder(Long id) {
        Map<String, Object> resource = new HashMap<>();
        resource.put("folderId", id);
        return resource;
    }

    private static Map<String, Object> file(Long id) {
        Map<String, Object> resource = new HashMap<>();
        resource.put("fileId", id);
        return resource;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
