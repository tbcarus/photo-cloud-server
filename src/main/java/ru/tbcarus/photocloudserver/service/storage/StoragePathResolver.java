package ru.tbcarus.photocloudserver.service.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@RequiredArgsConstructor
public class StoragePathResolver {

    private final StorageProperties storageProperties;

    private static final String PENDING_DELETE_DIR = ".pending-delete";

    public Path resolve(String directoryPath) {
        Path rootPath = rootPath();
        Path targetPath = rootPath.resolve(directoryPath).normalize();
        if (!targetPath.startsWith(rootPath)) {
            throw new IllegalArgumentException("Invalid storage path");
        }
        return targetPath;
    }

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

    // Один компонент пути внутри уже вычисленного каталога библиотеки.
    public Path resolveChild(Path directory, String name) {
        Path targetPath = directory.resolve(name).normalize();
        if (!targetPath.startsWith(rootPath()) || !directory.equals(targetPath.getParent())) {
            throw new IllegalArgumentException("Invalid storage path");
        }
        return targetPath;
    }

    // Общий каталог хранилища: тот же том, что и данные пользователей, но вне их библиотек.
    public Path resolvePendingDelete() {
        return rootPath().resolve(PENDING_DELETE_DIR);
    }

    public Path resolvePendingDelete(String name) {
        return resolveChild(resolvePendingDelete(), name);
    }

    public Path rootPath() {
        return Paths.get(storageProperties.getRoot()).toAbsolutePath().normalize();
    }
}
