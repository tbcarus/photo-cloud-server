package ru.tbcarus.photocloudserver.service;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.tbcarus.photocloudserver.controller.RegisterController;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmationEmailTemplateTest {

    private static final String CODE = UUID.randomUUID().toString();

    // Настоящий шаблон через автоконфигурацию Thymeleaf и application.yml; письмо перехватывается mock JavaMailSender.
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("SMTP_USERNAME=test")
            .withConfiguration(AutoConfigurations.of(ThymeleafAutoConfiguration.class))
            .withBean(JavaMailSender.class, () -> mailSender)
            .withUserConfiguration(EmailService.class);

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void linkIsClickableAndFullUrlIsVisible() {
        contextRunner.run(context -> {
            String html = renderActivationEmail(context, "/ctx");
            String link = "http://mail.example.com:8080/ctx" + RegisterController.BASE_URL + RegisterController.CONFIRM_URL
                    + "?code=" + CODE;

            assertThat(html).contains("<a href=\"" + link + "\">" + link + "</a>");
            // Служебные комментарии шаблона вырезаются при рендеринге и не попадают в письмо.
            assertThat(html).doesNotContain("Текст ссылки", "Склонение");
            assertThat(html).contains(
                    "Для активации аккаунта в приложении необходимо подтвердить свою почту, пройдя по ссылке:",
                    "действует только ссылка из последнего письма",
                    "Если вы не запрашивали регистрацию, просто проигнорируйте это письмо");
        });
    }

    @Test
    void linkIsEscapedInHrefAndText() {
        contextRunner.run(context -> {
            String html = renderActivationEmail(context, "/a&b\"c<d");
            String escapedLink = "http://mail.example.com:8080/a&amp;b&quot;c&lt;d" + RegisterController.BASE_URL
                    + RegisterController.CONFIRM_URL + "?code=" + CODE;

            // Спецсимволы не разрывают атрибут и разметку: браузер восстановит из сущностей исходный URL.
            assertThat(html).contains("<a href=\"" + escapedLink + "\">" + escapedLink + "</a>");
            assertThat(html).doesNotContain("a&b", "b\"c", "c<d");
        });
    }

    @Test
    void validityPeriodIsTakenFromApplicationYml() {
        contextRunner.run(context -> {
            int days = context.getEnvironment().getRequiredProperty("auth.request.activation-valid-days", Integer.class);
            String html = renderActivationEmail(context, "");

            assertThat(html).contains("Ссылка действительна в течение <span>" + days + " " + daysUnit(days) + "</span>.");
        });
    }

    @ParameterizedTest
    @CsvSource({"1, 1 день", "2, 2 дня", "4, 4 дня", "5, 5 дней", "11, 11 дней", "14, 14 дней", "21, 21 день", "22, 22 дня"})
    void validityPeriodFollowsConfiguredValue(int days, String expected) {
        contextRunner.withPropertyValues("auth.request.activation-valid-days=" + days).run(context ->
                assertThat(renderActivationEmail(context, ""))
                        .contains("Ссылка действительна в течение <span>" + expected + "</span>."));
    }

    private String renderActivationEmail(AssertableApplicationContext context, String contextPath) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("mail.example.com");
        request.setServerPort(8080);
        request.setContextPath(contextPath);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));

        User user = User.builder().id(1L).email("user@test.local").displayName("Тест").build();
        EmailRequest emailRequest = EmailRequest.builder().code(CODE).type(EmailRequestType.ACTIVATE).user(user).build();
        context.getBean(EmailService.class).sendEmail(emailRequest);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        // Заголовки Content-Type частей выставляются при saveChanges(), как перед реальной отправкой.
        message.saveChanges();
        return findHtml(message);
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

    private static String daysUnit(int days) {
        if (days % 10 == 1 && days % 100 != 11) {
            return "день";
        }
        if (days % 10 >= 2 && days % 10 <= 4 && (days % 100 < 12 || days % 100 > 14)) {
            return "дня";
        }
        return "дней";
    }
}
