package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tbcarus.photocloudserver.exception.FileConflictException;
import ru.tbcarus.photocloudserver.exception.FolderConflictException;
import ru.tbcarus.photocloudserver.exception.FolderNotFoundException;
import ru.tbcarus.photocloudserver.exception.FolderOperationException;
import ru.tbcarus.photocloudserver.exception.ManagedFolderException;
import ru.tbcarus.photocloudserver.model.FileType;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.FolderRepository;
import ru.tbcarus.photocloudserver.service.storage.FilenameSanitizer;
import ru.tbcarus.photocloudserver.service.storage.LibraryStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class FolderService {

    private static final String ROOT_FOLDER_NAME = "root";
    private static final String CAMERA_FOLDER_NAME = "Camera";
    private static final String AUTO_UPLOADS_FOLDER_NAME = "AutoUploads";

    private final FolderRepository folderRepository;
    private final FileItemRepository fileItemRepository;
    private final FilenameSanitizer filenameSanitizer;
    private final LibraryStorage libraryStorage;
    private final PlatformTransactionManager transactionManager;
    private final ReadAccess readAccess;

    // Единственный маршрут без явной папки: подходящее медиа идёт в Camera, для обычных файлов папку выбирает клиент.
    @Transactional
    public Folder getDefaultFolder(User user, FileType fileType) {
        if (fileType != FileType.IMAGE && fileType != FileType.VIDEO) {
            throw new FolderOperationException("Target folder is required for this file type");
        }
        return getOrCreateCamera(user);
    }

    // Идемпотентно: параллельные запросы проходят очередь блокировки ROOT и получают одну Camera.
    @Transactional
    public Folder getOrCreateCamera(User user) {
        lockUserTree(user);
        Folder root = getOrCreateRoot(user);
        return folderRepository.findByUserIdAndParentIdAndFolderType(user.getId(), root.getId(), FolderType.CAMERA)
                .orElseGet(() -> folderRepository.saveAndFlush(Folder.builder()
                        .user(user)
                        .parent(root)
                        .name(CAMERA_FOLDER_NAME)
                        .folderType(FolderType.CAMERA)
                        .build()));
    }

    @Transactional
    public Folder getOrCreateRoot(User user) {
        return folderRepository.findRootByUserId(user.getId())
                .orElseGet(() -> {
                    folderRepository.insertRootIfAbsent(user.getId(), ROOT_FOLDER_NAME);
                    return folderRepository.findRootByUserId(user.getId()).orElseThrow();
                });
    }

    // Сериализует структурные изменения пользователя: блокировка строки ROOT живёт до конца текущей транзакции.
    // MANDATORY: вне транзакции блокировка снялась бы сразу и ничего не защитила.
    // Вызывается первым в транзакции: всё, что проверяется под блокировкой, должно быть прочитано уже после неё.
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUserTree(User user) {
        if (folderRepository.findRootByUserIdForUpdate(user.getId()).isEmpty()) {
            folderRepository.insertRootIfAbsent(user.getId(), ROOT_FOLDER_NAME);
            folderRepository.findRootByUserIdForUpdate(user.getId()).orElseThrow();
        }
    }

    // Файлы и папки каталога делят одно пространство имён. Строгая проверка — только под lockUserTree;
    // вне блокировки вызов лишь отказывает раньше, до работы с байтами.
    @Transactional(readOnly = true)
    public void ensureNameAvailable(User user, Folder parent, String name, Long currentFileId, Long currentFolderId) {
        // ASCII-имя сравнивается в Java одинаково с lower() в PostgreSQL; Camera может быть ещё не создана.
        if (parent.getFolderType() == FolderType.ROOT && CAMERA_FOLDER_NAME.equalsIgnoreCase(name)) {
            throw new FolderConflictException("Name is reserved for system folder");
        }
        fileItemRepository.findInFolderByName(user.getId(), parent.getId(), name)
                .filter(file -> !Objects.equals(file.getId(), currentFileId))
                .ifPresent(file -> {
                    throw new FileConflictException("File with this name already exists in folder");
                });
        folderRepository.findByUserIdAndParentIdAndName(user.getId(), parent.getId(), name)
                .filter(folder -> !Objects.equals(folder.getId(), currentFolderId))
                .ifPresent(folder -> {
                    throw new FolderConflictException("Folder with this name already exists in parent folder");
                });
    }

    // Чтение по праву ReadAccess; потомки выбираются по владельцу папки, а не по текущему пользователю.
    @Transactional(readOnly = true)
    public List<Folder> getChildren(User user, Long parentId) {
        Folder parent = readAccess.readableFolder(parentId, user);
        return folderRepository.findChildrenByUserIdAndParentId(parent.getUser().getId(), parent.getId());
    }

    public Folder createFolder(User user, Long parentId, String name) {
        return createFolder(user, parentId, name, null);
    }

    public Folder createFolder(User user, Long parentId, String name, UUID backupSource) {
        filenameSanitizer.validateLibraryName(name);
        AtomicReference<Path> created = new AtomicReference<>();
        AtomicReference<Long> createdId = new AtomicReference<>();
        try {
            return transaction().execute(status -> {
                lockUserTree(user);
                Folder parent = parentId == null ? getOrCreateRoot(user) : getFolderForUser(parentId, user);

                // Инвариант: в CAMERA нет пользовательских папок, в AutoUploads — ничего, кроме корней источников.
                if (isCamera(parent) || isAutoUploads(parent)) {
                    throw new FolderOperationException("Cannot create folder inside " + parent.getFolderType());
                }
                ensureWritable(parent, backupSource);
                ensureNameAvailable(user, parent, name, null, null);

                Folder folder = saveFolder(Folder.builder()
                        .user(user)
                        .parent(parent)
                        .name(name)
                        .folderType(FolderType.USER)
                        .build(), "Folder with this name already exists in parent folder");
                libraryStorage.createLibraryRoot(user.getId());
                Path path = libraryStorage.folderPath(folder);
                libraryStorage.createDirectory(path);
                created.set(path);
                createdId.set(folder.getId());
                return folder;
            });
        } catch (RuntimeException ex) {
            removeCreatedDirectory(created.get(), createdId.get());
            throw ex;
        }
    }

    // Идемпотентно по sourceUuid: повтор после потерянного ответа получает ту же папку, другое имя — конфликт.
    public Folder createBackupSource(User user, String name, UUID sourceUuid) {
        filenameSanitizer.validateLibraryName(name);
        List<CreatedDirectory> created = new ArrayList<>();
        try {
            return transaction().execute(status -> {
                lockUserTree(user);
                Optional<Folder> registered = folderRepository.findByUserIdAndBackupSource(user.getId(), sourceUuid);
                if (registered.isPresent()) {
                    if (!registered.get().getName().equals(name)) {
                        throw new FolderConflictException("Backup source is registered with another name");
                    }
                    return registered.get();
                }

                Folder container = getOrCreateAutoUploads(user, created);
                ensureNameAvailable(user, container, name, null, null);
                Folder source = saveFolder(Folder.builder()
                        .user(user)
                        .parent(container)
                        .name(name)
                        .folderType(FolderType.USER)
                        .backupSource(sourceUuid)
                        .build(), "Folder with this name already exists in parent folder");
                createDirectory(source, created);
                return source;
            });
        } catch (RuntimeException ex) {
            // Сначала каталог источника: после него каталог AutoUploads, созданный этой же операцией, пуст.
            for (int i = created.size() - 1; i >= 0; i--) {
                removeCreatedDirectory(created.get(i).path(), created.get(i).folderId());
            }
            throw ex;
        }
    }

    // Вызывается под lockUserTree. Пользовательская папка AutoUploads не становится системной: её имя даёт 409.
    private Folder getOrCreateAutoUploads(User user, List<CreatedDirectory> created) {
        Folder root = getOrCreateRoot(user);
        Optional<Folder> existing = folderRepository.findByUserIdAndParentIdAndFolderType(
                user.getId(), root.getId(), FolderType.AUTO_UPLOADS);
        if (existing.isPresent()) {
            return existing.get();
        }
        ensureNameAvailable(user, root, AUTO_UPLOADS_FOLDER_NAME, null, null);
        Folder container = saveFolder(Folder.builder()
                .user(user)
                .parent(root)
                .name(AUTO_UPLOADS_FOLDER_NAME)
                .folderType(FolderType.AUTO_UPLOADS)
                .build(), "Folder with this name already exists in parent folder");
        createDirectory(container, created);
        return container;
    }

    private void createDirectory(Folder folder, List<CreatedDirectory> created) {
        libraryStorage.createLibraryRoot(folder.getUser().getId());
        Path path = libraryStorage.folderPath(folder);
        libraryStorage.createDirectory(path);
        created.add(new CreatedDirectory(path, folder.getId()));
    }

    // Отключение снимает защиту дерева: папка и её содержимое остаются на месте обычной USER-папкой.
    // Под той же блокировкой, что и запись в дерево: начатая операция резервирования завершается до отключения либо получает 409.
    public void disableBackupSource(User user, Long folderId) {
        transaction().executeWithoutResult(status -> {
            lockUserTree(user);
            Folder folder = getFolderForUser(folderId, user);
            if (folder.getFolderType() != FolderType.USER || folder.getParent() == null || !isAutoUploads(folder.getParent())) {
                throw new FolderOperationException("Folder is not a backup source root");
            }
            if (folder.getBackupSource() != null) {
                folder.setBackupSource(null);
                folderRepository.saveAndFlush(folder);
            }
        });
    }

    // Запись в папку: обычная операция допустима вне активного дерева, операция резервирования — только
    // в дереве своего источника. Строгая проверка — под lockUserTree до изменения записей; вне её — ранний отказ.
    public void ensureWritable(Folder folder, UUID backupSource) {
        Optional<UUID> active = activeBackupSource(folder);
        if (backupSource == null && active.isPresent()) {
            throw new ManagedFolderException("Folder belongs to an active backup source");
        }
        if (backupSource != null && !active.map(backupSource::equals).orElse(false)) {
            throw new ManagedFolderException("Folder is not in the tree of this active backup source");
        }
    }

    // Удаление, move и copy в активном дереве запрещены и для механизма резервирования.
    public void ensureNotManaged(Folder folder) {
        if (activeBackupSource(folder).isPresent()) {
            throw new ManagedFolderException("Folder belongs to an active backup source");
        }
    }

    private Optional<UUID> activeBackupSource(Folder folder) {
        return folderRepository.findActiveBackupSource(folder.getId()).map(UUID::fromString);
    }

    // AutoUploads содержит только корни источников: файлы в нём самом не размещаются.
    public void ensureCanHoldFiles(Folder folder) {
        if (isAutoUploads(folder)) {
            throw new FolderOperationException("Cannot place files inside " + folder.getFolderType());
        }
    }

    public Folder renameFolder(User user, Long folderId, String name) {
        return renameFolder(user, folderId, name, null);
    }

    public Folder renameFolder(User user, Long folderId, String name, UUID backupSource) {
        return moveInTree(user, folderId, folder -> {
            // Системные папки имеют стабильные имена, на них завязаны default-folder правила upload.
            ensureUserFolder(folder, "Cannot rename system folder");
            // Имя корня активного источника задано регистрацией: его не меняет и механизм резервирования.
            if (folder.getBackupSource() != null) {
                throw new ManagedFolderException("Cannot rename root of an active backup source");
            }
            ensureWritable(folder, backupSource);
            filenameSanitizer.validateLibraryName(name);
            ensureNameAvailable(user, folder.getParent(), name, null, folder.getId());
            folder.setName(name);
        }, "Folder with this name already exists in parent folder");
    }

    public Folder moveFolder(User user, Long folderId, Long targetParentId) {
        return moveInTree(user, folderId, folder -> {
            Folder targetParent = getFolderForUser(targetParentId, user);

            // Перемещать можно только USER-папки: ROOT/CAMERA фиксируют базовую структуру пользователя.
            ensureUserFolder(folder, "Cannot move system folder");
            if (Objects.equals(folder.getId(), targetParent.getId())) {
                throw new FolderOperationException("Cannot move folder into itself");
            }
            if (isCamera(targetParent) || isAutoUploads(targetParent)) {
                throw new FolderOperationException("Cannot move folder into " + targetParent.getFolderType());
            }
            // Корень активного источника остаётся прямым потомком AutoUploads; отключённый можно перенести.
            ensureNotManaged(folder);
            ensureNotManaged(targetParent);
            ensureNotDescendant(folder, targetParent);
            ensureNameAvailable(user, targetParent, folder.getName(), null, folder.getId());
            folder.setParent(targetParent);
        }, "Folder with this name already exists in target folder");
    }

    // Переносится только верхний каталог: пути потомков вычисляются из дерева и остаются верными.
    private Folder moveInTree(User user, Long folderId, Consumer<Folder> change, String conflictMessage) {
        AtomicReference<Path> source = new AtomicReference<>();
        AtomicReference<Path> target = new AtomicReference<>();
        try {
            return transaction().execute(status -> {
                lockUserTree(user);
                Folder folder = getFolderForUser(folderId, user);
                Path from = libraryStorage.folderPath(folder);
                change.accept(folder);
                Folder saved = saveFolder(folder, conflictMessage);
                Path to = libraryStorage.folderPath(saved);
                libraryStorage.move(from, to);
                source.set(from);
                target.set(to);
                return saved;
            });
        } catch (RuntimeException ex) {
            undoMoveIfRolledBack(user, source.get(), target.get(),
                    () -> libraryStorage.folderPath(getFolderForUser(folderId, user)));
            throw ex;
        }
    }

    // Ошибка COMMIT не говорит об исходе: перенос возвращается, только если БД достоверно сохранила прежний путь.
    public void undoMoveIfRolledBack(User user, Path source, Path target, Supplier<Path> currentPath) {
        if (source == null) {
            return;
        }
        try {
            transaction().executeWithoutResult(status -> {
                lockUserTree(user);
                if (source.toString().equals(currentPath.get().toString())) {
                    libraryStorage.move(target, source);
                }
            });
        } catch (RuntimeException ex) {
            log.error("Не удалось вернуть {} в {} после сбоя фиксации: нужно сверить БД и диск", target, source, ex);
        }
    }

    public void deleteFolder(User user, Long folderId) {
        transaction().executeWithoutResult(status -> {
            lockUserTree(user);
            Folder folder = getFolderForUser(folderId, user);

            // TODO: При появлении sharing удалять только доступы не владельца, а чужие StoredObject не затрагивать.
            ensureUserFolder(folder, "Cannot delete system folder");
            ensureNotManaged(folder);
            Path source = libraryStorage.folderPath(folder);
            deleteTreeRecords(folderId);
            // Содержимое не перебирается: переносится верхний каталог, очистку завершает PendingDeleteCleaner.
            libraryStorage.moveToPendingDelete("folder", folderId, source);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteTreeRecords(Long folderId) {
        // Сначала файлы: у file_item.folder_id нет каскада.
        fileItemRepository.deleteAllInFolderTree(folderId);
        folderRepository.deleteTree(folderId);
    }

    private Folder saveFolder(Folder folder, String conflictMessage) {
        try {
            return folderRepository.saveAndFlush(folder);
        } catch (DataIntegrityViolationException ex) {
            // Инвариант дублируется в БД, чтобы параллельные запросы не создали две папки с одним именем.
            throw new FolderConflictException(conflictMessage);
        }
    }

    // Ошибка COMMIT не говорит об исходе: каталог убирается, только если новая транзакция не нашла запись папки.
    private void removeCreatedDirectory(Path path, Long folderId) {
        if (path == null) {
            return;
        }
        try {
            if (!Boolean.TRUE.equals(transaction().execute(status -> folderRepository.existsById(folderId)))) {
                // Каталог создан этой операцией и пуст: удаление без рекурсии не затронет чужое содержимое.
                Files.deleteIfExists(path);
            }
        } catch (RuntimeException | IOException ex) {
            log.error("Каталог {} сохранён: исход создания папки {} не определён, нужно сверить БД и диск", path, folderId, ex);
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    // Только владелец: основа операций изменения. Чтение предоставленных папок — ReadAccess.
    @Transactional(readOnly = true)
    public Folder getFolderForUser(Long folderId, User user) {
        return folderRepository.findByIdAndUserId(folderId, user.getId())
                .orElseThrow(() -> new FolderNotFoundException(folderId));
    }

    private void ensureNotDescendant(Folder folder, Folder targetParent) {
        Folder current = targetParent;
        while (current != null) {
            if (Objects.equals(current.getId(), folder.getId())) {
                // Инвариант дерева: перенос в потомка создаст цикл и сломает обход parent-цепочки.
                throw new FolderOperationException("Cannot move folder into its descendant");
            }
            current = current.getParent();
        }
    }

    private void ensureUserFolder(Folder folder, String message) {
        if (folder.getFolderType() != FolderType.USER) {
            throw new FolderOperationException(message);
        }
    }

    private boolean isCamera(Folder folder) {
        return folder.getFolderType() == FolderType.CAMERA;
    }

    private boolean isAutoUploads(Folder folder) {
        return folder.getFolderType() == FolderType.AUTO_UPLOADS;
    }

    private record CreatedDirectory(Path path, Long folderId) {
    }
}
