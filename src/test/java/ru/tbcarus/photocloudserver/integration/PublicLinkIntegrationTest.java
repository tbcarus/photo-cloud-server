package ru.tbcarus.photocloudserver.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import ru.tbcarus.photocloudserver.config.filter.HttpLoggingFilter;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.service.link.LinkSharingProperties;
import ru.tbcarus.photocloudserver.service.link.LinkTokenService;
import ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailFixtures;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Публичные ссылки: управление владельцем и анонимное чтение. Правила USER-доступов проверяют тесты 8.1–8.2.
// Дерево владельца: ROOT/{root.txt, Shared/{a.txt, photo.png, page.svg, Nested/{n.txt}}, Sibling/{s.txt}}.
@ExtendWith(OutputCaptureExtension.class)
class PublicLinkIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";
    private static final String STRICT_CSP = "default-src 'none'; sandbox";
    // Страница папки: только скрипты, стили, изображения и запросы того же адреса.
    private static final String PAGE_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
            + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final byte[] A_BYTES = "public link content".getBytes(StandardCharsets.UTF_8);

    @Autowired
    LinkSharingProperties linkProperties;

    private User owner;
    private String ownerToken;
    private String otherToken;
    private Long rootId;
    private Long sharedId;
    private Long nestedId;
    private Long siblingId;
    private Long rootFileId;
    private Long aFileId;
    private Long photoId;
    private Long svgId;
    private Long nFileId;
    private Long sFileId;

    @BeforeEach
    void createTree() throws Exception {
        owner = createUser("owner@test.local", PASSWORD);
        createUser("other@test.local", PASSWORD);
        ownerToken = loginAndGetAccessToken("owner@test.local", PASSWORD);
        otherToken = loginAndGetAccessToken("other@test.local", PASSWORD);
        rootId = getRootId(ownerToken);
        sharedId = createFolder(ownerToken, rootId, "Shared").get("id").asLong();
        nestedId = createFolder(ownerToken, sharedId, "Nested").get("id").asLong();
        siblingId = createFolder(ownerToken, rootId, "Sibling").get("id").asLong();
        rootFileId = upload(ownerToken, "root.txt", "text/plain", bytes("root"), rootId).getId();
        aFileId = upload(ownerToken, "a.txt", "text/plain", A_BYTES, sharedId).getId();
        photoId = upload(ownerToken, "photo.png", "image/png", ThumbnailFixtures.png(8, 8), sharedId).getId();
        svgId = upload(ownerToken, "page.svg", "image/svg+xml",
                bytes("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"), sharedId).getId();
        nFileId = upload(ownerToken, "n.txt", "text/plain", bytes("nested"), nestedId).getId();
        sFileId = upload(ownerToken, "s.txt", "text/plain", bytes("sibling"), siblingId).getId();
    }

    @Test
    void createdLinkStoresOnlyHashAndEncryptedSecretAndUrlSurvivesRestart() throws Exception {
        JsonNode link = json(createLink(ownerToken, file(aFileId), null).andExpect(status().isOk()).andReturn());
        String url = link.get("url").asText();
        String token = tokenOf(url);
        assertThat(url).startsWith("http://localhost:8080/s/");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(link.has("tokenHash")).isFalse();
        assertThat(link.has("encryptedToken")).isFalse();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT kind, token_hash, encrypted_token, recipient_id FROM share WHERE id = ?", link.get("id").asLong());
        assertThat(row.get("kind")).isEqualTo("LINK");
        assertThat(row.get("token_hash")).isEqualTo(sha256(token.getBytes(StandardCharsets.US_ASCII)));
        assertThat((String) row.get("encrypted_token")).doesNotContain(token);
        assertThat(row.get("recipient_id")).isNull();
        // Новый экземпляр сервиса с тем же ключом — как после перезапуска Server — восстанавливает тот же секрет.
        assertThat(new LinkTokenService(linkProperties).decrypt((String) row.get("encrypted_token"))).contains(token);
        perform(get("/api/v1/shares/links").header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(jsonPath("$.items[0].url").value(url))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));

        // Каждая выдача — новая независимая ссылка; обе действуют.
        String second = tokenOf(json(createLink(ownerToken, file(aFileId), null).andReturn()).get("url").asText());
        assertThat(second).isNotEqualTo(token);
        anonymous(get("/api/v1/public/s/{token}", token)).andExpect(status().isOk());
        anonymous(get("/api/v1/public/s/{token}", second)).andExpect(status().isOk());
        // LINK не попадает в список USER-доступов.
        perform(get("/api/v1/shares").header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void expiryRevocationAndOwnerStateControlAccess() throws Exception {
        createLink(ownerToken, file(aFileId), Instant.now().minusSeconds(60).toString()).andExpect(status().isBadRequest());
        JsonNode link = json(createLink(ownerToken, file(aFileId), "2031-01-01T00:00:00Z").andReturn());
        long id = link.get("id").asLong();
        String token = tokenOf(link.get("url").asText());

        patchLink(ownerToken, id, "{\"expiresAt\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").doesNotExist());
        patchLink(ownerToken, id, "{}").andExpect(status().isBadRequest());
        jdbcTemplate.update("UPDATE share SET expires_at = now() - interval '1 second' WHERE id = ?", id);
        anonymous(get("/s/{token}", token)).andExpect(status().isNotFound());
        perform(get("/api/v1/shares/links").header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(jsonPath("$.items[0].status").value("EXPIRED"));
        // Истёкшая ссылка продлевается тем же URL.
        patchLink(ownerToken, id, "{\"expiresAt\":\"2031-01-01T00:00:00Z\"}").andExpect(jsonPath("$.status").value("ACTIVE"));
        anonymous(get("/s/{token}", token)).andExpect(status().isOk());

        jdbcTemplate.update("UPDATE users SET banned = true WHERE id = ?", owner.getId());
        anonymous(get("/s/{token}", token)).andExpect(status().isNotFound());
        jdbcTemplate.update("UPDATE users SET banned = false WHERE id = ?", owner.getId());

        perform(delete("/api/v1/shares/links/{id}", id).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());
        anonymous(get("/s/{token}", token)).andExpect(status().isNotFound());
        perform(delete("/api/v1/shares/links/{id}", id).header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyOwnerManagesLinksAndRevokeAllKeepsUserShares() throws Exception {
        long id = json(createLink(ownerToken, folder(sharedId), null).andReturn()).get("id").asLong();
        createLink(ownerToken, folder(sharedId), null).andExpect(status().isOk());
        perform(post("/api/v1/shares/users").header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"folderId\":" + sharedId + ",\"emails\":[\"other@test.local\"]}"))
                .andExpect(status().isOk());

        perform(get("/api/v1/shares/links").header(HttpHeaders.AUTHORIZATION, authHeader(otherToken)))
                .andExpect(jsonPath("$.totalElements").value(0));
        patchLink(otherToken, id, "{\"expiresAt\":null}").andExpect(status().isNotFound());
        perform(delete("/api/v1/shares/links/{id}", id).header(HttpHeaders.AUTHORIZATION, authHeader(otherToken)))
                .andExpect(status().isNotFound());
        // Получатель USER-доступа не создаёт ссылку на чужой объект; ROOT не предоставляется.
        createLink(otherToken, folder(sharedId), null).andExpect(status().isNotFound());
        createLink(ownerToken, folder(rootId), null).andExpect(status().isBadRequest());

        perform(delete("/api/v1/shares/links").param("folderId", sharedId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM share WHERE kind = 'LINK'", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM share WHERE kind = 'USER'", Long.class)).isEqualTo(1);
    }

    @Test
    void fileLinkServesBytesWithRangeAndSafeDisposition() throws Exception {
        String token = linkToken(file(aFileId));

        // Без JWT, и случайный устаревший заголовок Authorization не мешает.
        anonymous(get("/s/{token}", token).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(A_BYTES))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"a.txt\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
        anonymous(get("/s/{token}", token).header(HttpHeaders.RANGE, "bytes=7-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 7-%d/%d".formatted(A_BYTES.length - 1, A_BYTES.length)))
                .andExpect(content().bytes(Arrays.copyOfRange(A_BYTES, 7, A_BYTES.length)));
        anonymous(get("/api/v1/public/s/{token}/files/{id}/download", token, aFileId).header(HttpHeaders.RANGE, "bytes=0-5"))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOfRange(A_BYTES, 0, 6)));

        // Изображение показывается встроенным просмотром браузера: исполнить его нельзя, строгая CSP сломала бы просмотр.
        anonymous(get("/s/{token}", linkToken(file(photoId))))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"photo.png\""))
                .andExpect(header().doesNotExist("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        // SVG со скриптом только скачивается и остаётся в sandbox; ошибки — тоже со строгой политикой.
        anonymous(get("/s/{token}", linkToken(file(svgId))))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"page.svg\""))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
        anonymous(get("/s/{token}", "C".repeat(43)))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        // Ссылка на файл не открывает папок и других файлов.
        anonymous(get("/api/v1/public/s/{token}/files/{id}/download", token, photoId)).andExpect(status().isNotFound());
        anonymous(get("/api/v1/public/s/{token}/folders/{id}/children", token, sharedId)).andExpect(status().isNotFound());
    }

    @Test
    void folderLinkOpensOnlyItsSubtreeWithMinimalData() throws Exception {
        String token = linkToken(folder(sharedId));

        String root = anonymous(get("/api/v1/public/s/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FOLDER"))
                .andExpect(jsonPath("$.id").value(sharedId))
                .andExpect(jsonPath("$.name").value("Shared"))
                .andReturn().getResponse().getContentAsString();
        String children = anonymous(get("/api/v1/public/s/{token}/folders/{id}/children", token, sharedId))
                .andExpect(jsonPath("$[0].name").value("Nested"))
                .andReturn().getResponse().getContentAsString();
        String files = anonymous(get("/api/v1/public/s/{token}/files", token).param("folderId", sharedId.toString()).param("sort", "name"))
                .andExpect(jsonPath("$.items[0].name").value("a.txt"))
                .andExpect(jsonPath("$.items[0].mimeType").value("text/plain"))
                .andExpect(jsonPath("$.items[0].size").value(A_BYTES.length))
                .andReturn().getResponse().getContentAsString();
        assertThat(root + children + files).doesNotContain("checksum", "backupSource", "metadata", "owner", "email", "folderType",
                "tokenHash", "encryptedToken", "storage");
        anonymous(get("/api/v1/public/s/{token}/files", token).param("folderId", nestedId.toString()))
                .andExpect(jsonPath("$.items[0].name").value("n.txt"));
        anonymous(get("/api/v1/public/s/{token}/files/{id}/download", token, nFileId)).andExpect(status().isOk());
        anonymous(get("/api/v1/public/s/{token}/files/{id}/thumbnail", token, photoId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        // Родитель и соседи владельца недоступны подбором ID.
        anonymous(get("/api/v1/public/s/{token}/folders/{id}/children", token, rootId)).andExpect(status().isNotFound());
        anonymous(get("/api/v1/public/s/{token}/files", token).param("folderId", siblingId.toString())).andExpect(status().isNotFound());
        anonymous(get("/api/v1/public/s/{token}/files/{id}/download", token, rootFileId)).andExpect(status().isNotFound());
        anonymous(get("/api/v1/public/s/{token}/files/{id}/thumbnail", token, sFileId)).andExpect(status().isNotFound());

        // Доступ следует текущему дереву: файл внутрь — открыт, наружу — закрыт.
        moveFile(sFileId, nestedId);
        anonymous(get("/api/v1/public/s/{token}/files/{id}/download", token, sFileId)).andExpect(status().isOk());
        moveFile(nFileId, siblingId);
        anonymous(get("/api/v1/public/s/{token}/files/{id}/download", token, nFileId)).andExpect(status().isNotFound());
    }

    @Test
    void folderLinkOpensStaticBrowserPageWithOwnCsp() throws Exception {
        // Имя со спецсимволами (< > " в именах папок запрещены, сущности — нет): страница его не содержит,
        // API отдаёт как данные, скрипт выводит текстом.
        String hostile = "Фото &lt;img src=x onerror=alert(1)&gt; & 'q'";
        Long hostileId = createFolder(ownerToken, sharedId, hostile).get("id").asLong();
        String token = linkToken(folder(sharedId));

        String page = anonymous(get("/s/{token}", token).accept(MediaType.TEXT_HTML)
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Content-Security-Policy", PAGE_CSP))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("assets/link.js", "assets/link.css").doesNotContain("Shared", "onerror", token, "<style", "http");
        // Страница без inline-скриптов: CSP не разрешает их, весь код — во внешнем файле того же адреса.
        assertThat(page.replace("<script src=\"assets/link.js\" defer></script>", "")).doesNotContain("<script");
        // Без Accept: text/html — тоже страница: тип ресурса определяет Server, а не клиент.
        anonymous(get("/s/{token}", token)).andExpect(content().contentType("text/html;charset=UTF-8"));
        anonymous(get("/api/v1/public/s/{token}/folders/{id}/children", token, sharedId))
                .andExpect(jsonPath("$[?(@.id == %d)].name".formatted(hostileId)).value(hostile));

        // Ссылка на файл в браузере — прежнее поведение 8.5.
        anonymous(get("/s/{token}", linkToken(file(aFileId))).accept(MediaType.TEXT_HTML))
                .andExpect(content().bytes(A_BYTES))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"a.txt\""))
                .andExpect(header().string("Content-Security-Policy", STRICT_CSP));

        // Отозванная ссылка: браузер получает простую страницу 404 без скрипта и сведений о ресурсе, API — JSON.
        perform(delete("/api/v1/shares/links").param("folderId", sharedId.toString())
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken)))
                .andExpect(status().isNoContent());
        String notFound = anonymous(get("/s/{token}", token).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Content-Security-Policy", PAGE_CSP))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(notFound).contains("Ссылка недоступна").doesNotContain("<script", "Shared", token);
        anonymous(get("/s/{token}", token)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Not found"));
        anonymous(get("/api/v1/public/s/{token}/folders/{id}/children", token, sharedId)).andExpect(status().isNotFound());
    }

    @Test
    void pageAssetsArePublicStaticAndSafe() throws Exception {
        String script = anonymous(get("/s/assets/link.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String style = anonymous(get("/s/assets/link.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Имена выводятся только текстом; внешних адресов, eval и записи в консоль нет.
        assertThat(script).contains("textContent")
                .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
                        "console.", "http:", "https:", "localStorage");
        assertThat(style).doesNotContain("http:", "https:", "@import", "url(");
        assertThat(script + style).doesNotContain(linkProperties.getLinkKey());
        // Статика открыта только в /s/assets: остальные ресурсы Server по-прежнему требуют входа.
        anonymous(get("/css/style.css")).andExpect(status().isUnauthorized());
        anonymous(get("/s/assets/missing.js")).andExpect(status().isNotFound());
    }

    @Test
    void unknownMalformedAndForeignTokensAreIndistinguishable() throws Exception {
        String unknown = "A".repeat(43);
        for (String token : List.of(unknown, "short", "A".repeat(42) + "!")) {
            String body = anonymous(get("/s/{token}", token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Not found"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(token);
        }
        // Анонимно доступно только чтение: изменение по публичным маршрутам требует входа и ничего не меняет.
        String token = linkToken(folder(sharedId));
        anonymous(post("/api/v1/public/s/{token}/files", token)).andExpect(status().isUnauthorized());
        anonymous(delete("/api/v1/public/s/{token}/files/{id}/download", token, aFileId)).andExpect(status().isUnauthorized());
        anonymous(put("/s/{token}", token)).andExpect(status().isUnauthorized());
        assertThat(fileItemRepository.count()).isEqualTo(6);
    }

    @Test
    void tokenNeverAppearsInHttpLog(CapturedOutput output) throws Exception {
        String token = linkToken(file(aFileId));
        // Создание ссылки печатает ответ с URL через тестовый помощник perform(); проверяется только то, что пишется дальше.
        int start = output.getAll().length();
        Logger filterLogger = (Logger) LoggerFactory.getLogger(HttpLoggingFilter.class);
        Level previous = filterLogger.getLevel();
        filterLogger.setLevel(Level.INFO);
        try {
            // mockMvc напрямую: вспомогательный perform() печатает адрес запроса в вывод теста.
            mockMvc.perform(get("/s/{token}", token)).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/public/s/{token}/files/{id}/download", token, aFileId)).andExpect(status().isOk());
            mockMvc.perform(get("/s/{token}", "B".repeat(43))).andExpect(status().isNotFound());
        } finally {
            filterLogger.setLevel(previous);
        }
        String written = output.getAll().substring(start);
        assertThat(written).contains("GET /s/{token} -> 200", "GET /api/v1/public/s/{token}/files/{id}/download -> 200",
                "GET /s/{token} -> 404");
        assertThat(written).doesNotContain(token, "B".repeat(43));
    }

    private ResultActions createLink(String token, Map<String, Object> resource, String expiresAt) throws Exception {
        Map<String, Object> body = new HashMap<>(resource);
        body.put("expiresAt", expiresAt);
        return perform(post("/api/v1/shares/links")
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String linkToken(Map<String, Object> resource) throws Exception {
        return tokenOf(json(createLink(ownerToken, resource, null).andExpect(status().isOk()).andReturn()).get("url").asText());
    }

    private ResultActions patchLink(String token, long id, String body) throws Exception {
        return perform(patch("/api/v1/shares/links/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions anonymous(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    private void moveFile(Long fileId, Long targetFolderId) throws Exception {
        perform(post("/api/v1/files/{id}/move", fileId)
                .header(HttpHeaders.AUTHORIZATION, authHeader(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetFolderId\":" + targetFolderId + "}"))
                .andExpect(status().isOk());
    }

    private JsonNode json(org.springframework.test.web.servlet.MvcResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String tokenOf(String url) {
        return url.substring(url.indexOf("/s/") + 3);
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
