package ru.tbcarus.photocloudserver.service.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StorageKeyGenerator {

    private final FilenameSanitizer filenameSanitizer;
    private final StorageProperties storageProperties;

    public String generateFilePath(Long userId, String checksum) {
        String firstShard = checksum.substring(0, 2);
        String secondShard = checksum.substring(2, 4);
        return "users/%d/objects/%s/%s".formatted(userId, firstShard, secondShard);
    }

    public String generateFilename(String originalFilename, String mimeType) {
        return filenameSanitizer.buildPhysicalFilename(
                originalFilename,
                mimeType,
                UUID.randomUUID().toString(),
                storageProperties.getPhysicalFilename().getOriginalNameMaxLength()
        );
    }
}
