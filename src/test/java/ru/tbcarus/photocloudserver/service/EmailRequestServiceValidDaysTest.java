package ru.tbcarus.photocloudserver.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.tbcarus.photocloudserver.exception.BadRegistrationRequest;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.EmailRequestRepository;
import ru.tbcarus.photocloudserver.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.tbcarus.photocloudserver.model.EmailRequestType.ACTIVATE;
import static ru.tbcarus.photocloudserver.model.EmailRequestType.PASSWORD_RESET;

class EmailRequestServiceValidDaysTest {

    private static final String ACTIVATION_VALID_DAYS = "auth.request.activation-valid-days";
    private static final String RESET_VALID_DAYS = "auth.request.reset-valid-days";

    // Контекст Spring без БД: проверяется внедрение сроков из application.yml.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(EmailRequestRepository.class, () -> mock(EmailRequestRepository.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .withUserConfiguration(EmailRequestService.class);

    @Test
    void defaultValidDaysIsThreeDaysForAllCodeTypes() {
        contextRunner.run(context -> {
            EmailRequestService service = context.getBean(EmailRequestService.class);
            LocalDateTime now = LocalDateTime.now();
            for (EmailRequestType type : EmailRequestType.values()) {
                assertValid(service, type, now);
                assertValid(service, type, now.minusDays(3).plusHours(1));
                assertExpired(service, type, now.minusDays(3).minusMinutes(1));
            }
        });
    }

    @Test
    void activationValidDaysDoesNotAffectResetCodes() {
        contextRunner.withPropertyValues(ACTIVATION_VALID_DAYS + "=1").run(context -> {
            EmailRequestService service = context.getBean(EmailRequestService.class);
            LocalDateTime now = LocalDateTime.now();
            assertValid(service, ACTIVATE, now.minusHours(23));
            assertExpired(service, ACTIVATE, now.minusHours(25));
            assertValid(service, PASSWORD_RESET, now.minusDays(3).plusHours(1));
            assertExpired(service, PASSWORD_RESET, now.minusDays(3).minusMinutes(1));
        });
    }

    @Test
    void resetValidDaysDoesNotAffectActivationCodes() {
        contextRunner.withPropertyValues(RESET_VALID_DAYS + "=1").run(context -> {
            EmailRequestService service = context.getBean(EmailRequestService.class);
            LocalDateTime now = LocalDateTime.now();
            assertValid(service, PASSWORD_RESET, now.minusHours(23));
            assertExpired(service, PASSWORD_RESET, now.minusHours(25));
            assertValid(service, ACTIVATE, now.minusDays(3).plusHours(1));
            assertExpired(service, ACTIVATE, now.minusDays(3).minusMinutes(1));
        });
    }

    @Test
    void passwordResetInvalidatesResetCodesWithinResetValidDays() {
        contextRunner.withPropertyValues(ACTIVATION_VALID_DAYS + "=1", RESET_VALID_DAYS + "=5").run(context -> {
            EmailRequestService service = context.getBean(EmailRequestService.class);
            EmailRequestRepository emailRequestRepository = context.getBean(EmailRequestRepository.class);
            User user = User.builder().id(1L).build();
            EmailRequest code = EmailRequest.builder()
                    .code("reset-code")
                    .type(PASSWORD_RESET)
                    .user(user)
                    .createdAt(LocalDateTime.now())
                    .build();
            EmailRequest previousCode = EmailRequest.builder().type(PASSWORD_RESET).user(user).build();
            when(emailRequestRepository.findByCode("reset-code")).thenReturn(Optional.of(code));
            when(context.getBean(UserRepository.class).findById(1L)).thenReturn(Optional.of(user));
            when(emailRequestRepository.findAllByUserIdAndCreatedAtBetweenAndTypeOrderByCreatedAtDesc(
                    eq(1L), any(), any(), eq(PASSWORD_RESET))).thenReturn(List.of(previousCode));

            LocalDateTime before = LocalDateTime.now();
            service.resetPassword("newpass", "reset-code");
            LocalDateTime after = LocalDateTime.now();

            ArgumentCaptor<LocalDateTime> windowStart = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(emailRequestRepository).findAllByUserIdAndCreatedAtBetweenAndTypeOrderByCreatedAtDesc(
                    eq(1L), windowStart.capture(), any(), eq(PASSWORD_RESET));
            assertThat(windowStart.getValue()).isBetween(before.minusDays(5), after.minusDays(5));
            assertThat(code.isUsed()).isTrue();
            assertThat(previousCode.isUsed()).isTrue();
        });
    }

    @Test
    void validDaysBelowOneFailsStartup() {
        for (String property : List.of(ACTIVATION_VALID_DAYS, RESET_VALID_DAYS)) {
            for (int value : new int[]{0, -1}) {
                contextRunner.withPropertyValues(property + "=" + value).run(context ->
                        assertThat(context).getFailure()
                                .rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining(property + "=" + value));
            }
        }
    }

    private static void assertValid(EmailRequestService service, EmailRequestType type, LocalDateTime createdAt) {
        assertThatCode(() -> service.checkEmailRequest(code(type, createdAt), type)).doesNotThrowAnyException();
    }

    private static void assertExpired(EmailRequestService service, EmailRequestType type, LocalDateTime createdAt) {
        assertThatThrownBy(() -> service.checkEmailRequest(code(type, createdAt), type))
                .isInstanceOf(BadRegistrationRequest.class);
    }

    private static EmailRequest code(EmailRequestType type, LocalDateTime createdAt) {
        return EmailRequest.builder()
                .type(type)
                .used(false)
                .createdAt(createdAt)
                .build();
    }
}
