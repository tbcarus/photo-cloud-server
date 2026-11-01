package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.FolderRepository;
import ru.tbcarus.photocloudserver.repository.UserRepository;
import ru.tbcarus.photocloudserver.service.storage.LibraryStorage;
import ru.tbcarus.photocloudserver.service.storage.StoragePathResolver;
import ru.tbcarus.photocloudserver.service.storage.StorageProperties;
import ru.tbcarus.photocloudserver.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Завершает удаление объектов из .pending-delete. Каталог и есть очередь работы: дерево пользователей не сканируется.
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingDeleteCleaner {

    private static final Pattern ENTRY_NAME = Pattern.compile("(file|folder)-(\\d+)");

    private final FileItemRepository fileItemRepository;
    private final FolderRepository folderRepository;
    private final UserRepository userRepository;
    private final FolderService folderService;
    private final FileItemService fileItemService;
    private final LibraryStorage libraryStorage;
    private final StoragePathResolver storagePathResolver;
    private final StorageProperties storageProperties;
    private final PlatformTransactionManager transactionManager;
    private final ReentrantLock running = new ReentrantLock();

    // Первый запуск сразу после старта Server, дальше с паузой после завершения прохода.
    @Scheduled(fixedDelayString = "${storage.pending-delete-interval}")
    public void clean() {
        if (!running.tryLock()) {
            return;
        }
        try {
            for (Path entry : entries()) {
                process(entry);
            }
        } finally {
            running.unlock();
        }
    }

    private List<Path> entries() {
        Path directory = storagePathResolver.resolvePendingDelete();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            // Подменённый служебный каталог привёл бы очистку в чужие данные: такой каталог не читается вовсе.
            if (!isPlainDirectory(Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))) {
                log.error("{} не является обычным каталогом: очистка не выполняется", directory);
                return List.of();
            }
            libraryStorage.ensureInsideStorage(directory);
            try (var stream = Files.list(directory)) {
                return stream.toList();
            }
        } catch (IOException ex) {
            log.error("Служебный каталог {} не подтверждён: очистка не выполняется", directory, ex);
            return List.of();
        }
    }

    private void process(Path entry) {
        Matcher name = ENTRY_NAME.matcher(entry.getFileName().toString());
        if (!name.matches()) {
            log.warn("Неизвестный объект в служебном каталоге оставлен без изменений: {}", entry);
            return;
        }
        long id;
        try {
            id = Long.parseLong(name.group(2));
        } catch (NumberFormatException ex) {
            log.warn("Служебный объект с недопустимым ID оставлен без изменений: {}", entry, ex);
            return;
        }
        try {
            boolean released = "file".equals(name.group(1)) ? releaseFile(id, entry) : releaseFolder(id, entry);
            if (released) {
                delete(entry);
            }
        } catch (IOException | RuntimeException ex) {
            // Занятый файл или недоступная БД: объект остаётся до следующего прохода.
            log.warn("Очистка {} отложена до следующего прохода", entry, ex);
        }
    }

    private boolean releaseFile(Long id, Path entry) {
        Optional<Long> ownerId = fileItemRepository.findOwnerId(id);
        if (ownerId.isEmpty()) {
            return true;
        }
        // Запись есть, если Server остановился между переносом и COMMIT. Решение принимается только под блокировкой:
        // работающий запрос удаления к этому моменту успевает зафиксироваться.
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            folderService.lockUserTree(owner(ownerId.get()));
            Optional<FileItem> file = fileItemRepository.findWithRelationsById(id);
            if (file.isEmpty()) {
                return true;
            }
            if (!isSameFile(file.get(), entry)) {
                log.error("Запись файла {} не соответствует {}: объект оставлен для разбора", id, entry);
                return false;
            }
            fileItemService.deleteRecords(file.get());
            return true;
        }));
    }

    private boolean releaseFolder(Long id, Path entry) {
        Optional<Long> ownerId = folderRepository.findOwnerId(id);
        if (ownerId.isEmpty()) {
            return true;
        }
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            folderService.lockUserTree(owner(ownerId.get()));
            Optional<Folder> folder = folderRepository.findById(id);
            if (folder.isEmpty()) {
                return true;
            }
            // Папка на своём месте значит, что ID совпал случайно: удалять её записи и этот объект нельзя.
            if (Files.exists(libraryStorage.folderPath(folder.get()), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                log.error("Запись папки {} не соответствует {}: объект оставлен для разбора", id, entry);
                return false;
            }
            folderService.deleteTreeRecords(id);
            return true;
        }));
    }

    private User owner(Long userId) {
        return userRepository.getReferenceById(userId);
    }

    // Совпадения ID недостаточно: файл должен исчезнуть со своего места и совпасть содержимым.
    private boolean isSameFile(FileItem file, Path entry) {
        if (Files.exists(libraryStorage.physicalPath(file), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (InputStream input = Files.newInputStream(entry)) {
            String checksum = FileUtils.writeAndCalculateSHA256(input, OutputStream.nullOutputStream(), storageProperties.getMaxFileSizeBytes()).checksum();
            return checksum.equals(file.getStoredObject().getChecksum());
        } catch (IOException ex) {
            log.warn("Не удалось проверить {}", entry, ex);
            return false;
        }
    }

    // Обход без перехода по ссылкам: Files.walkFileTree входит в junction и удалил бы чужие данные.
    private void delete(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            return;
        }
        if (isPlainDirectory(attributes)) {
            List<Path> children;
            try (var stream = Files.list(path)) {
                children = stream.toList();
            }
            for (Path child : children) {
                delete(child);
            }
        }
        deleteEntry(path);
    }

    // Junction на Windows выглядит как каталог и отличается только признаком isOther.
    private boolean isPlainDirectory(BasicFileAttributes attributes) {
        return attributes.isDirectory() && !attributes.isOther() && !attributes.isSymbolicLink();
    }

    private void deleteEntry(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (AccessDeniedException ex) {
            DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null || !view.readAttributes().isReadOnly()) {
                throw ex;
            }
            view.setReadOnly(false);
            Files.deleteIfExists(path);
        }
    }
}
