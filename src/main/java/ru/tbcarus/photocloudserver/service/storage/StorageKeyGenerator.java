package ru.tbcarus.photocloudserver.service.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StorageKeyGenerator {

    private final FilenameSanitizer filenameSanitizer;
    private final StorageProperties storageProperties;

    // Физический ключ Camera определяется один раз при создании StoredObject и потом не меняется.
    public String generateCameraPath(Long userId, LocalDateTime capturedAt) {
        return String.format(Locale.ROOT, "%s/camera/%04d", generateUserRootPath(userId), capturedAt.getYear());
    }

    // Корень обычной библиотеки: соответствует ROOT, вложенные каталоги повторяют дерево Folder.
    public String generateLibraryRootPath(Long userId) {
        return generateUserRootPath(userId) + "/files";
    }

    // Корневой каталог пользователя: в нём лежат camera/, files/ и маркер владельца.
    public String generateUserRootPath(Long userId) {
        // Locale.ROOT: в части локалей %d выводит не ASCII-цифры.
        return String.format(Locale.ROOT, "%d", userId);
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
