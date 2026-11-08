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
import ru.tbcarus.photocloudserver.repository.NotificationRepository;
import ru.tbcarus.photocloudserver.repository.ShareRepository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

// Уведомления о новом USER-доступе и API получателя. Правила самой выдачи проверяет ShareApiIntegrationTest.
// Дерево владельца: ROOT/{Shared/{a.txt}, Other/{c.txt}}.
class NotificationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final String URL = "/api/v1/notifications";

    @Autowired
    ShareRepository shareRepository;

    @Autowired
    NotificationRepository notificationRepository;

    private User owner;
    private User alice;
    private User bob;
    private String ownerToken;
    private String aliceToken;
    private String bobToken;
    private Long sharedId;
    private Long otherId;
    private Long aFileId;
    private Long cFileId;

    @BeforeEach
    void createTree() throws Exception {
        owner = createUser("owner@test.local", PASSWORD);
        alice = createUser("alice@test.local", PASSWORD);
        bob = createUser("bob@test.local", PASSWORD);
        createUser("carol@test.local", PASSWORD);
        ownerToken = loginAndGetAccessToken("owner@test.local", PASSWORD);
        aliceToken = loginAndGetAccessToken("alice@test.local", PASSWORD);
        bobToken = loginAndGetAccessToken("bob@test.local", PASSWORD);

        Long rootId = getRootId(ownerToken);
        sharedId = createFolder(ownerToken, rootId, "Shared").get("id").asLong();
        otherId = createFolder(ownerToken, rootId, "Other").get("id").asLong();
        aFileId = upload(ownerToken, "a.txt", "text/plain", bytes("a"), sharedId).getId();
        cFileId = upload(ownerToken, "c.txt", "text/plain", bytes("c"), otherId).getId();
    }

    @Test
    void newUserShareNotifiesRecipientWithServerText() throws Exception {
        grant(file(aFileId), List.of("alice@test.local")).andExpect(status().isOk());
        grant(folder(otherId), List.of("alice@test.local")).andExpect(status().isOk());

        String body = list(aliceToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].type").value("SHARE_GRANTED"))
                .andExpect(jsonPath("$.items[0].title").value("Предоставлен доступ"))
                .andExpect(jsonPath("$.items[0].body").value("Вам предоставлен доступ к папке „Other“"))
                .andExpect(jsonPath("$.items[0].targetType").value("FOLDER"))
                .andExpect(jsonPath("$.items[0].targetId").value(otherId))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.items[1].body").value("Вам предоставлен доступ к файлу „a.txt“"))
                .andExpect(jsonPath("$.items[1].targetType").value("FILE"))
                .andExpect(jsonPath("$.items[1].targetId").value(aFileId))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("recipient", "owner@test.local", "Bearer");

        // В БД — строковые имена enum, получатель — тот, кому выдан доступ.
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT type || '/' || recipient_id FROM notification", String.class))
                .containsExactly("SHARE_GRANTED/" + alice.getId());
        assertThat(jdbcTemplate.queryForList("SELECT target_type FROM notification ORDER BY id", String.class))
                .containsExactly("FILE", "FOLDER");
        list(ownerToken).andExpect(jsonPath("$.totalElements").value(0));
        list(bobToken).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void groupGrantNotifiesOnlyNewRecipients() throws Exception {
        grant(folder(sharedId), List.of("alice@test.local")).andExpect(status().isOk());

        grant(folder(sharedId), List.of("alice@test.local", "bob@test.local", "carol@test.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        assertThat(shareRepository.count()).isEqualTo(3);
        assertThat(notificationRepository.count()).isEqualTo(3);
        unreadCount(aliceToken).andExpect(jsonPath("$.count").value(1));
        unreadCount(bobToken).andExpect(jsonPath("$.count").value(1));
        unreadCount(loginAndGetAccessToken("carol@test.local", PASSWORD)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void onlyCreationOfUserShareNotifies() throws Exception {
        Long shareId = shareIds(grant(folder(sharedId), List.of("alice@test.local"))).get(0);
        // Повторная выдача, изменение срока, продление истёкшего доступа, LINK, отказ и отзыв — без уведомлений.
        grant(folder(sharedId), List.of("alice@test.local"), "2031-01-01T00:00:00Z").andExpect(status().isOk());
        perform(patch("/api/v1/shares/{id}", shareId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresAt\":null}"))
                .andExpect(status().isOk());
        insertExpiredShare(otherId, bob);
        grant(folder(otherId), List.of("bob@test.local")).andExpect(status().isOk());
        perform(post("/api/v1/shares/links")
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"folderId\":%d}".formatted(sharedId)))
                .andExpect(status().isOk());
        perform(delete("/api/v1/shared-with-me/{id}", shareId).header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNoContent());
        perform(delete("/api/v1/shares/users").param("folderId", otherId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());

        assertThat(notificationRepository.count()).isEqualTo(1);
        list(bobToken).andExpect(jsonPath("$.totalElements").value(0));

        // После отказа доступа нет: следующая выдача создаёт новый Share и новое уведомление.
        grant(folder(sharedId), List.of("alice@test.local")).andExpect(status().isOk());
        list(aliceToken).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void rejectedOrRolledBackGrantLeavesNoNotifications() throws Exception {
        grant(folder(sharedId), List.of("alice@test.local", "nobody@test.local"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHARE_RECIPIENT_UNAVAILABLE"));
        assertThat(shareRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();

        // Сбой записи уведомления второго получателя после записи первого: откатываются оба Share и уведомление.
        jdbcTemplate.execute("""
                CREATE FUNCTION test_fail_notification() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'test failure' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_notification BEFORE INSERT ON notification
                FOR EACH ROW WHEN (NEW.recipient_id = %d) EXECUTE FUNCTION test_fail_notification()
                """.formatted(bob.getId()));
        try {
            grant(folder(sharedId), List.of("alice@test.local", "bob@test.local"))
                    .andExpect(status().isInternalServerError());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_fail_notification ON notification");
            jdbcTemplate.execute("DROP FUNCTION test_fail_notification()");
        }

        assertThat(shareRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
        perform(get("/api/v1/folders/{id}/children", sharedId).header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listShowsOnlyOwnNotificationsNewestFirstWithPaging() throws Exception {
        grant(folder(sharedId), List.of("alice@test.local")).andExpect(status().isOk());
        grant(folder(otherId), List.of("alice@test.local")).andExpect(status().isOk());
        grant(file(cFileId), List.of("alice@test.local", "bob@test.local")).andExpect(status().isOk());
        List<Long> aliceIds = jdbcTemplate.queryForList(
                "SELECT id FROM notification WHERE recipient_id = ? ORDER BY id", Long.class, alice.getId());
        // Одинаковое время у двух последних: порядок определяет id.
        Timestamp same = Timestamp.from(Instant.parse("2030-01-01T00:00:00Z"));
        jdbcTemplate.update("UPDATE notification SET created_at = ? WHERE id IN (?, ?)", same, aliceIds.get(1), aliceIds.get(2));
        jdbcTemplate.update("UPDATE notification SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2029-01-01T00:00:00Z")), aliceIds.get(0));

        list(aliceToken, 0, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(aliceIds.get(2)))
                .andExpect(jsonPath("$.items[0].createdAt").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.items[1].id").value(aliceIds.get(1)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        list(aliceToken, 1, 2)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(aliceIds.get(0)))
                .andExpect(jsonPath("$.hasPrevious").value(true));
        list(bobToken)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].body").value("Вам предоставлен доступ к файлу „c.txt“"));

        // Нет уведомлений — пустая страница и счётчик 0; неверная пагинация — 400, как у остальных списков.
        list(ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
        unreadCount(ownerToken).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
        list(aliceToken, -1, 10).andExpect(status().isBadRequest());
        list(aliceToken, 0, 0).andExpect(status().isBadRequest());
    }

    @Test
    void markReadIsIdempotentAndForeignNotificationIsNotFound() throws Exception {
        grant(folder(sharedId), List.of("alice@test.local", "bob@test.local")).andExpect(status().isOk());
        grant(file(cFileId), List.of("alice@test.local")).andExpect(status().isOk());
        Long aliceFirst = notificationId(alice, 0);
        Long bobFirst = notificationId(bob, 0);
        unreadCount(aliceToken).andExpect(jsonPath("$.count").value(2));

        String readAt = objectMapper.readTree(markRead(aliceToken, aliceFirst)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(aliceFirst))
                        .andExpect(jsonPath("$.readAt").isNotEmpty())
                        .andReturn().getResponse().getContentAsString())
                .get("readAt").asText();
        unreadCount(aliceToken).andExpect(jsonPath("$.count").value(1));
        markRead(aliceToken, aliceFirst)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").value(readAt));
        unreadCount(aliceToken).andExpect(jsonPath("$.count").value(1));

        // Чужое и отсутствующее уведомление неразличимы; состояние чужого не меняется.
        markRead(aliceToken, bobFirst)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        markRead(aliceToken, 999_999L).andExpect(status().isNotFound());
        unreadCount(bobToken).andExpect(jsonPath("$.count").value(1));
        assertThat(notificationRepository.findById(bobFirst).orElseThrow().getReadAt()).isNull();
    }

    @Test
    void markAllReadKeepsEarlierReadAtAndOtherUsers() throws Exception {
        grant(folder(sharedId), List.of("alice@test.local", "bob@test.local")).andExpect(status().isOk());
        grant(folder(otherId), List.of("alice@test.local")).andExpect(status().isOk());
        grant(file(cFileId), List.of("alice@test.local")).andExpect(status().isOk());
        Long earlier = notificationId(alice, 0);
        Instant earlierReadAt = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE notification SET read_at = ? WHERE id = ?", Timestamp.from(earlierReadAt), earlier);
        unreadCount(aliceToken).andExpect(jsonPath("$.count").value(2));

        perform(patch(URL + "/read-all").header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNoContent());

        unreadCount(aliceToken).andExpect(jsonPath("$.count").value(0));
        Map<Long, Instant> readAts = aliceReadAts();
        assertThat(readAts).hasSize(3).doesNotContainValue(null);
        assertThat(readAts.get(earlier)).isEqualTo(earlierReadAt);
        unreadCount(bobToken).andExpect(jsonPath("$.count").value(1));

        // Повтор ничего не меняет.
        perform(patch(URL + "/read-all").header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNoContent());
        assertThat(aliceReadAts()).isEqualTo(readAts);
    }

    @Test
    void notificationSurvivesRenameRevokeAndDeletionWithoutGrantingAccess() throws Exception {
        Long folderShare = shareIds(grant(folder(sharedId), List.of("alice@test.local"))).get(0);
        grant(file(cFileId), List.of("alice@test.local")).andExpect(status().isOk());

        perform(patch("/api/v1/folders/{id}", sharedId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk());
        perform(delete("/api/v1/shares/{id}", folderShare).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());
        perform(delete("/api/v1/files/{id}", cFileId).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());
        assertThat(shareRepository.count()).isZero();

        list(aliceToken)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].targetType").value("FILE"))
                .andExpect(jsonPath("$.items[0].targetId").value(cFileId))
                .andExpect(jsonPath("$.items[1].targetId").value(sharedId))
                .andExpect(jsonPath("$.items[1].body").value("Вам предоставлен доступ к папке „Shared“"));
        // Указатель на объект не даёт права чтения.
        perform(get("/api/v1/folders/{id}/children", sharedId).header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNotFound());
        perform(get("/api/v1/files/{id}/download", cFileId).header(HttpHeaders.AUTHORIZATION, authHeader(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        perform(get(URL)).andExpect(status().isUnauthorized());
        perform(get(URL + "/unread-count")).andExpect(status().isUnauthorized());
        perform(patch(URL + "/{id}/read", 1)).andExpect(status().isUnauthorized());
        perform(patch(URL + "/read-all")).andExpect(status().isUnauthorized());
    }

    private ResultActions grant(Map<String, Object> resource, List<String> emails) throws Exception {
        return grant(resource, emails, null);
    }

    private ResultActions grant(Map<String, Object> resource, List<String> emails, String expiresAt) throws Exception {
        Map<String, Object> body = new HashMap<>(resource);
        body.put("emails", emails);
        body.put("expiresAt", expiresAt);
        return perform(post("/api/v1/shares/users")
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private List<Long> shareIds(ResultActions grantResult) throws Exception {
        JsonNode array = objectMapper.readTree(grantResult.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).map(node -> node.get("id").asLong()).toList();
    }

    private ResultActions list(String token) throws Exception {
        return perform(get(URL).header(HttpHeaders.AUTHORIZATION, authHeader(token)));
    }

    private ResultActions list(String token, int page, int size) throws Exception {
        return perform(get(URL).param("page", String.valueOf(page)).param("size", String.valueOf(size))
                .header(HttpHeaders.AUTHORIZATION, authHeader(token)));
    }

    private ResultActions unreadCount(String token) throws Exception {
        return perform(get(URL + "/unread-count").header(HttpHeaders.AUTHORIZATION, authHeader(token)));
    }

    private ResultActions markRead(String token, Long id) throws Exception {
        return perform(patch(URL + "/{id}/read", id).header(HttpHeaders.AUTHORIZATION, authHeader(token)));
    }

    private Long notificationId(User recipient, int index) {
        return jdbcTemplate.queryForList("SELECT id FROM notification WHERE recipient_id = ? ORDER BY id",
                Long.class, recipient.getId()).get(index);
    }

    private Map<Long, Instant> aliceReadAts() {
        Map<Long, Instant> result = new HashMap<>();
        jdbcTemplate.query("SELECT id, read_at FROM notification WHERE recipient_id = ?",
                rs -> {
                    Timestamp readAt = rs.getTimestamp("read_at");
                    result.put(rs.getLong("id"), readAt == null ? null : readAt.toInstant());
                }, alice.getId());
        return result;
    }

    // Истёкший доступ API создать не позволяет: такая запись появляется только со временем.
    private void insertExpiredShare(Long folderId, User recipient) {
        shareRepository.save(Share.builder()
                .owner(owner)
                .folder(folderRepository.findById(folderId).orElseThrow())
                .kind(ShareKind.USER)
                .recipient(recipient)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build());
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
