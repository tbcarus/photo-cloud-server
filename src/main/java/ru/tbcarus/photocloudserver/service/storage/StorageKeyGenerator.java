package ru.tbcarus.photocloudserver.service.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.tbcarus.photocloudserver.model.FolderType;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StorageKeyGenerator {

    private final FilenameSanitizer filenameSanitizer;
    private final StorageProperties storageProperties;

    // Каталог определяется один раз при создании StoredObject и потом не меняется: move/rename логические.
    // Раскладка files/ временная — окончательная организация ручной библиотеки ещё не выбрана.
    public String generateFilePath(Long userId, FolderType targetFolderType, LocalDateTime capturedAt) {
        if (targetFolderType == FolderType.CAMERA) {
            // Locale.ROOT: в части локалей %d выводит не ASCII-цифры.
            return String.format(Locale.ROOT, "%d/camera/%04d", userId, capturedAt.getYear());
        }
        return String.format(Locale.ROOT, "%d/files", userId);
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
