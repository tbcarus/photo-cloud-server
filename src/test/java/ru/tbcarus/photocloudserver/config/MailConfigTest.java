package ru.tbcarus.photocloudserver.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailConfigTest {

    // Контекст без БД: MailConfig и application.yml; SMTP-учётные данные подставляются тестовыми значениями.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("SMTP_USERNAME=test", "SMTP_PASSWORD=test")
            .withUserConfiguration(MailConfig.class);

    @Test
    void javaMailSenderGetsSmtpTimeoutsFromApplicationYml() {
        contextRunner.run(context -> {
            Properties properties = ((JavaMailSenderImpl) context.getBean(JavaMailSender.class)).getJavaMailProperties();
            assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
            assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("5000");
            assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("5000");
            assertThat(properties.getProperty("mail.smtp.quitwait")).isEqualTo("false");
        });
    }

    @Test
    // Без mail.smtp.timeout отправка зависла бы навсегда: тест должен упасть, а не блокировать сборку.
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void readTimeoutAbortsSendToSilentSmtpServer() throws Exception {
        // Сервер принимает TCP-соединения (backlog), но не отвечает: клиент ждёт TLS-ответ до mail.smtp.timeout.
        try (ServerSocket silentServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            contextRunner.withPropertyValues(
                    "spring.mail.host=127.0.0.1",
                    "spring.mail.port=" + silentServer.getLocalPort(),
                    "spring.mail.properties.mail.smtp.timeout=300"
            ).run(context -> {
                JavaMailSender mailSender = context.getBean(JavaMailSender.class);
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom("test@example.com");
                message.setTo("user@example.com");
                message.setSubject("timeout");
                message.setText("timeout");

                long startedAt = System.nanoTime();
                assertThatThrownBy(() -> mailSender.send(message))
                        .isInstanceOf(MailSendException.class)
                        .hasRootCauseInstanceOf(SocketTimeoutException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
            });
        }
    }
}
