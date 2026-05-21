package ru.tbcarus.photocloudserver.service.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@RequiredArgsConstructor
public class StoragePathResolver {

    private final StorageProperties storageProperties;

    public Path resolve(String filePath, String filename) {
        Path rootPath = Paths.get(storageProperties.getRoot()).toAbsolutePath().normalize();
        Path targetPath = rootPath.resolve(filePath).resolve(filename).normalize();
        // Защищаем storage root от выхода через .. или нестандартные разделители.
        if (!targetPath.startsWith(rootPath)) {
            throw new IllegalArgumentException("Invalid storage path");
        }
        return targetPath;
    }
}
