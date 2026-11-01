package ru.tbcarus.photocloudserver.service.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.tbcarus.photocloudserver.exception.FileConflictException;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.StoredObject;
import ru.tbcarus.photocloudserver.repository.FolderRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Физическое дерево библиотеки: вычисление путей и операции, которые не заменяют существующие объекты.
@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryStorage {

    private final FolderRepository folderRepository;
    private final StoragePathResolver storagePathResolver;
    private final StorageKeyGenerator storageKeyGenerator;

    // Физический ключ есть только у Camera; файл библиотеки адресуется деревом папок и своим именем.
    public Path physicalPath(FileItem file) {
        StoredObject storedObject = file.getStoredObject();
        if (storedObject.getFilePath() != null) {
            return storagePathResolver.resolve(storedObject.getFilePath(), storedObject.getFilename());
        }
        return filePath(file.getFolder(), file.getOriginalName());
    }

    public Path filePath(Folder folder, String name) {
        return storagePathResolver.resolveChild(folderPath(folder), name);
    }

    public Path folderPath(Folder folder) {
        Path path = storagePathResolver.resolve(storageKeyGenerator.generateLibraryRootPath(folder.getUser().getId()));
        for (String name : folderRepository.findPathNames(folder.getId())) {
            path = storagePathResolver.resolveChild(path, name);
        }
        return path;
    }

    public void createLibraryRoot(Long userId) {
        try {
            Files.createDirectories(storagePathResolver.resolve(storageKeyGenerator.generateLibraryRootPath(userId)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // Родитель обязан существовать: его отсутствие означает расхождение БД и диска, скрывать его нельзя.
    public void createDirectory(Path path) {
        try {
            ensureInsideStorage(path);
            Files.createDirectory(path);
        } catch (FileAlreadyExistsException ex) {
            throw new FileConflictException("Physical name is already occupied");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public void move(Path source, Path target) {
        try {
            moveWithoutReplacing(source, target);
        } catch (FileAlreadyExistsException ex) {
            throw new FileConflictException("Physical name is already occupied");
        } catch (NoSuchFileException ex) {
            throw new UncheckedIOException(ex);
        } catch (FileSystemException ex) {
            log.warn("Физический объект {} занят или недоступен", source, ex);
            throw new FileConflictException("Object is busy or not accessible, try again later");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // Переносит только верхний объект. Источника нет — переносить нечего: запись можно удалять, как и при потере байтов.
    public void moveToPendingDelete(String kind, Long id, Path source) {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path target = storagePathResolver.resolvePendingDelete(kind + "-" + id);
        try {
            Files.createDirectories(target.getParent());
            moveWithoutReplacing(source, target);
        } catch (FileAlreadyExistsException | NoSuchFileException ex) {
            throw diagnose(source, target, ex);
        } catch (FileSystemException ex) {
            log.warn("Объект {} занят, удаление отклонено. Источник есть: {}, служебный путь есть: {}",
                    source, Files.exists(source, LinkOption.NOFOLLOW_LINKS), Files.exists(target, LinkOption.NOFOLLOW_LINKS), ex);
            throw new FileConflictException("Object is busy or not accessible, try again later");
        } catch (IOException ex) {
            throw diagnose(source, target, ex);
        }
    }

    // Запрещает выход из хранилища через junction и символические ссылки: лексическая проверка путей их не видит.
    public void ensureInsideStorage(Path path) throws IOException {
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (!existing.toRealPath().startsWith(storagePathResolver.rootPath().toRealPath())) {
            throw new IOException("Path leaves storage root: " + path);
        }
    }

    private UncheckedIOException diagnose(Path source, Path target, IOException ex) {
        // Данные не трогаем: состояние неоднозначно, решение принимает администратор по журналу.
        log.error("Перенос {} в {} не выполнен. Источник есть: {}, служебный путь есть: {}",
                source, target, Files.exists(source, LinkOption.NOFOLLOW_LINKS), Files.exists(target, LinkOption.NOFOLLOW_LINKS), ex);
        return new UncheckedIOException(ex);
    }

    private void moveWithoutReplacing(Path source, Path target) throws IOException {
        // Строковое сравнение: Path на Windows считает равными пути, различающиеся только регистром.
        if (source.toString().equals(target.toString())) {
            return;
        }
        ensureInsideStorage(source);
        ensureInsideStorage(target);
        if (!Files.getFileStore(source).equals(Files.getFileStore(target.getParent()))) {
            throw new IOException("Source and target are on different volumes: " + source + ", " + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(source, target)) {
            // Смена только регистра на NTFS: Files.move без опций считает это одним файлом и ничего не делает.
            // Цель — тот же объект, поэтому ATOMIC_MOVE здесь ничего не заменяет.
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        }
        // Без опций: NTFS атомарно отказывает на занятой цели. ATOMIC_MOVE здесь заменил бы чужой объект.
        Files.move(source, target);
    }
}
