package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tbcarus.photocloudserver.exception.FolderOperationException;
import ru.tbcarus.photocloudserver.exception.ShareNotFoundException;
import ru.tbcarus.photocloudserver.exception.ShareRecipientException;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;
import ru.tbcarus.photocloudserver.model.Share;
import ru.tbcarus.photocloudserver.model.ShareKind;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.CreateLinkShareRequest;
import ru.tbcarus.photocloudserver.model.dto.GrantUserShareRequest;
import ru.tbcarus.photocloudserver.model.dto.LinkShareDto;
import ru.tbcarus.photocloudserver.model.dto.ShareDto;
import ru.tbcarus.photocloudserver.model.dto.ShareResourceType;
import ru.tbcarus.photocloudserver.model.dto.ShareStatus;
import ru.tbcarus.photocloudserver.model.dto.ShareUserDto;
import ru.tbcarus.photocloudserver.model.dto.SharedResourceDto;
import ru.tbcarus.photocloudserver.model.dto.UpdateShareRequest;
import ru.tbcarus.photocloudserver.model.dto.mapper.FileItemMapper;
import ru.tbcarus.photocloudserver.repository.ShareRepository;
import ru.tbcarus.photocloudserver.repository.UserRepository;
import ru.tbcarus.photocloudserver.service.link.LinkTokenService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// USER-доступы: выдача и управление — только владельцем ресурса, список и отказ — только получателем.
// Чтение ресурсов по доступу проверяет ReadAccess; здесь доступы лишь создаются, меняются и удаляются.
@Service
@RequiredArgsConstructor
public class ShareService {

    private static final String RECIPIENT_UNAVAILABLE = "Recipient is not available";
    private static final String RECIPIENT_SELF = "Cannot share with yourself";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ShareRepository shareRepository;
    private final UserRepository userRepository;
    private final FolderService folderService;
    private final FileItemService fileItemService;
    private final FileItemMapper fileItemMapper;
    private final LinkTokenService linkTokens;

    // Все получатели или никто: недопустимый адрес отклоняет операцию до записи.
    // Под блокировкой дерева владельца: удаление ресурса не пройдёт между проверкой и созданием доступа,
    // а параллельные выдачи того же ресурса не создадут дубль USER-доступа.
    @Transactional
    public List<ShareDto> grant(User owner, GrantUserShareRequest request) {
        requireSingleResource(request.fileId(), request.folderId());
        Instant expiresAt = validExpiry(request.expiresAt());

        folderService.lockUserTree(owner);
        Resource resource = ownedShareableResource(owner, request.fileId(), request.folderId());
        Map<Long, User> recipients = resolveRecipients(owner, request.emails());

        for (User recipient : recipients.values()) {
            // Повторная выдача обновляет срок, в том числе продлевает истёкший доступ.
            int updated = shareRepository.updateUserShareExpiry(
                    owner.getId(), resource.folderId(), resource.fileId(), recipient.getId(), expiresAt);
            if (updated == 0) {
                shareRepository.save(Share.builder()
                        .owner(owner)
                        .folder(resource.folder())
                        .fileItem(resource.file())
                        .kind(ShareKind.USER)
                        .recipient(recipient)
                        .expiresAt(expiresAt)
                        .build());
            }
        }
        shareRepository.flush();

        List<Long> order = List.copyOf(recipients.keySet());
        Instant now = Instant.now();
        return shareRepository.findUserSharesForRecipients(owner.getId(), resource.folderId(), resource.fileId(), order)
                .stream()
                .sorted(Comparator.comparingInt(share -> order.indexOf(share.getRecipient().getId())))
                .map(share -> toShareDto(share, now))
                .toList();
    }

    // Без фильтра — все USER-доступы владельца; фильтр по ресурсу допускает только собственный ресурс.
    @Transactional(readOnly = true)
    public Page<ShareDto> listGranted(User owner, Long fileId, Long folderId, int page, int size) {
        requireOwnFilter(owner, fileId, folderId);
        Instant now = Instant.now();
        return shareRepository.findOwnShares(owner.getId(), ShareKind.USER, folderId, fileId, PageRequest.of(page, size, NEWEST_FIRST))
                .map(share -> toShareDto(share, now));
    }

    @Transactional
    public ShareDto updateExpiry(User owner, Long shareId, UpdateShareRequest request) {
        if (!request.isExpiresAtPresent()) {
            throw new IllegalArgumentException("expiresAt is required; null removes the expiry");
        }
        Instant expiresAt = validExpiry(request.getExpiresAt());
        if (shareRepository.updateOwnShareExpiry(shareId, owner.getId(), ShareKind.USER, expiresAt) == 0) {
            throw new ShareNotFoundException(shareId);
        }
        return shareRepository.findOwnShare(shareId, owner.getId(), ShareKind.USER)
                .map(share -> toShareDto(share, Instant.now()))
                .orElseThrow(() -> new ShareNotFoundException(shareId));
    }

    @Transactional
    public void revoke(User owner, Long shareId) {
        if (shareRepository.deleteOwnShare(shareId, owner.getId(), ShareKind.USER) == 0) {
            throw new ShareNotFoundException(shareId);
        }
    }

    // Отзыв всех USER-доступов ресурса; ресурс без доступов — тоже успех.
    @Transactional
    public void revokeAll(User owner, Long fileId, Long folderId) {
        requireSingleResource(fileId, folderId);
        folderService.lockUserTree(owner);
        Resource resource = ownedResource(owner, fileId, folderId);
        shareRepository.deleteOwnSharesOfResource(owner.getId(), ShareKind.USER, resource.folderId(), resource.fileId());
    }

    @Transactional(readOnly = true)
    public Page<SharedResourceDto> listSharedWithMe(User recipient, int page, int size) {
        return shareRepository.findActiveSharedWith(recipient.getId(), Instant.now(), PageRequest.of(page, size, NEWEST_FIRST))
                .map(this::toSharedResourceDto);
    }

    // Получатель удаляет только свой доступ: ресурс и остальные доступы не меняются.
    @Transactional
    public void refuse(User recipient, Long shareId) {
        if (shareRepository.deleteUserShareByIdAndRecipient(shareId, recipient.getId()) == 0) {
            throw new ShareNotFoundException(shareId);
        }
    }

    // --- Публичные ссылки (LINK): управление только владельцем, USER-доступы не затрагиваются ---

    /**
     * Каждый вызов создаёт новую независимую ссылку со своим секретом. Под блокировкой дерева владельца: удаление
     * ресурса не пройдёт между проверкой и созданием. В БД — только SHA-256 и зашифрованный секрет.
     */
    @Transactional
    public LinkShareDto createLink(User owner, CreateLinkShareRequest request) {
        linkTokens.requireConfigured();
        requireSingleResource(request.fileId(), request.folderId());
        Instant expiresAt = validExpiry(request.expiresAt());

        folderService.lockUserTree(owner);
        Resource resource = ownedShareableResource(owner, request.fileId(), request.folderId());
        String token = linkTokens.generateToken();
        Share share = shareRepository.saveAndFlush(Share.builder()
                .owner(owner)
                .folder(resource.folder())
                .fileItem(resource.file())
                .kind(ShareKind.LINK)
                .tokenHash(linkTokens.hash(token))
                .encryptedToken(linkTokens.encrypt(token))
                .expiresAt(expiresAt)
                .build());
        return toLinkDto(share, linkTokens.url(token), Instant.now());
    }

    /** Ссылки владельца, включая истёкшие; URL восстанавливается из зашифрованного секрета. */
    @Transactional(readOnly = true)
    public Page<LinkShareDto> listLinks(User owner, Long fileId, Long folderId, int page, int size) {
        linkTokens.requireConfigured();
        requireOwnFilter(owner, fileId, folderId);
        Instant now = Instant.now();
        return shareRepository.findOwnShares(owner.getId(), ShareKind.LINK, folderId, fileId, PageRequest.of(page, size, NEWEST_FIRST))
                .map(share -> toLinkDto(share, linkUrl(share), now));
    }

    @Transactional
    public LinkShareDto updateLinkExpiry(User owner, Long shareId, UpdateShareRequest request) {
        linkTokens.requireConfigured();
        if (!request.isExpiresAtPresent()) {
            throw new IllegalArgumentException("expiresAt is required; null removes the expiry");
        }
        Instant expiresAt = validExpiry(request.getExpiresAt());
        if (shareRepository.updateOwnShareExpiry(shareId, owner.getId(), ShareKind.LINK, expiresAt) == 0) {
            throw new ShareNotFoundException(shareId);
        }
        return shareRepository.findOwnShare(shareId, owner.getId(), ShareKind.LINK)
                .map(share -> toLinkDto(share, linkUrl(share), Instant.now()))
                .orElseThrow(() -> new ShareNotFoundException(shareId));
    }

    // Отзыв не требует ключа и адреса: закрыть ссылку можно и при неполной конфигурации.
    @Transactional
    public void revokeLink(User owner, Long shareId) {
        if (shareRepository.deleteOwnShare(shareId, owner.getId(), ShareKind.LINK) == 0) {
            throw new ShareNotFoundException(shareId);
        }
    }

    @Transactional
    public void revokeAllLinks(User owner, Long fileId, Long folderId) {
        requireSingleResource(fileId, folderId);
        folderService.lockUserTree(owner);
        Resource resource = ownedResource(owner, fileId, folderId);
        shareRepository.deleteOwnSharesOfResource(owner.getId(), ShareKind.LINK, resource.folderId(), resource.fileId());
    }

    private String linkUrl(Share share) {
        return linkTokens.decrypt(share.getEncryptedToken()).map(linkTokens::url).orElse(null);
    }

    private LinkShareDto toLinkDto(Share share, String url, Instant now) {
        return new LinkShareDto(
                share.getId(),
                resourceType(share),
                resourceId(share),
                resourceName(share),
                url,
                share.getExpiresAt(),
                status(share, now),
                share.getCreatedAt());
    }

    private void requireOwnFilter(User owner, Long fileId, Long folderId) {
        if (fileId != null && folderId != null) {
            throw new IllegalArgumentException("Specify either fileId or folderId");
        }
        if (fileId != null) {
            fileItemService.getFileForCurrentUser(fileId, owner);
        }
        if (folderId != null) {
            folderService.getFolderForUser(folderId, owner);
        }
    }

    private void requireSingleResource(Long fileId, Long folderId) {
        if ((fileId == null) == (folderId == null)) {
            throw new IllegalArgumentException("Specify exactly one of fileId or folderId");
        }
    }

    // Срок — только в будущем. Микросекунды: точность timestamptz, ответ совпадает с сохранённым значением.
    private Instant validExpiry(Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        Instant truncated = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (!truncated.isAfter(Instant.now())) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        return truncated;
    }

    // Поиск по владельцу: чужой и отсутствующий ресурс — 404, получатель не может выдать доступ дальше.
    private Resource ownedResource(User owner, Long fileId, Long folderId) {
        if (fileId != null) {
            return new Resource(null, fileItemService.getFileForCurrentUser(fileId, owner));
        }
        return new Resource(folderService.getFolderForUser(folderId, owner), null);
    }

    private Resource ownedShareableResource(User owner, Long fileId, Long folderId) {
        Resource resource = ownedResource(owner, fileId, folderId);
        if (resource.folder() != null) {
            FolderType type = resource.folder().getFolderType();
            if (type == FolderType.ROOT || type == FolderType.AUTO_UPLOADS) {
                throw new FolderOperationException("Folder of type " + type + " cannot be shared");
            }
        }
        return resource;
    }

    // Совпадение адресов определяет найденный аккаунт: повтор и другой регистр того же email — один получатель.
    private Map<Long, User> resolveRecipients(User owner, List<String> emails) {
        Map<Long, User> recipients = new LinkedHashMap<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        for (String email : emails) {
            Optional<User> found = userRepository.findByEmailIgnoreCase(email);
            if (found.isEmpty() || !found.get().isEnabled() || found.get().isBanned()) {
                rejected.put(email, RECIPIENT_UNAVAILABLE);
            } else if (found.get().getId().equals(owner.getId())) {
                rejected.put(email, RECIPIENT_SELF);
            } else {
                recipients.putIfAbsent(found.get().getId(), found.get());
            }
        }
        if (!rejected.isEmpty()) {
            throw new ShareRecipientException(rejected);
        }
        return recipients;
    }

    private ShareDto toShareDto(Share share, Instant now) {
        return new ShareDto(
                share.getId(),
                resourceType(share),
                resourceId(share),
                resourceName(share),
                userDto(share.getRecipient()),
                share.getExpiresAt(),
                status(share, now),
                share.getCreatedAt());
    }

    private ShareStatus status(Share share, Instant now) {
        boolean active = share.getExpiresAt() == null || now.isBefore(share.getExpiresAt());
        return active ? ShareStatus.ACTIVE : ShareStatus.EXPIRED;
    }

    private SharedResourceDto toSharedResourceDto(Share share) {
        return new SharedResourceDto(
                share.getId(),
                resourceType(share),
                resourceId(share),
                resourceName(share),
                share.getFileItem() == null ? null : fileItemMapper.toDto(share.getFileItem()),
                userDto(share.getOwner()),
                share.getExpiresAt(),
                share.getCreatedAt());
    }

    private ShareResourceType resourceType(Share share) {
        return share.getFolder() != null ? ShareResourceType.FOLDER : ShareResourceType.FILE;
    }

    private Long resourceId(Share share) {
        return share.getFolder() != null ? share.getFolder().getId() : share.getFileItem().getId();
    }

    private String resourceName(Share share) {
        return share.getFolder() != null ? share.getFolder().getName() : share.getFileItem().getOriginalName();
    }

    private ShareUserDto userDto(User user) {
        return new ShareUserDto(user.getId(), user.getEmail(), user.getDisplayName());
    }

    private record Resource(Folder folder, FileItem file) {
        Long folderId() {
            return folder == null ? null : folder.getId();
        }

        Long fileId() {
            return file == null ? null : file.getId();
        }
    }
}
