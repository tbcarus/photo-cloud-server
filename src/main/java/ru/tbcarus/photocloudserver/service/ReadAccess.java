package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tbcarus.photocloudserver.exception.FileItemNotFoundException;
import ru.tbcarus.photocloudserver.exception.FolderNotFoundException;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.FolderRepository;
import ru.tbcarus.photocloudserver.repository.ShareRepository;

import java.time.Instant;

// Право чтения: собственный объект или действующий USER-доступ. Только для операций чтения:
// операции изменения по-прежнему ищут объект вместе с владельцем (FolderService.getFolderForUser,
// FileItemService.getFileForCurrentUser), поэтому доступ никогда не открывает изменение чужого объекта.
@Service
@RequiredArgsConstructor
public class ReadAccess {

    private final FolderRepository folderRepository;
    private final FileItemRepository fileItemRepository;
    private final ShareRepository shareRepository;

    // Недоступная папка неотличима от отсутствующей: 404.
    @Transactional(readOnly = true)
    public Folder readableFolder(Long folderId, User user) {
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new FolderNotFoundException(folderId));
        if (isOwner(folder.getUser(), user)
                || shareRepository.existsActiveUserShareForFolder(folderId, user.getId(), Instant.now())) {
            return folder;
        }
        throw new FolderNotFoundException(folderId);
    }

    @Transactional(readOnly = true)
    public FileItem readableFile(Long fileId, User user) {
        FileItem file = fileItemRepository.findWithRelationsById(fileId)
                .orElseThrow(() -> new FileItemNotFoundException(fileId));
        if (isOwner(file.getUser(), user)
                || shareRepository.existsActiveUserShareForFile(fileId, user.getId(), Instant.now())) {
            return file;
        }
        throw new FileItemNotFoundException(fileId);
    }

    private boolean isOwner(User owner, User user) {
        return owner.getId().equals(user.getId());
    }
}
