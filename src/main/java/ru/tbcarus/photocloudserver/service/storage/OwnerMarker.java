package ru.tbcarus.photocloudserver.service.storage;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * Содержимое маркера владельца {userId}/{safeEmail}.json — справка для администратора при ручном просмотре диска.
 * Не источник авторизации и не замена БД; секретов и служебных полей нет намеренно.
 */
public record OwnerMarker(
        Long userId,
        String email,
        // shape STRING фиксирует ISO 8601 независимо от глобальной настройки WRITE_DATES_AS_TIMESTAMPS.
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        LocalDateTime userCreatedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        LocalDateTime markerCreatedAt
) {
}
