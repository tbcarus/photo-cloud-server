package ru.tbcarus.photocloudserver.model.dto;

import java.time.Instant;

// Выданный USER-доступ глазами владельца. Поля LINK в USER API не выдаются.
public record ShareDto(
        Long id,
        ShareResourceType resourceType,
        Long resourceId,
        String resourceName,
        ShareUserDto recipient,
        Instant expiresAt,
        ShareStatus status,
        Instant createdAt
) {
}
