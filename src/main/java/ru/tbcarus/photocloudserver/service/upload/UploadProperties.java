package ru.tbcarus.photocloudserver.service.upload;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "upload")
@Validated
@Getter
@Setter
public class UploadProperties {
    @Min(1) private long maxFileSizeBytes;
    @Min(1) private long maxChunkSizeBytes;
    @Min(1) private long maxReservedBytes;
    @Min(1) private int maxConcurrentChunks;
    @NotNull private Duration inactivityTtl;
    @NotNull private Duration resultTtl;
    @NotNull private Duration maintenanceInterval;

    @jakarta.annotation.PostConstruct
    void validate() {
        if (maxReservedBytes < maxFileSizeBytes || maxChunkSizeBytes > maxFileSizeBytes
                || inactivityTtl.isNegative() || inactivityTtl.isZero()
                || resultTtl.isNegative() || resultTtl.isZero()
                || maintenanceInterval.isNegative() || maintenanceInterval.isZero()) {
            throw new IllegalArgumentException("Invalid upload limits or durations");
        }
    }
}
