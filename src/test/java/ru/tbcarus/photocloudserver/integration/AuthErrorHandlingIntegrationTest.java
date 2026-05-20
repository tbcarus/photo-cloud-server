package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.RefreshToken;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.LoginResponse;

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
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("BAD_REGISTRATION_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
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
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("BAD_REGISTRATION_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
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
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("BAD_REGISTRATION_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void invalidPasswordResetCodeReturnsBadRequestErrorResponse() throws Exception {
        perform(post("/api/v1/auth/password/reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\",\"code\":\"missing-reset-code\"}".formatted(PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("BAD_REGISTRATION_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void passwordResetRequestValidationRejectsBlankEmail() throws Exception {
        perform(post("/api/v1/auth/password/reset/request")
                        .param("email", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.email").value("Email must not be blank"));
    }

    @Test
    void passwordResetConfirmValidationRejectsBlankPasswordAndCode() throws Exception {
        perform(post("/api/v1/auth/password/reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\",\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.code").value("Code must not be blank"));
    }

    @Test
    void passwordResetConfirmRejectsLegacyQueryParamContract() throws Exception {
        perform(post("/api/v1/auth/password/reset/confirm")
                        .param("password", PASSWORD)
                        .param("code", "reset-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed or missing request body"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void successfulPasswordReset() throws Exception {
        User user = createUser("password-reset-success@test.local", PASSWORD);
        String code = "password-reset-success-code";
        emailRequestRepository.save(EmailRequest.builder()
                .code(code)
                .type(EmailRequestType.PASSWORD_RESET)
                .used(false)
                .user(user)
                .build());

        perform(post("/api/v1/auth/password/reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"newPass1\",\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password was reset"));

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("newPass1", updatedUser.getPassword())).isTrue();
        assertThat(emailRequestRepository.findByCode(code).orElseThrow().isUsed()).isTrue();
    }

    @Test
    void passwordResetConfirmValidationRejectsTooShortPassword() throws Exception {
        perform(post("/api/v1/auth/password/reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"abc\",\"code\":\"reset-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Password length must be from 4 to 20"));
    }

    @Test
    void passwordResetConfirmValidationRejectsTooLongPassword() throws Exception {
        perform(post("/api/v1/auth/password/reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"abcdefghijklmnopqrstu\",\"code\":\"reset-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Password length must be from 4 to 20"));
    }

    @Test
    void loginValidationRejectsNullAndBlankFields() throws Exception {
        assertValidationBody("/api/v1/auth/login", "{\"email\":null,\"password\":\"pass1\"}", "email", "Email must not be blank");
        assertValidationBody("/api/v1/auth/login", "{\"email\":\"\",\"password\":\"pass1\"}", "email", "Email must not be blank");
        assertValidationBody("/api/v1/auth/login", "{\"email\":\"user@test.local\",\"password\":null}", "password", "Password must not be blank");
        assertValidationFieldExists("/api/v1/auth/login", "{\"email\":\"user@test.local\",\"password\":\"\"}", "password");
    }

    @Test
    void loginWithUnknownEmailReturnsUnauthorizedErrorResponse() throws Exception {
        perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@test.local\",\"password\":\"pass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorizedErrorResponse() throws Exception {
        createUser("wrong-password@test.local", PASSWORD);

        perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"wrong-password@test.local\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void bannedUserCannotLogin() throws Exception {
        createUser("banned@test.local", PASSWORD, true, true);

        perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"banned@test.local\",\"password\":\"pass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void disabledUserCannotLogin() throws Exception {
        createUser("disabled@test.local", PASSWORD, false, false);

        perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"disabled@test.local\",\"password\":\"pass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void successfulLoginUpdatesLastLoginAt() throws Exception {
        User user = createUser("last-login@test.local", PASSWORD);
        assertThat(user.getLastLoginAt()).isNull();

        login("last-login@test.local", PASSWORD);

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updatedUser.getLastLoginAt()).isNotNull();
    }

    @Test
    void registerValidationRejectsNullAndBlankFields() throws Exception {
        assertValidationBody("/api/v1/auth/register", "{\"email\":null,\"password\":\"pass1\"}", "email", "Email must not be blank");
        assertValidationBody("/api/v1/auth/register", "{\"email\":\"\",\"password\":\"pass1\"}", "email", "Email must not be blank");
        assertValidationBody("/api/v1/auth/register", "{\"email\":\"user@test.local\",\"password\":null}", "password", "Password must not be blank");
        assertValidationFieldExists("/api/v1/auth/register", "{\"email\":\"user@test.local\",\"password\":\"\"}", "password");
    }

    @Test
    void duplicateRegisterReturnsConflictErrorResponse() throws Exception {
        createUser("duplicate@test.local", PASSWORD);

        perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"duplicate@test.local\",\"password\":\"pass1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Email is already registered"));
    }

    @Test
    void refreshValidationRejectsBlankRefreshToken() throws Exception {
        assertValidationBody("/api/v1/auth/refresh-token", "{\"refreshToken\":\"\"}", "refreshToken", "Refresh token must not be blank");
    }

    @Test
    void refreshWithUnknownTokenReturnsUnauthorizedErrorResponse() throws Exception {
        perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"unknown-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    @Test
    void refreshWithRevokedTokenReturnsForbiddenErrorResponse() throws Exception {
        createUser("refresh-revoked@test.local", PASSWORD);
        LoginResponse tokens = login("refresh-revoked@test.local", PASSWORD);
        revokeRefreshToken(tokens.refreshToken());

        perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(tokens.refreshToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("token revoked"));
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
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.refreshToken").value("Refresh token must not be blank"));
    }

    @Test
    void logoutWithForeignRefreshTokenReturnsForbidden() throws Exception {
        createUser("logout-owner@test.local", PASSWORD);
        createUser("logout-foreign@test.local", PASSWORD);
        LoginResponse ownerTokens = login("logout-owner@test.local", PASSWORD);
        LoginResponse foreignTokens = login("logout-foreign@test.local", PASSWORD);

        perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(ownerTokens.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(foreignTokens.refreshToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_OWNERSHIP_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Refresh token does not belong to current user"));

        assertThat(findRefreshToken(foreignTokens.refreshToken()).isRevoked()).isFalse();
    }

    @Test
    void logoutWithUnknownRefreshTokenReturnsNotFound() throws Exception {
        createUser("logout-unknown@test.local", PASSWORD);
        LoginResponse tokens = login("logout-unknown@test.local", PASSWORD);

        perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(tokens.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"unknown-token\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_NOT_FOUND"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Refresh token not found"));
    }

    @Test
    void logoutWithOwnRefreshTokenReturnsOk() throws Exception {
        createUser("logout-own@test.local", PASSWORD);
        LoginResponse tokens = login("logout-own@test.local", PASSWORD);

        perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(tokens.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(tokens.refreshToken())))
                .andExpect(status().isOk());

        assertThat(findRefreshToken(tokens.refreshToken()).isRevoked()).isTrue();
    }

    @Test
    void logoutOthersWithForeignRefreshTokenReturnsForbiddenWithoutRevokingOwnTokens() throws Exception {
        createUser("logout-others-owner@test.local", PASSWORD);
        createUser("logout-others-foreign@test.local", PASSWORD);
        LoginResponse ownerCurrentTokens = login("logout-others-owner@test.local", PASSWORD);
        LoginResponse ownerOtherTokens = login("logout-others-owner@test.local", PASSWORD);
        LoginResponse foreignTokens = login("logout-others-foreign@test.local", PASSWORD);

        perform(post("/api/v1/auth/logout-others")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(ownerCurrentTokens.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(foreignTokens.refreshToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_OWNERSHIP_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Refresh token does not belong to current user"));

        assertThat(findRefreshToken(ownerCurrentTokens.refreshToken()).isRevoked()).isFalse();
        assertThat(findRefreshToken(ownerOtherTokens.refreshToken()).isRevoked()).isFalse();
    }

    @Test
    void logoutOthersWithUnknownRefreshTokenReturnsNotFound() throws Exception {
        createUser("logout-others-unknown@test.local", PASSWORD);
        LoginResponse tokens = login("logout-others-unknown@test.local", PASSWORD);

        perform(post("/api/v1/auth/logout-others")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(tokens.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"unknown-token\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_NOT_FOUND"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Refresh token not found"));
    }

    @Test
    void logoutOthersWithOwnRefreshTokenKeepsCurrentAndRevokesOtherOwnTokens() throws Exception {
        createUser("logout-others-own@test.local", PASSWORD);
        LoginResponse currentTokens = login("logout-others-own@test.local", PASSWORD);
        LoginResponse otherTokens = login("logout-others-own@test.local", PASSWORD);

        perform(post("/api/v1/auth/logout-others")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(currentTokens.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(currentTokens.refreshToken())))
                .andExpect(status().isOk());

        assertThat(findRefreshToken(currentTokens.refreshToken()).isRevoked()).isFalse();
        assertThat(findRefreshToken(otherTokens.refreshToken()).isRevoked()).isTrue();
    }

    @Test
    void protectedEndpointWithoutTokenReturnsUnauthorizedErrorResponse() throws Exception {
        perform(get("/api/v1/files"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Unauthorized: access token expired or invalid"));
    }

    @Test
    void protectedEndpointWithInvalidJwtReturnsUnauthorizedErrorResponse() throws Exception {
        perform(get("/api/v1/files")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("Unauthorized: access token expired or invalid"));
    }

    private void assertValidationBody(String path, String body, String field, String expectedMessage) throws Exception {
        MvcResult result = perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.get("message").asText()).isEqualTo("Validation failed");
        assertThat(response.get("fieldErrors").get(field).asText()).isEqualTo(expectedMessage);
    }

    private void assertValidationFieldExists(String path, String body, String field) throws Exception {
        MvcResult result = perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.get("message").asText()).isEqualTo("Validation failed");
        assertThat(response.get("fieldErrors").has(field)).isTrue();
    }

    private LoginResponse login(String email, String password) throws Exception {
        MvcResult result = perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
    }

    private RefreshToken findRefreshToken(String token) {
        return jdbcTemplate.queryForObject(
                "SELECT id, token, user_name, expires, revoked, revoked_at FROM refresh_token WHERE token = ?",
                (rs, rowNum) -> RefreshToken.builder()
                        .id(rs.getLong("id"))
                        .token(rs.getString("token"))
                        .userName(rs.getString("user_name"))
                        .expires(rs.getTimestamp("expires").toLocalDateTime())
                        .revoked(rs.getBoolean("revoked"))
                        .revokedAt(rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toLocalDateTime())
                        .build(),
                token
        );
    }

    private void revokeRefreshToken(String token) {
        jdbcTemplate.update("UPDATE refresh_token SET revoked = true WHERE token = ?", token);
    }
}
