package ru.tbcarus.photocloudserver.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import ru.tbcarus.photocloudserver.model.EmailContext;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    @Value("${spring.mail.mail-from}")
    private String mailFrom;

    public static final String CONFIRMATION_EMAIL_TEMPLATE = "email/confirmationTemplate";
    public static final String PASSWORD_RESET_EMAIL_TEMPLATE = "email/passwordResetTemplate";

    public void sendEmail(EmailRequest emailRequest) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
        Context context = new Context();
        EmailContext email = getEmailContext(emailRequest);

        context.setVariables(email.getContext());
        helper.setFrom(email.getFrom());
        helper.setTo(email.getTo());
        helper.setSubject(email.getSubject());
        String html = templateEngine.process(email.getTemplate(), context);
        helper.setText(html, true);

        log.info("Sending email: {} with html body: {}", email, html);
        mailSender.send(message);
    }

    public EmailContext getEmailContext(EmailRequest emailRequest) {
        EmailContext email = new EmailContext();
        email.setTo(emailRequest.getUser().getEmail());
        email.setSubject(emailRequest.getType().getTitle());
        email.setFromDisplayName("FROM_DISPLAY_NAME");
        email.setDisplayName("DISPLAY_NAME");
        email.setFrom(mailFrom);

        if (EmailRequestType.ACTIVATE.equals(emailRequest.getType())) {
            email.setTemplate(CONFIRMATION_EMAIL_TEMPLATE);
        }
        if (EmailRequestType.PASSWORD_RESET.equals(emailRequest.getType())) {
            email.setTemplate(PASSWORD_RESET_EMAIL_TEMPLATE);
        }

        Map<String, Object> map = new HashMap<>();
        map.put("name", emailRequest.getUser().getFirstName());
        map.put("emailRequest", emailRequest);
        ServletRequestAttributes servletRequestAttributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = Objects.requireNonNull(servletRequestAttributes).getRequest();
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder
                .append(request.getScheme()).append("://")
                .append(request.getServerName())
                .append(request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort())
                .append(request.getContextPath())
                .append("/register/")
                .append(emailRequest.getType().name())
                .append("?email=").append(emailRequest.getUser().getEmail())
                .append("&code=").append(emailRequest.getCode());
        String link = urlBuilder.toString();

        map.put("link", link);
        map.put("mailTo", mailFrom);
        email.setContext(map);

        return email;
    }
}
