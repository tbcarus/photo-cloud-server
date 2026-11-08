package ru.tbcarus.photocloudserver.service.link;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tbcarus.photocloudserver.exception.PublicLinkNotFoundException;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.Share;
import ru.tbcarus.photocloudserver.model.ShareKind;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.FolderRepository;
import ru.tbcarus.photocloudserver.repository.ShareRepository;

import java.time.Instant;

/**
 * Право чтения по публичной ссылке, без пользователя. Токен ищется только по SHA-256; ссылка действует, пока
 * срок не истёк, владелец активен и не заблокирован, а цель принадлежит владельцу (ROOT и AUTO_UPLOADS не открываются).
 * Ссылка на файл открывает только этот файл, на папку — её текущее поддерево. Любой отказ — одинаковый 404.
 * Только чтение: операций изменения по ссылке нет.
 */
@Service
@RequiredArgsConstructor
public class LinkAccess {

    private final ShareRepository shareRepository;
    private final FolderRepository folderRepository;
    private final FileItemRepository fileItemRepository;
    private final LinkTokenService linkTokens;

    /** Действующая ссылка и владелец её цели. */
    public record Grant(Share share, Long ownerId) {
        public Folder folder() {
            return share.getFolder();
        }

        public FileItem file() {
            return share.getFileItem();
        }
    }

    @Transactional(readOnly = true)
    public Grant resolve(String token) {
        if (!linkTokens.isWellFormed(token)) {
            throw new PublicLinkNotFoundException();
        }
        Share share = shareRepository.findByTokenHashAndKind(linkTokens.hash(token), ShareKind.LINK)
                .orElseThrow(PublicLinkNotFoundException::new);
        User owner = share.getOwner();
        boolean active = share.getExpiresAt() == null || Instant.now().isBefore(share.getExpiresAt());
        if (!active || !owner.isEnabled() || owner.isBanned() || !targetBelongsTo(share, owner.getId())) {
            throw new PublicLinkNotFoundException();
        }
        return new Grant(share, owner.getId());
    }

    /** Папка самой ссылки или её поддерева; у ссылки на файл папок нет. */
    @Transactional(readOnly = true)
    public Folder readableFolder(String token, Long folderId) {
        Grant grant = resolve(token);
        Folder root = grant.folder();
        if (root == null || !folderRepository.isInTree(root.getId(), folderId, grant.ownerId())) {
            throw new PublicLinkNotFoundException();
        }
        return folderRepository.findById(folderId).orElseThrow(PublicLinkNotFoundException::new);
    }

    /** Сам файл ссылки либо файл, лежащий сейчас в поддереве папки ссылки; файл и его байты — того же владельца. */
    @Transactional(readOnly = true)
    public FileItem readableFile(String token, Long fileId) {
        Grant grant = resolve(token);
        FileItem file = fileItemRepository.findWithRelationsById(fileId).orElseThrow(PublicLinkNotFoundException::new);
        boolean sameOwner = file.getUser().getId().equals(grant.ownerId())
                && file.getStoredObject().getUser().getId().equals(grant.ownerId());
        boolean direct = grant.file() != null && grant.file().getId().equals(fileId);
        boolean inTree = grant.folder() != null
                && folderRepository.isInTree(grant.folder().getId(), file.getFolder().getId(), grant.ownerId());
        if (!sameOwner || !(direct || inTree)) {
            throw new PublicLinkNotFoundException();
        }
        return file;
    }

    private boolean targetBelongsTo(Share share, Long ownerId) {
        Folder folder = share.getFolder();
        if (folder != null) {
            return folder.getUser().getId().equals(ownerId)
                    && folder.getFolderType() != FolderType.ROOT && folder.getFolderType() != FolderType.AUTO_UPLOADS;
        }
        FileItem file = share.getFileItem();
        return file != null && file.getUser().getId().equals(ownerId)
                && file.getStoredObject().getUser().getId().equals(ownerId);
    }
}
