package ru.tbcarus.photocloudserver.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.tbcarus.photocloudserver.exception.BadRegistrationRequest;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.repository.EmailRequestRepository;
import ru.tbcarus.photocloudserver.repository.UserRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EmailRequestServiceValidDaysTest {

    // Контекст Spring без БД: проверяется внедрение auth.request.valid-days из application.yml.
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
    void configuredValidDaysChangesLifetimeOfAllCodeTypes() {
        contextRunner.withPropertyValues("auth.request.valid-days=1").run(context -> {
            EmailRequestService service = context.getBean(EmailRequestService.class);
            LocalDateTime now = LocalDateTime.now();
            for (EmailRequestType type : EmailRequestType.values()) {
                assertValid(service, type, now.minusHours(23));
                assertExpired(service, type, now.minusHours(25));
            }
        });
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
