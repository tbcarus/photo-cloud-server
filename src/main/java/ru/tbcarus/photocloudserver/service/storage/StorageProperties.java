package ru.tbcarus.photocloudserver.service.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private String root;
    private PhysicalFilename physicalFilename = new PhysicalFilename();

    @Getter
    @Setter
    public static class PhysicalFilename {
        private int originalNameMaxLength = 80;
    }
}
