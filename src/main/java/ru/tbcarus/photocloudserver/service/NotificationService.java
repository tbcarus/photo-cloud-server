package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tbcarus.photocloudserver.exception.NotificationNotFoundException;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.Notification;
import ru.tbcarus.photocloudserver.model.NotificationTargetType;
import ru.tbcarus.photocloudserver.model.NotificationType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.NotificationDto;
import ru.tbcarus.photocloudserver.model.dto.UnreadNotificationCountDto;
import ru.tbcarus.photocloudserver.repository.NotificationRepository;

import java.time.Instant;

// Единственное место создания уведомлений. Создание присоединяется к транзакции вызывающей операции:
// откат операции откатывает и уведомление. Доставки (push, email) нет — уведомление только сохраняется.
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final String SHARE_GRANTED_TITLE = "Предоставлен доступ";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final NotificationRepository notificationRepository;

    @Transactional
    public Notification create(User recipient, NotificationType type, String title, String body,
                               NotificationTargetType targetType, Long targetId) {
        return notificationRepository.save(Notification.builder()
                .recipient(recipient)
                .type(type)
                .title(title)
                .body(body)
                .targetType(targetType)
                .targetId(targetId)
                .build());
    }

    // Текст — снимок имени на момент выдачи: переименование и удаление объекта его не меняют.
    @Transactional
    public Notification shareGranted(User recipient, FileItem file) {
        return create(recipient, NotificationType.SHARE_GRANTED, SHARE_GRANTED_TITLE,
                "Вам предоставлен доступ к файлу „" + file.getOriginalName() + "“",
                NotificationTargetType.FILE, file.getId());
    }

    @Transactional
    public Notification shareGranted(User recipient, Folder folder) {
        return create(recipient, NotificationType.SHARE_GRANTED, SHARE_GRANTED_TITLE,
                "Вам предоставлен доступ к папке „" + folder.getName() + "“",
                NotificationTargetType.FOLDER, folder.getId());
    }

    // Показываются независимо от существования объекта и действия Share.
    @Transactional(readOnly = true)
    public Page<NotificationDto> list(User recipient, int page, int size) {
        return notificationRepository.findByRecipient_Id(recipient.getId(), PageRequest.of(page, size, NEWEST_FIRST))
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public UnreadNotificationCountDto unreadCount(User recipient) {
        return new UnreadNotificationCountDto(notificationRepository.countByRecipient_IdAndReadAtIsNull(recipient.getId()));
    }

    // Идемпотентно: уже прочитанное сохраняет исходный readAt.
    @Transactional
    public NotificationDto markRead(User recipient, Long id) {
        notificationRepository.markRead(id, recipient.getId(), Instant.now());
        return notificationRepository.findByIdAndRecipient_Id(id, recipient.getId())
                .map(this::toDto)
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }

    @Transactional
    public void markAllRead(User recipient) {
        notificationRepository.markAllRead(recipient.getId(), Instant.now());
    }

    private NotificationDto toDto(Notification notification) {
        return new NotificationDto(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getTargetType(),
                notification.getTargetId(),
                notification.getCreatedAt(),
                notification.getReadAt());
    }
}
