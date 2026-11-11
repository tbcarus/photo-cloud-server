package ru.tbcarus.photocloudserver.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.LoginResponse;
import ru.tbcarus.photocloudserver.service.EmailRequestService;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Страница восстановления пароля из письма: форма без JWT, отправка формы выполняет ту же операцию, что JSON
 * reset/confirm. Запросы с секретами выполняются через mockMvc напрямую: perform() печатает тела в проверяемый вывод.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetPageIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "reset-page@test.local";
    private static final String OLD_PASSWORD = "old-Pa55";
    private static final String NEW_PASSWORD = "new-Pa55-secret";
    private static final String PAGE = "/api/v1/auth/password/reset/page";

    @MockitoBean
    private JavaMailSender mailSender;

    @MockitoSpyBean
    private EmailRequestService emailRequestService;

    private final Logger appLogger = (Logger) LoggerFactory.getLogger("ru.tbcarus.photocloudserver");
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
        // application-test.yml понижает уровень до WARN; журнал запросов и писем пишется на INFO.
        previousLevel = appLogger.getLevel();
        appLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void restoreLogging() {
        appLogger.setLevel(previousLevel);
    }

    @Test
    void linkFromEmailOpensFormAndNewPasswordWorksForLogin(CapturedOutput output) throws Exception {
        createUser(EMAIL, OLD_PASSWORD);
        LoginResponse session = login(OLD_PASSWORD);

        mockMvc.perform(post("/api/v1/auth/password/reset/request").param("email", EMAIL));
        URI link = URI.create(linkFromEmail());
        String code = link.getQuery().substring("code=".length());
        assertThat(link.getPath()).isEqualTo(PAGE);

        // Без Authorization: страница публичная.
        MockHttpServletResponse form = mockMvc.perform(get(link)).andReturn().getResponse();
        assertThat(form.getStatus()).isEqualTo(200);
        assertPageHeaders(form);
        String html = form.getContentAsString();
        assertThat(html).contains("Новый пароль", "Повторите пароль", "Сохранить пароль",
                "<form id=\"reset-form\" method=\"post\" action=\"page\">",
                "<input type=\"hidden\" name=\"code\" value=\"" + code + "\">",
                "type=\"password\"", "minlength=\"4\"", "maxlength=\"20\"");
        assertNoExternalResources(html);

        MockHttpServletResponse result = submit(code, NEW_PASSWORD, NEW_PASSWORD);
        assertThat(result.getStatus()).isEqualTo(200);
        assertPageHeaders(result);
        assertThat(result.getContentAsString()).contains("Пароль успешно изменён").doesNotContain(NEW_PASSWORD, code);

        assertThat(loginStatus(NEW_PASSWORD)).isEqualTo(200);
        assertThat(loginStatus(OLD_PASSWORD)).isEqualTo(401);
        // Q-04: прежние refresh отозваны, выданный access действует до TTL.
        assertThat(refreshStatus(session.refreshToken())).isEqualTo(403);
        assertThat(profileStatus(session.accessToken())).isEqualTo(200);
        // Ни код, ни пароль не попадают в журнал.
        assertThat(output).contains("GET /api/v1/auth/password/reset/page -> 200", "POST /api/v1/auth/password/reset/page -> 200");
        assertThat(output).doesNotContain(code, NEW_PASSWORD, OLD_PASSWORD);
    }

    @Test
    void repeatedSubmitAfterSuccessShowsInvalidLinkAndKeepsPassword() throws Exception {
        User user = createUser(EMAIL, OLD_PASSWORD);
        String code = resetCode(user);
        assertThat(submit(code, NEW_PASSWORD, NEW_PASSWORD).getStatus()).isEqualTo(200);
        String hash = passwordHash();
        LoginResponse session = login(NEW_PASSWORD);

        MockHttpServletResponse repeated = submit(code, "another-pass", "another-pass");

        assertInvalidLinkPage(repeated);
        assertThat(passwordHash()).isEqualTo(hash);
        assertThat(refreshStatus(session.refreshToken())).isEqualTo(200);
        assertThat(mockMvc.perform(get(PAGE).param("code", code)).andReturn().getResponse().getContentAsString())
                .contains("Ссылка недействительна").doesNotContain("<form");
    }

    @Test
    void mismatchedPasswordsShowFormAgainAndChangeNothing() throws Exception {
        User user = createUser(EMAIL, OLD_PASSWORD);
        String code = resetCode(user);
        LoginResponse session = login(OLD_PASSWORD);
        String hash = passwordHash();

        MockHttpServletResponse response = submit(code, NEW_PASSWORD, NEW_PASSWORD + "x");

        assertThat(response.getStatus()).isEqualTo(400);
        assertPageHeaders(response);
        String html = response.getContentAsString();
        assertThat(html).contains("Пароли не совпадают", "<form id=\"reset-form\"",
                "<input type=\"hidden\" name=\"code\" value=\"" + code + "\">");
        assertThat(html).doesNotContain(NEW_PASSWORD);
        assertNothingChanged(code, hash, session);
        // Без этой проверки в браузере запрос не отправляется: скрипт сравнивает поля до отправки формы.
        assertThat(html).contains("form.elements.password.value !== form.elements.passwordConfirm.value");
    }

    @Test
    void passwordRejectedByExistingRulesShowsFormAgainAndChangesNothing() throws Exception {
        User user = createUser(EMAIL, OLD_PASSWORD);
        String code = resetCode(user);
        LoginResponse session = login(OLD_PASSWORD);
        String hash = passwordHash();

        for (String password : new String[]{"abc", "abcdefghijklmnopqrstu", "    "}) {
            MockHttpServletResponse response = submit(code, password, password);

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString())
                    .contains("Пароль должен содержать от 4 до 20 символов", "<form id=\"reset-form\"");
        }
        assertNothingChanged(code, hash, session);
    }

    @Test
    void unknownExpiredAndUsedCodesShowInvalidLinkAndChangeNothing() throws Exception {
        User user = createUser(EMAIL, OLD_PASSWORD);
        LoginResponse session = login(OLD_PASSWORD);
        String hash = passwordHash();
        String expired = resetCode(user);
        jdbcTemplate.update("UPDATE email_requests SET created_at = ? WHERE code = ?", LocalDateTime.now().minusDays(30), expired);
        String used = resetCode(user);
        jdbcTemplate.update("UPDATE email_requests SET used = true WHERE code = ?", used);
        String activation = emailRequestService.generateEmailRequest(user, EmailRequestType.ACTIVATE).getCode();

        for (String code : new String[]{"unknown-code", expired, used, activation}) {
            assertInvalidLinkPage(mockMvc.perform(get(PAGE).param("code", code)).andReturn().getResponse());
            assertInvalidLinkPage(submit(code, NEW_PASSWORD, NEW_PASSWORD));
        }
        assertInvalidLinkPage(mockMvc.perform(get(PAGE)).andReturn().getResponse());
        assertInvalidLinkPage(mockMvc.perform(post(PAGE).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("password", NEW_PASSWORD).param("passwordConfirm", NEW_PASSWORD)).andReturn().getResponse());

        assertThat(passwordHash()).isEqualTo(hash);
        assertThat(refreshStatus(session.refreshToken())).isEqualTo(200);
        assertThat(loginStatus(OLD_PASSWORD)).isEqualTo(200);
    }

    @Test
    void codeFromRequestIsEscapedAndNotEchoedOnInvalidLink() throws Exception {
        String injected = "\"><script>alert(1)</script>";

        String invalid = mockMvc.perform(get(PAGE).param("code", injected)).andReturn().getResponse().getContentAsString();
        assertThat(invalid).doesNotContain("alert(1)");

        // Форма с ошибкой возвращает код только экранированным в hidden-поле.
        String form = submit(injected, "pass1", "pass2").getContentAsString();
        assertThat(form).doesNotContain(injected)
                .contains("value=\"&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\"");
    }

    @Test
    void unexpectedFailureShowsGenericPageWithoutDetails() throws Exception {
        User user = createUser(EMAIL, OLD_PASSWORD);
        String code = resetCode(user);
        String hash = passwordHash();
        doThrow(new IllegalStateException("internal detail")).when(emailRequestService).resetPassword(anyString(), anyString());

        MockHttpServletResponse response = submit(code, NEW_PASSWORD, NEW_PASSWORD);

        assertThat(response.getStatus()).isEqualTo(500);
        assertPageHeaders(response);
        assertThat(response.getContentAsString()).contains("Не удалось изменить пароль")
                .doesNotContain("internal detail", "IllegalStateException", code, NEW_PASSWORD);
        assertThat(passwordHash()).isEqualTo(hash);
    }

    private void assertNothingChanged(String code, String hash, LoginResponse session) throws Exception {
        assertThat(passwordHash()).isEqualTo(hash);
        assertThat(jdbcTemplate.queryForObject("SELECT used FROM email_requests WHERE code = ?", Boolean.class, code)).isFalse();
        assertThat(refreshStatus(session.refreshToken())).isEqualTo(200);
        assertThat(loginStatus(OLD_PASSWORD)).isEqualTo(200);
    }

    private void assertInvalidLinkPage(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(400);
        assertPageHeaders(response);
        assertThat(response.getContentAsString()).contains("Ссылка недействительна").doesNotContain("<form", NEW_PASSWORD);
    }

    private static void assertPageHeaders(MockHttpServletResponse response) {
        assertThat(response.getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy"))
                .startsWith("default-src 'none'; script-src 'nonce-")
                .contains("form-action 'self'", "base-uri 'none'");
    }

    // Страница не загружает внешних ресурсов: код из адреса не уходит сторонним серверам.
    private static void assertNoExternalResources(String html) {
        assertThat(html).doesNotContain("http://", "https://", "<link", " src=", "<img", "<iframe");
    }

    private MockHttpServletResponse submit(String code, String password, String passwordConfirm) throws Exception {
        return mockMvc.perform(post(PAGE)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code", code)
                        .param("password", password)
                        .param("passwordConfirm", passwordConfirm))
                .andReturn().getResponse();
    }

    private String resetCode(User user) {
        return emailRequestService.generateEmailRequest(user, EmailRequestType.PASSWORD_RESET).getCode();
    }

    private String linkFromEmail() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        Matcher matcher = Pattern.compile("https?://[^\\s<\"]+/reset/page\\?code=[0-9a-f-]+").matcher(findHtml(message));
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    private static String findHtml(Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String html = findHtml(bodyPart);
                if (html != null) {
                    return html;
                }
            }
        }
        return null;
    }

    private String passwordHash() {
        return jdbcTemplate.queryForObject("SELECT password FROM users WHERE email = ?", String.class, EMAIL);
    }

    private LoginResponse login(String password) throws Exception {
        MockHttpServletResponse response = loginCall(password);
        assertThat(response.getStatus()).isEqualTo(200);
        return objectMapper.readValue(response.getContentAsString(), LoginResponse.class);
    }

    private int loginStatus(String password) throws Exception {
        return loginCall(password).getStatus();
    }

    private MockHttpServletResponse loginCall(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse();
    }

    private int refreshStatus(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int profileStatus(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/profile").header(HttpHeaders.AUTHORIZATION, authHeader(accessToken)))
                .andReturn().getResponse().getStatus();
    }
}
