package ru.tbcarus.photocloudserver.model.dto;

import java.time.Instant;

// Публичная ссылка глазами владельца. url — полный адрес для повторного копирования; null, если секрет не
// расшифровывается текущим ключом. tokenHash и encryptedToken не выдаются.
public record LinkShareDto(
        Long id,
        ShareResourceType resourceType,
        Long resourceId,
        String resourceName,
        String url,
        Instant expiresAt,
        ShareStatus status,
        Instant createdAt
) {
}
