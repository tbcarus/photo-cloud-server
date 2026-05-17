package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthErrorHandlingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";

    @Test
    void invalidRegistrationCodeReturnsBadRequestErrorResponse() throws Exception {
        perform(get("/api/v1/auth/register/confirm").param("code", "missing-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.uuid").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void usedRegistrationCodeReturnsBadRequestErrorResponse() throws Exception {
        User user = createUser("used-confirm@test.local", PASSWORD);
        String code = "used-confirm-code";
        emailRequestRepository.save(EmailRequest.builder()
                .code(code)
                .type(EmailRequestType.ACTIVATE)
                .used(true)
                .user(user)
                .build());

        perform(get("/api/v1/auth/register/confirm").param("code", code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.uuid").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void expiredRegistrationCodeReturnsBadRequestErrorResponse() throws Exception {
        User user = createUser("expired-confirm@test.local", PASSWORD);
        String code = "expired-confirm-code";
        emailRequestRepository.save(EmailRequest.builder()
                .code(code)
                .type(EmailRequestType.ACTIVATE)
                .used(false)
                .user(user)
                .build());
        jdbcTemplate.update(
                "UPDATE email_requests SET created_at = ? WHERE code = ?",
                LocalDateTime.now().minusDays(4),
                code
        );

        perform(get("/api/v1/auth/register/confirm").param("code", code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.uuid").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void invalidPasswordResetCodeReturnsBadRequestErrorResponse() throws Exception {
        perform(post("/api/v1/auth/password/reset/confirm")
                        .param("password", PASSWORD)
                        .param("code", "missing-reset-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.uuid").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void passwordResetRequestValidationRejectsBlankEmail() throws Exception {
        perform(post("/api/v1/auth/password/reset/request")
                        .param("email", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").value("Email must not be blank"));
    }

    @Test
    void passwordResetConfirmValidationRejectsBlankPasswordAndCode() throws Exception {
        perform(post("/api/v1/auth/password/reset/confirm")
                        .param("password", "")
                        .param("code", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.password").value("Password must not be blank"))
                .andExpect(jsonPath("$.code").value("Code must not be blank"));
    }

    @Test
    void loginValidationRejectsNullAndBlankFields() throws Exception {
        assertValidationBody("/api/v1/auth/login", "{\"email\":null,\"password\":\"pass1\"}", "email", "Email must not be blank");
        assertValidationBody("/api/v1/auth/login", "{\"email\":\"\",\"password\":\"pass1\"}", "email", "Email must not be blank");
        assertValidationBody("/api/v1/auth/login", "{\"email\":\"user@test.local\",\"password\":null}", "password", "Password must not be blank");
        assertValidationFieldExists("/api/v1/auth/login", "{\"email\":\"user@test.local\",\"password\":\"\"}", "password");
    }

    @Test
    void registerValidationRejectsNullAndBlankFields() throws Exception {
        assertValidationBody("/api/v1/auth/register", "{\"email\":null,\"password\":\"pass1\"}", "email", "Email must not be blank");
        assertValidationBody("/api/v1/auth/register", "{\"email\":\"\",\"password\":\"pass1\"}", "email", "Email must not be blank");
        assertValidationBody("/api/v1/auth/register", "{\"email\":\"user@test.local\",\"password\":null}", "password", "Password must not be blank");
        assertValidationFieldExists("/api/v1/auth/register", "{\"email\":\"user@test.local\",\"password\":\"\"}", "password");
    }

    @Test
    void refreshValidationRejectsBlankRefreshToken() throws Exception {
        assertValidationBody("/api/v1/auth/refresh-token", "{\"refreshToken\":\"\"}", "refreshToken", "Refresh token must not be blank");
    }

    @Test
    void logoutValidationRejectsBlankRefreshToken() throws Exception {
        createUser("logout-validation@test.local", PASSWORD);
        String accessToken = loginAndGetAccessToken("logout-validation@test.local", PASSWORD);

        perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.refreshToken").value("Refresh token must not be blank"));
    }

    @Test
    void protectedEndpointWithoutTokenReturnsUnauthorizedErrorResponse() throws Exception {
        perform(get("/api/v1/media"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.uuid").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Unauthorized: access token expired or invalid"));
    }

    @Test
    void protectedEndpointWithInvalidJwtReturnsUnauthorizedErrorResponse() throws Exception {
        perform(get("/api/v1/media")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.uuid").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Unauthorized: access token expired or invalid"));
    }

    private void assertValidationBody(String path, String body, String field, String expectedMessage) throws Exception {
        MvcResult result = perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.get(field).asText()).isEqualTo(expectedMessage);
    }

    private void assertValidationFieldExists(String path, String body, String field) throws Exception {
        MvcResult result = perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.has(field)).isTrue();
    }
}
