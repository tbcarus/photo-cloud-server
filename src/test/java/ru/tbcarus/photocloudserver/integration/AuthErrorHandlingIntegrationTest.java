package ru.tbcarus.photocloudserver.integration;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.RefreshToken;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.LoginResponse;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthErrorHandlingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";

    // Письма reset/request уходят в mock; реальный SMTP в тестах не используется.
    @MockitoBean
    private JavaMailSender mailSender;

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
    void freshRegistrationCodeActivatesUser() throws Exception {
        User user = createUser("fresh-confirm@test.local", PASSWORD, false, false);
        String code = "fresh-confirm-code";
        emailRequestRepository.save(EmailRequest.builder()
                .code(code)
                .type(EmailRequestType.ACTIVATE)
                .used(false)
                .user(user)
                .build());

        perform(get("/api/v1/auth/register/confirm").param("code", code))
                .andExpect(status().isOk())
                .andExpect(content().string("User fresh-confirm@test.local was verified"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(emailRequestRepository.findByCode(code).orElseThrow().isUsed()).isTrue();
        loginAndGetAccessToken("fresh-confirm@test.local", PASSWORD);
    }

    @Test
    void registrationCodeCannotBeUsedTwice() throws Exception {
        User user = createUser("twice-confirm@test.local", PASSWORD, false, false);
        String code = saveEmailRequest(user, "twice-confirm-code", EmailRequestType.ACTIVATE);

        perform(get("/api/v1/auth/register/confirm").param("code", code))
                .andExpect(status().isOk());
        perform(get("/api/v1/auth/register/confirm").param("code", code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REGISTRATION_REQUEST"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(emailRequestRepository.findByCode(code).orElseThrow().isUsed()).isTrue();
    }

    @Test
    void passwordResetCodeCannotActivateUser() throws Exception {
        User user = createUser("reset-type-confirm@test.local", PASSWORD, false, false);
        String code = saveEmailRequest(user, "reset-type-confirm-code", EmailRequestType.PASSWORD_RESET);

        perform(get("/api/v1/auth/register/confirm").param("code", code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REGISTRATION_REQUEST"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(emailRequestRepository.findByCode(code).orElseThrow().isUsed()).isFalse();
    }

    @Test
    void activationDoesNotConsumeCodeWhenUserUpdateFails() throws Exception {
        assertActivationRolledBack("users", "enabled = false");
    }

    @Test
    void activationDoesNotEnableUserWhenCodeUpdateFails() throws Exception {
        assertActivationRolledBack("email_requests", "used = false");
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
    void passwordResetRequestSendsEmailAndReturnsOk() throws Exception {
        createUser("reset-request@test.local", PASSWORD);
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));

        perform(post("/api/v1/auth/password/reset/request").param("email", "reset-request@test.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email was sent"));

        assertSingleUnusedResetCode();
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void passwordResetRequestReturns503WhenSmtpFails() throws Exception {
        createUser("reset-request@test.local", PASSWORD);
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
        doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(any(MimeMessage.class));

        assertPasswordResetEmailNotSent("reset-request@test.local", "SMTP unavailable");
    }

    @Test
    void passwordResetRequestReturns503WhenMessageCannotBeBuilt() throws Exception {
        createUser("reset-request@test.local", PASSWORD);
        // MessagingException без причины: прежний catch падал на getCause() с NPE.
        MimeMessage message = spy(new MimeMessage((Session) null));
        doThrow(new MessagingException("Subject rejected")).when(message).setSubject(anyString(), anyString());
        when(mailSender.createMimeMessage()).thenReturn(message);

        assertPasswordResetEmailNotSent("reset-request@test.local", "Subject rejected");
        verify(mailSender, never()).send(any(MimeMessage.class));
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
    void registerValidationAcceptsEmailOf128Characters() throws Exception {
        // Невалидный пароль останавливает запрос на валидации; отсутствие ошибки email подтверждает допустимость 128 символов.
        perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"123\"}".formatted(emailOfLength(128))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Password length must be from 4 to 20"))
                .andExpect(jsonPath("$.fieldErrors.email").doesNotExist());
    }

    @Test
    void registerValidationRejectsEmailLongerThan128Characters() throws Exception {
        perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pass1\"}".formatted(emailOfLength(129))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.email").value("Email length must be up to 128"))
                .andExpect(jsonPath("$.fieldErrors.password").doesNotExist());

        assertThat(userRepository.count()).isZero();
        assertThat(emailRequestRepository.count()).isZero();
    }

    @Test
    void registerValidationRejectsInvalidEmailFormat() throws Exception {
        assertValidationBody("/api/v1/auth/register", "{\"email\":\"not-an-email\",\"password\":\"pass1\"}", "email", "Email must be valid");
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

    // 503 в стандартном ErrorResponse без технических подробностей; PASSWORD_RESET-код, сохранённый до отправки, остаётся.
    private void assertPasswordResetEmailNotSent(String email, String technicalDetail) throws Exception {
        String body = perform(post("/api/v1/auth/password/reset/request").param("email", email))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_SENT"))
                .andExpect(jsonPath("$.message").value("The password reset email could not be sent. Please try again later."))
                .andExpect(jsonPath("$.fieldErrors").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();

        EmailRequest code = assertSingleUnusedResetCode();
        assertThat(body).doesNotContain(technicalDetail, code.getCode());
    }

    private EmailRequest assertSingleUnusedResetCode() {
        List<EmailRequest> codes = emailRequestRepository.findAll();
        assertThat(codes).hasSize(1);
        assertThat(codes.get(0).getType()).isEqualTo(EmailRequestType.PASSWORD_RESET);
        assertThat(codes.get(0).isUsed()).isFalse();
        return codes.get(0);
    }

    private String saveEmailRequest(User user, String code, EmailRequestType type) {
        emailRequestRepository.save(EmailRequest.builder()
                .code(code)
                .type(type)
                .used(false)
                .user(user)
                .build());
        return code;
    }

    // Временный CHECK ... NOT VALID не проверяет существующие строки, но отклоняет UPDATE одной из таблиц при commit активации.
    private void assertActivationRolledBack(String table, String check) throws Exception {
        User user = createUser("rollback-confirm@test.local", PASSWORD, false, false);
        String code = saveEmailRequest(user, "rollback-confirm-code", EmailRequestType.ACTIVATE);

        jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT activation_rollback_check CHECK (" + check + ") NOT VALID");
        try {
            perform(get("/api/v1/auth/register/confirm").param("code", code))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("DATABASE_CONSTRAINT_VIOLATION"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT activation_rollback_check");
        }

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(emailRequestRepository.findByCode(code).orElseThrow().isUsed()).isFalse();
    }

    // Синтаксически корректный email: @Email ограничивает local-part 64 символами, а метку домена — 63.
    private String emailOfLength(int length) {
        String localPart = "a".repeat(64);
        String domainSuffix = ".local";
        return localPart + "@" + "b".repeat(length - localPart.length() - 1 - domainSuffix.length()) + domainSuffix;
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
