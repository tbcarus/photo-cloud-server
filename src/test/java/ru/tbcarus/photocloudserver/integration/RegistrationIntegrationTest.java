package ru.tbcarus.photocloudserver.integration;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.thymeleaf.context.IContext;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import ru.tbcarus.photocloudserver.exception.RegistrationLimitExceededException;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.Role;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.service.EmailRequestService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Регистрация и лимит N (auth.request.active-max = 3 из application.yml); письма уходят в mock JavaMailSender.
class RegistrationIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "registration@test.local";

    @MockitoBean
    private JavaMailSender mailSender;

    @MockitoSpyBean
    private SpringTemplateEngine templateEngine;

    @Autowired
    private EmailRequestService emailRequestService;

    @Value("${auth.request.activation-valid-days}")
    private int activationValidDays;

    @BeforeEach
    void stubMailSender() {
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
    }

    @Test
    void firstRegistrationCreatesDisabledUserWithOneActivationCodeAndSendsEmailAfterCommit() throws Exception {
        AtomicBoolean transactionActiveAtSend = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transactionActiveAtSend.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        register(EMAIL, "pass1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Email was sent"));

        assertThat(userRepository.count()).isEqualTo(1);
        User user = user();
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.isBanned()).isFalse();
        assertThat(user.getRoles()).containsExactly(Role.USER);
        assertThat(passwordEncoder.matches("pass1", user.getPassword())).isTrue();
        List<EmailRequest> codes = codes();
        assertThat(codes).hasSize(1);
        assertThat(codes.get(0).getType()).isEqualTo(EmailRequestType.ACTIVATE);
        assertThat(codes.get(0).isUsed()).isFalse();
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        assertThat(transactionActiveAtSend).isFalse();
    }

    @Test
    void repeatedRegistrationReplacesPasswordAndInvalidatesPreviousCode() throws Exception {
        register(EMAIL, "pass1").andExpect(status().isCreated());
        String oldCode = codes().get(0).getCode();

        register(EMAIL, "pass2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Email was sent"));

        assertThat(userRepository.count()).isEqualTo(1);
        User user = user();
        assertThat(user.isEnabled()).isFalse();
        assertThat(passwordEncoder.matches("pass2", user.getPassword())).isTrue();
        assertThat(passwordEncoder.matches("pass1", user.getPassword())).isFalse();
        List<EmailRequest> codes = codes();
        assertThat(codes).extracting(EmailRequest::getCode).first().isEqualTo(oldCode);
        assertThat(codes).extracting(EmailRequest::isUsed).containsExactly(true, false);
        verify(mailSender, times(2)).send(any(MimeMessage.class));

        confirm(oldCode).andExpect(status().isBadRequest());
        confirm(codes.get(1).getCode()).andExpect(status().isOk());
        loginAndGetAccessToken(EMAIL, "pass2");
        login(EMAIL, "pass1").andExpect(status().isUnauthorized());
    }

    @Test
    void activatedAccountGetsConflictWithoutChanges() throws Exception {
        User user = createUser(EMAIL, "pass1");
        addActivationCode(user, LocalDateTime.now().minusHours(1), true);
        List<String> codesBefore = codeStates();

        register(EMAIL, "pass2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.fieldErrors").value(nullValue()))
                .andExpect(jsonPath("$.message").value("Account with this email is already activated. Please log in"));

        User unchanged = user();
        assertThat(unchanged.getPassword()).isEqualTo(user.getPassword());
        assertThat(unchanged.isEnabled()).isTrue();
        assertThat(codeStates()).isEqualTo(codesBefore);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void fourthRegistrationWithinWindowIsRejectedAndChangesNothing() throws Exception {
        // Первое письмо не отправлено (503), но User и код уже зафиксированы.
        doThrow(new MailSendException("SMTP unavailable")).doNothing().when(mailSender).send(any(MimeMessage.class));
        register(EMAIL, "pass1")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_SENT"));
        assertThat(codes()).hasSize(1);

        register(EMAIL, "pass2").andExpect(status().isCreated());
        register(EMAIL, "pass3").andExpect(status().isCreated());
        String passwordBefore = user().getPassword();
        List<String> codesBefore = codeStates();

        MvcResult result = register(EMAIL, "pass4")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").value(containsString("my-cld@yandex.ru")))
                .andReturn();

        // Код первой регистрации (письмо не ушло) и аннулированные коды учитываются в лимите.
        long window = Duration.ofDays(activationValidDays).toSeconds();
        assertThat(retryAfter(result)).isBetween(window - 60, window);
        assertThat(user().getPassword()).isEqualTo(passwordBefore);
        assertThat(codeStates()).isEqualTo(codesBefore);
        assertThat(codes()).extracting(EmailRequest::isUsed).containsExactly(true, true, false);
        verify(mailSender, times(3)).send(any(MimeMessage.class));
    }

    @Test
    void registrationReturns503WhenSmtpRejectsMessage() throws Exception {
        doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(any(MimeMessage.class));

        assertRegistrationEmailNotSent("SMTP unavailable");
    }

    @Test
    void registrationReturns503WhenSmtpAuthenticationFails() throws Exception {
        doThrow(new MailAuthenticationException("535 authentication failed")).when(mailSender).send(any(MimeMessage.class));

        assertRegistrationEmailNotSent("535 authentication failed");
    }

    @Test
    void registrationReturns503WhenMessageCannotBeBuilt() throws Exception {
        // MessagingException без причины: прежний catch падал на getCause() с NPE.
        MimeMessage message = spy(new MimeMessage((Session) null));
        doThrow(new MessagingException("Subject rejected")).when(message).setSubject(anyString(), anyString());
        when(mailSender.createMimeMessage()).thenReturn(message);

        assertRegistrationEmailNotSent("Subject rejected");
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void registrationReturns503WhenTemplateFails() throws Exception {
        doThrow(new TemplateProcessingException("Template broken")).when(templateEngine).process(anyString(), any(IContext.class));

        assertRegistrationEmailNotSent("Template broken");
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void retryAfterPointsToExpiryOfOldestCodeAndRegistrationReopensWhenItLeavesWindow() throws Exception {
        User user = createUser(EMAIL, "pass1", false, false);
        LocalDateTime now = LocalDateTime.now();
        EmailRequest oldest = addActivationCode(user, now.minusDays(activationValidDays).plusSeconds(100), true);
        addActivationCode(user, now.minusHours(2), true);
        addActivationCode(user, now.minusHours(1), false);

        MvcResult result = register(EMAIL, "pass2")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(containsString("Try again in 2 min")))
                .andReturn();
        assertThat(retryAfter(result)).isBetween(99L, 100L);

        // Старейший код вышел из окна: в нём осталось N - 1 кодов, повторная регистрация снова разрешена.
        setCreatedAt(oldest, now.minusDays(activationValidDays).minusSeconds(1));
        register(EMAIL, "pass2").andExpect(status().isCreated());

        assertThat(passwordEncoder.matches("pass2", user().getPassword())).isTrue();
        assertThat(codes()).extracting(EmailRequest::isUsed).containsExactly(true, true, true, false);
    }

    @Test
    void activationCodeLeavesLimitWindowExactlyWhenItExpires() {
        User user = createUser(EMAIL, "pass1", false, false);
        LocalDateTime now = LocalDateTime.now();
        EmailRequest oldest = addActivationCode(user, now.minusDays(1), true);
        addActivationCode(user, now.minusHours(2), true);
        addActivationCode(user, now.minusHours(1), false);
        // createdAt в том виде, в каком его хранит PostgreSQL; как и в EmailRequest.isActive, код в момент createdAt + срок уже истёк.
        LocalDateTime expiresAt = emailRequestRepository.findByCode(oldest.getCode()).orElseThrow()
                .getCreatedAt().plusDays(activationValidDays);

        assertThatThrownBy(() -> emailRequestService.checkActivationLimit(user, expiresAt.minusNanos(1_000)))
                .isInstanceOfSatisfying(RegistrationLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1));
        assertThatCode(() -> emailRequestService.checkActivationLimit(user, expiresAt)).doesNotThrowAnyException();
    }

    @Test
    void firstRegistrationRollsBackUserWhenCodeInsertFails() throws Exception {
        registerWithFailingConstraint("email_requests", "false", "pass1");

        assertThat(userRepository.count()).isZero();
        assertThat(codes()).isEmpty();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void repeatedRegistrationRollsBackNewCodeWhenPasswordUpdateFails() throws Exception {
        register(EMAIL, "pass1").andExpect(status().isCreated());
        String passwordBefore = user().getPassword();
        List<String> codesBefore = codeStates();

        registerWithFailingConstraint("users", "password = '" + passwordBefore + "'", "pass2");

        assertThat(user().getPassword()).isEqualTo(passwordBefore);
        assertThat(codeStates()).isEqualTo(codesBefore);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    // 503 в стандартном ErrorResponse без технических подробностей; User и ACTIVATE-код остаются в БД.
    private void assertRegistrationEmailNotSent(String technicalDetail) throws Exception {
        String body = register(EMAIL, "pass1")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_SENT"))
                .andExpect(jsonPath("$.message").value("Registration data was saved, but the verification email "
                        + "could not be sent. Please try again later or contact my-cld@yandex.ru."))
                .andExpect(jsonPath("$.fieldErrors").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        assertThat(user().isEnabled()).isFalse();
        List<EmailRequest> codes = codes();
        assertThat(codes).hasSize(1);
        assertThat(codes.get(0).isUsed()).isFalse();
        assertThat(body).doesNotContain(technicalDetail, codes.get(0).getCode(), "pass1");
    }

    // Временный CHECK ... NOT VALID не проверяет существующие строки, но отклоняет запись регистрации в выбранной таблице.
    private void registerWithFailingConstraint(String table, String check, String password) throws Exception {
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT registration_rollback_check CHECK (" + check + ") NOT VALID");
        try {
            register(EMAIL, password)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("DATABASE_CONSTRAINT_VIOLATION"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT registration_rollback_check");
        }
    }

    private ResultActions register(String email, String password) throws Exception {
        return perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions confirm(String code) throws Exception {
        return perform(get("/api/v1/auth/register/confirm").param("code", code));
    }

    private ResultActions login(String email, String password) throws Exception {
        return perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private User user() {
        return userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
    }

    private List<EmailRequest> codes() {
        return emailRequestRepository.findAll(Sort.by("id"));
    }

    private List<String> codeStates() {
        return codes().stream().map(code -> code.getCode() + ":" + code.isUsed()).toList();
    }

    private EmailRequest addActivationCode(User user, LocalDateTime createdAt, boolean used) {
        EmailRequest code = emailRequestRepository.save(EmailRequest.builder()
                .code(UUID.randomUUID().toString())
                .type(EmailRequestType.ACTIVATE)
                .used(used)
                .user(user)
                .build());
        setCreatedAt(code, createdAt);
        return code;
    }

    private void setCreatedAt(EmailRequest code, LocalDateTime createdAt) {
        jdbcTemplate.update("UPDATE email_requests SET created_at = ? WHERE id = ?", createdAt, code.getId());
    }

    private long retryAfter(MvcResult result) {
        return Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
    }
}
