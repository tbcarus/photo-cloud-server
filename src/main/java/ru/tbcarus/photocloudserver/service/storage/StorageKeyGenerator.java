package ru.tbcarus.photocloudserver.service.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StorageKeyGenerator {

    private final FilenameSanitizer filenameSanitizer;

    public String generate(Long userId, String checksum, String originalFilename, String mimeType) {
        String firstShard = checksum.substring(0, 2);
        String secondShard = checksum.substring(2, 4);
        String storageFilename = filenameSanitizer.buildStorageFilename(originalFilename, mimeType, UUID.randomUUID().toString());
        return "users/%d/objects/%s/%s/%s".formatted(userId, firstShard, secondShard, storageFilename);
    }
}
