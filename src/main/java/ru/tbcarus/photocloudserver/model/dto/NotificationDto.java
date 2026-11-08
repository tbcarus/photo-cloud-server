package ru.tbcarus.photocloudserver.model.dto;

import ru.tbcarus.photocloudserver.model.NotificationTargetType;
import ru.tbcarus.photocloudserver.model.NotificationType;

import java.time.Instant;

// Уведомление глазами получателя. targetType/targetId — навигация, а не право доступа.
public record NotificationDto(
        Long id,
        NotificationType type,
        String title,
        String body,
        NotificationTargetType targetType,
        Long targetId,
        Instant createdAt,
        Instant readAt
) {
}
