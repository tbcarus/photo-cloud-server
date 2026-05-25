package ru.tbcarus.photocloudserver.service.sync;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sync.checksum-exists")
public class ChecksumExistsProperties {
    private int maxBatchSize = 500;
}
