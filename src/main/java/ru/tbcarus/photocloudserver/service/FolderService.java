package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.model.FileType;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.FolderRepository;

@Service
@RequiredArgsConstructor
public class FolderService {

    private static final String ROOT_FOLDER_NAME = "root";
    private static final String CAMERA_FOLDER_NAME = "Camera";
    private static final String FILES_FOLDER_NAME = "Files";

    private final FolderRepository folderRepository;

    public Folder getDefaultFolder(User user, FileType fileType) {
        Folder root = getOrCreateRoot(user);
        return switch (fileType) {
            case IMAGE, VIDEO -> getOrCreateChild(user, root, CAMERA_FOLDER_NAME, FolderType.CAMERA);
            default -> getOrCreateChild(user, root, FILES_FOLDER_NAME, FolderType.FILES);
        };
    }

    private Folder getOrCreateRoot(User user) {
        return folderRepository.findByUserIdAndParentIsNullAndFolderType(user.getId(), FolderType.ROOT)
                .orElseGet(() -> folderRepository.save(Folder.builder()
                        .user(user)
                        .parent(null)
                        .name(ROOT_FOLDER_NAME)
                        .folderType(FolderType.ROOT)
                        .build()));
    }

    private Folder getOrCreateChild(User user, Folder parent, String name, FolderType folderType) {
        return folderRepository.findByUserIdAndParentIdAndFolderType(user.getId(), parent.getId(), folderType)
                .orElseGet(() -> folderRepository.save(Folder.builder()
                        .user(user)
                        .parent(parent)
                        .name(name)
                        .folderType(folderType)
                        .build()));
    }
}
