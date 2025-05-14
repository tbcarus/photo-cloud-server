package ru.tbcarus.photocloudserver.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.tbcarus.photocloudserver.model.EmailContext;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class EmailContextUtil {

    public static final String CONFIRMATION_EMAIL_TEMPLATE = "email/confirmationTemplate";
    public static final String PASSWORD_RESET_EMAIL_TEMPLATE = "email/passwordResetTemplate";

    public static EmailContext getEmailContext(EmailRequest emailRequest) {
        EmailContext email = new EmailContext();
        email.setTo(emailRequest.getUser().getEmail());
        email.setSubject(emailRequest.getType().getTitle());
        email.setFromDisplayName("FROM_DISPLAY_NAME");
        email.setDisplayName("DISPLAY_NAME");

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
        email.setContext(map);

        return email;
    }
}
