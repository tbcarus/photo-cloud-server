package ru.tbcarus.photocloudserver.service.thumbnail;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// Значения по умолчанию заданы в application.yml; без них запуск завершается ошибкой валидации.
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "thumbnail")
public class ThumbnailProperties {
    @Positive
    private int maxSide;
    @Positive
    private long maxSourcePixels;
    @Positive
    private long maxFullDecodePixels;
    @Positive
    private int maxConcurrent;
    @NotNull
    private Duration cacheMaxAge;
}
