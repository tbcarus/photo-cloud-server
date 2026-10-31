package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.tbcarus.photocloudserver.exception.FileConflictException;
import ru.tbcarus.photocloudserver.exception.FolderConflictException;
import ru.tbcarus.photocloudserver.exception.FolderNotFoundException;
import ru.tbcarus.photocloudserver.exception.FolderOperationException;
import ru.tbcarus.photocloudserver.model.FileType;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.FolderRepository;
import ru.tbcarus.photocloudserver.service.storage.FilenameSanitizer;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class FolderService {

    private static final String ROOT_FOLDER_NAME = "root";
    private static final String CAMERA_FOLDER_NAME = "Camera";

    private final FolderRepository folderRepository;
    private final FileItemRepository fileItemRepository;
    private final FilenameSanitizer filenameSanitizer;

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

    @Transactional(readOnly = true)
    public List<Folder> getChildren(User user, Long parentId) {
        Folder parent = getFolderForUser(parentId, user);
        return folderRepository.findChildrenByUserIdAndParentId(user.getId(), parent.getId());
    }

    @Transactional
    public Folder createFolder(User user, Long parentId, String name) {
        filenameSanitizer.validateLibraryName(name);
        lockUserTree(user);
        Folder parent = parentId == null ? getOrCreateRoot(user) : getFolderForUser(parentId, user);

        // Инвариант: системная CAMERA является листом для пользовательских папок.
        if (isCamera(parent)) {
            throw new FolderOperationException("Cannot create folder inside " + parent.getFolderType());
        }
        ensureNameAvailable(user, parent, name, null, null);

        try {
            return folderRepository.saveAndFlush(Folder.builder()
                    .user(user)
                    .parent(parent)
                    .name(name)
                    .folderType(FolderType.USER)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // Инвариант дублируется в БД, чтобы параллельные запросы не создали две папки с одним именем.
            throw new FolderConflictException("Folder with this name already exists in parent folder");
        }
    }

    @Transactional
    public Folder renameFolder(User user, Long folderId, String name) {
        lockUserTree(user);
        Folder folder = getFolderForUser(folderId, user);

        // Системные папки имеют стабильные имена, на них завязаны default-folder правила upload.
        ensureUserFolder(folder, "Cannot rename system folder");
        filenameSanitizer.validateLibraryName(name);
        ensureNameAvailable(user, folder.getParent(), name, null, folder.getId());

        folder.setName(name);
        try {
            return folderRepository.saveAndFlush(folder);
        } catch (DataIntegrityViolationException ex) {
            throw new FolderConflictException("Folder with this name already exists in parent folder");
        }
    }

    @Transactional
    public Folder moveFolder(User user, Long folderId, Long targetParentId) {
        lockUserTree(user);
        Folder folder = getFolderForUser(folderId, user);
        Folder targetParent = getFolderForUser(targetParentId, user);

        // Перемещать можно только USER-папки: ROOT/CAMERA фиксируют базовую структуру пользователя.
        ensureUserFolder(folder, "Cannot move system folder");
        if (Objects.equals(folder.getId(), targetParent.getId())) {
            throw new FolderOperationException("Cannot move folder into itself");
        }
        if (isCamera(targetParent)) {
            throw new FolderOperationException("Cannot move folder into " + targetParent.getFolderType());
        }
        ensureNotDescendant(folder, targetParent);
        ensureNameAvailable(user, targetParent, folder.getName(), null, folder.getId());

        folder.setParent(targetParent);
        try {
            return folderRepository.saveAndFlush(folder);
        } catch (DataIntegrityViolationException ex) {
            throw new FolderConflictException("Folder with this name already exists in target folder");
        }
    }

    @Transactional
    public void deleteFolder(User user, Long folderId) {
        lockUserTree(user);
        Folder folder = getFolderForUser(folderId, user);

        // TODO: В будущем реализовать рекурсивное удаление с учётом шаринга.
        // Если удаляет владелец:
        //   - удалить все дочерние папки
        //   - удалить все FileItem
        //   - удалить физические файлы (StoredObject)
        // Если удаляет не владелец:
        //   - удалить только его FileItem/доступы
        //   - не удалять StoredObject
        ensureUserFolder(folder, "Cannot delete system folder");
        if (folderRepository.existsByParentId(folder.getId())) {
            throw new FolderOperationException("Cannot delete folder with child folders");
        }
        if (fileItemRepository.existsByFolderId(folder.getId())) {
            throw new FolderOperationException("Cannot delete folder with files");
        }

        folderRepository.delete(folder);
    }

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
}
