package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tbcarus.photocloudserver.exception.FolderConflictException;
import ru.tbcarus.photocloudserver.exception.FolderNotFoundException;
import ru.tbcarus.photocloudserver.exception.FolderOperationException;
import ru.tbcarus.photocloudserver.model.FileType;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.FolderRepository;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class FolderService {

    private static final String ROOT_FOLDER_NAME = "root";
    private static final String CAMERA_FOLDER_NAME = "Camera";
    private static final String FILES_FOLDER_NAME = "Files";

    private final FolderRepository folderRepository;
    private final FileItemRepository fileItemRepository;

    @Transactional
    public Folder getDefaultFolder(User user, FileType fileType) {
        Folder root = getOrCreateRoot(user);
        return switch (fileType) {
            case IMAGE, VIDEO -> getOrCreateSystemChild(user, root, CAMERA_FOLDER_NAME, FolderType.CAMERA);
            default -> getOrCreateSystemChild(user, root, FILES_FOLDER_NAME, FolderType.FILES);
        };
    }

    @Transactional
    public Folder getOrCreateRoot(User user) {
        return folderRepository.findRootByUserId(user.getId())
                .orElseGet(() -> createRootRaceSafe(user));
    }

    @Transactional(readOnly = true)
    public List<Folder> getChildren(User user, Long parentId) {
        Folder parent = getFolderForUser(parentId, user);
        return folderRepository.findChildrenByUserIdAndParentId(user.getId(), parent.getId());
    }

    @Transactional
    public Folder createFolder(User user, Long parentId, String name) {
        Folder parent = parentId == null ? getOrCreateRoot(user) : getFolderForUser(parentId, user);
        String normalizedName = normalizeName(name);

        // Инвариант: системные CAMERA/FILES являются листьями для пользовательских папок.
        if (isSystemLeaf(parent)) {
            throw new FolderOperationException("Cannot create folder inside " + parent.getFolderType());
        }
        ensureSystemRootNameIsNotReserved(parent, normalizedName);
        ensureNameIsFree(user.getId(), parent.getId(), normalizedName, null);

        try {
            return folderRepository.save(Folder.builder()
                    .user(user)
                    .parent(parent)
                    .name(normalizedName)
                    .folderType(FolderType.USER)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // Инвариант дублируется в БД, чтобы параллельные запросы не создали две папки с одним именем.
            throw new FolderConflictException("Folder with this name already exists in parent folder");
        }
    }

    @Transactional
    public Folder renameFolder(User user, Long folderId, String name) {
        Folder folder = getFolderForUser(folderId, user);
        String normalizedName = normalizeName(name);

        // Системные папки имеют стабильные имена, на них завязаны default-folder правила upload.
        ensureUserFolder(folder, "Cannot rename system folder");
        ensureSystemRootNameIsNotReserved(folder.getParent(), normalizedName);
        ensureNameIsFree(user.getId(), folder.getParent().getId(), normalizedName, folder.getId());

        folder.setName(normalizedName);
        try {
            return folderRepository.save(folder);
        } catch (DataIntegrityViolationException ex) {
            throw new FolderConflictException("Folder with this name already exists in parent folder");
        }
    }

    @Transactional
    public Folder moveFolder(User user, Long folderId, Long targetParentId) {
        Folder folder = getFolderForUser(folderId, user);
        Folder targetParent = getFolderForUser(targetParentId, user);

        // Перемещать можно только USER-папки: ROOT/CAMERA/FILES фиксируют базовую структуру пользователя.
        ensureUserFolder(folder, "Cannot move system folder");
        if (Objects.equals(folder.getId(), targetParent.getId())) {
            throw new FolderOperationException("Cannot move folder into itself");
        }
        if (isSystemLeaf(targetParent)) {
            throw new FolderOperationException("Cannot move folder into " + targetParent.getFolderType());
        }
        ensureNotDescendant(folder, targetParent);
        ensureSystemRootNameIsNotReserved(targetParent, folder.getName());
        ensureNameIsFree(user.getId(), targetParent.getId(), folder.getName(), folder.getId());

        folder.setParent(targetParent);
        try {
            return folderRepository.save(folder);
        } catch (DataIntegrityViolationException ex) {
            throw new FolderConflictException("Folder with this name already exists in target folder");
        }
    }

    @Transactional
    public void deleteFolder(User user, Long folderId) {
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

    private Folder createRootRaceSafe(User user) {
        try {
            return folderRepository.saveAndFlush(Folder.builder()
                    .user(user)
                    .parent(null)
                    .name(ROOT_FOLDER_NAME)
                    .folderType(FolderType.ROOT)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // Root должен быть единственным; при гонке второй запрос перечитывает уже созданный root.
            return folderRepository.findRootByUserId(user.getId())
                    .orElseThrow(() -> ex);
        }
    }

    private Folder getOrCreateSystemChild(User user, Folder root, String name, FolderType folderType) {
        return folderRepository.findByUserIdAndParentIdAndFolderType(user.getId(), root.getId(), folderType)
                .orElseGet(() -> createSystemChildRaceSafe(user, root, name, folderType));
    }

    private Folder createSystemChildRaceSafe(User user, Folder root, String name, FolderType folderType) {
        try {
            return folderRepository.saveAndFlush(Folder.builder()
                    .user(user)
                    .parent(root)
                    .name(name)
                    .folderType(folderType)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // CAMERA/FILES тоже создаются лениво, поэтому параллельный upload может первым создать ту же папку.
            return folderRepository.findByUserIdAndParentIdAndFolderType(user.getId(), root.getId(), folderType)
                    .orElseThrow(() -> ex);
        }
    }

    private void ensureNameIsFree(Long userId, Long parentId, String name, Long currentFolderId) {
        folderRepository.findByUserIdAndParentIdAndName(userId, parentId, name)
                .filter(folder -> !Objects.equals(folder.getId(), currentFolderId))
                .ifPresent(folder -> {
                    throw new FolderConflictException("Folder with this name already exists in parent folder");
                });
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

    private void ensureSystemRootNameIsNotReserved(Folder parent, String name) {
        if (parent.getFolderType() == FolderType.ROOT
                && (CAMERA_FOLDER_NAME.equalsIgnoreCase(name) || FILES_FOLDER_NAME.equalsIgnoreCase(name))) {
            // Инвариант: имена Camera/Files в root зарезервированы под системные папки upload pipeline.
            throw new FolderConflictException("Folder name is reserved for system folder");
        }
    }

    private boolean isSystemLeaf(Folder folder) {
        return folder.getFolderType() == FolderType.CAMERA || folder.getFolderType() == FolderType.FILES;
    }

    private String normalizeName(String name) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank()) {
            throw new FolderOperationException("Folder name must not be blank");
        }
        if (normalizedName.length() > 255) {
            throw new FolderOperationException("Folder name must be at most 255 characters");
        }
        return normalizedName;
    }
}
