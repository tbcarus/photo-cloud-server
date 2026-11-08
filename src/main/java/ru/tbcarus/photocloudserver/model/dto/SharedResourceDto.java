package ru.tbcarus.photocloudserver.model.dto;

import java.time.Instant;

// Элемент «Доступно мне»: один прямой USER-доступ. file заполнен только у файла; backupSource папки не выдаётся.
public record SharedResourceDto(
        Long shareId,
        ShareResourceType resourceType,
        Long resourceId,
        String name,
        FileItemDto file,
        ShareUserDto owner,
        Instant expiresAt,
        Instant sharedAt
) {
}
