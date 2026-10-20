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
        Path rootPath = rootPath();
        Path targetPath = rootPath.resolve(filePath).resolve(filename).normalize();
        // Защищаем storage root от выхода через .. или нестандартные разделители.
        if (!targetPath.startsWith(rootPath)) {
            throw new IllegalArgumentException("Invalid storage path");
        }
        return targetPath;
    }

    // Файл, который обязан лежать непосредственно в каталоге directoryPath: имя не может увести даже в соседний каталог.
    public Path resolveDirectChild(String directoryPath, String filename) {
        Path rootPath = rootPath();
        Path directory = rootPath.resolve(directoryPath).normalize();
        Path targetPath = directory.resolve(filename).normalize();
        if (!directory.startsWith(rootPath) || !directory.equals(targetPath.getParent())) {
            throw new IllegalArgumentException("Invalid storage path");
        }
        return targetPath;
    }

    private Path rootPath() {
        return Paths.get(storageProperties.getRoot()).toAbsolutePath().normalize();
    }
}
