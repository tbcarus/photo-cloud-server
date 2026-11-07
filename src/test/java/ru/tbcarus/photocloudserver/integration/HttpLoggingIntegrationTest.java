package ru.tbcarus.photocloudserver.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import ru.tbcarus.photocloudserver.config.filter.HttpLoggingFilter;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.model.dto.LoginResponse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-журнал через настоящую цепочку фильтров и MVC: шаблон маршрута вместо фактического пути,
 * без тел, заголовков и query. Запросы с секретами выполняются через mockMvc напрямую, а не через perform():
 * тестовый вывод perform() печатает тела запросов и сам попал бы в проверяемый вывод.
 */
@ExtendWith(OutputCaptureExtension.class)
class HttpLoggingIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "logging@example.com";
    private static final String PASSWORD = "Pa55-logging-secret";

    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(HttpLoggingFilter.class);
    private Level previousLevel;

    // application-test.yml понижает уровень пакета до WARN; журнал запросов пишется на INFO.
    @BeforeEach
    void enableFilterLogging() {
        previousLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void restoreFilterLogging() {
        filterLogger.setLevel(previousLevel);
    }

    @Test
    void loginLeavesNeitherPasswordNorTokensInLog(CapturedOutput output) throws Exception {
        createUser(EMAIL, PASSWORD);

        LoginResponse tokens = login();

        assertThat(output).containsPattern("POST /api/v1/auth/login -> 200 \\| \\d+ ms");
        assertThat(output).doesNotContain(PASSWORD, tokens.accessToken(), tokens.refreshToken());
    }

    @Test
    void authorizedRequestLogsRouteTemplateWithoutAuthorizationAndQuery(CapturedOutput output) throws Exception {
        createUser(EMAIL, PASSWORD);
        String accessToken = login().accessToken();
        Long rootId = getRootId(accessToken);

        mockMvc.perform(get("/api/v1/folders/{id}/children", rootId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(accessToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/files")
                        .param("folderId", "987654")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(accessToken)))
                .andExpect(status().isNotFound());

        assertThat(output).containsPattern("GET /api/v1/folders/\\{id}/children -> 200 \\| \\d+ ms");
        assertThat(output).containsPattern("GET /api/v1/files -> 404 \\| \\d+ ms");
        assertThat(output).doesNotContain(accessToken, "folderId", "987654");
    }

    @Test
    void requestRejectedBySecurityStillLogsTemplateWithoutSecrets(CapturedOutput output) throws Exception {
        String forgedToken = "forged.jwt.value-3c9d";

        mockMvc.perform(get("/api/v1/files/{id}/download", 135791))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/files/{id}", 135791)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized());

        assertThat(output).containsPattern("GET /api/v1/files/\\{id}/download -> 401 \\| \\d+ ms");
        assertThat(output).containsPattern("GET /api/v1/files/\\{id} -> 401 \\| \\d+ ms");
        assertThat(output).doesNotContain("135791", forgedToken);
    }

    @Test
    void emailConfirmationCodeIsNotLogged(CapturedOutput output) throws Exception {
        String code = "confirm-code-5e1b";

        mockMvc.perform(get("/api/v1/auth/register/confirm").param("code", code))
                .andExpect(status().isBadRequest());

        assertThat(output).containsPattern("GET /api/v1/auth/register/confirm -> 400 \\| \\d+ ms");
        assertThat(output).doesNotContain(code);
    }

    @Test
    void downloadAndRangeAreUnchanged(CapturedOutput output) throws Exception {
        createUser(EMAIL, PASSWORD);
        String accessToken = login().accessToken();
        byte[] bytes = "0123456789-range-check-content".getBytes(StandardCharsets.US_ASCII);
        FileItemDto uploaded = upload(accessToken, "range.txt", "text/plain", bytes);

        MvcResult full = mockMvc.perform(get("/api/v1/files/{id}/download", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(accessToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"range.txt\""))
                .andReturn();
        MvcResult partial = mockMvc.perform(get("/api/v1/files/{id}/download", uploaded.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(accessToken))
                        .header(HttpHeaders.RANGE, "bytes=10-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 10-" + (bytes.length - 1) + "/" + bytes.length))
                .andReturn();

        assertThat(full.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(full.getResponse().getContentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
        assertThat(partial.getResponse().getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(bytes, 10, bytes.length));
        assertThat(output).containsPattern("GET /api/v1/files/\\{id}/download -> 200 \\| \\d+ ms");
        assertThat(output).containsPattern("GET /api/v1/files/\\{id}/download -> 206 \\| \\d+ ms");
        assertThat(output).doesNotContain("range-check-content");
    }

    private LoginResponse login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
    }
}
