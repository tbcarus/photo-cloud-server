package ru.tbcarus.photocloudserver.service.link;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// Значения задаются в application.yml из переменных окружения; пустое значение — LINK-ссылки отключены.
// Проверку формата выполняет LinkTokenService при запуске.
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sharing")
public class LinkSharingProperties {
    private String publicBaseUrl;
    private String linkKey;
}
