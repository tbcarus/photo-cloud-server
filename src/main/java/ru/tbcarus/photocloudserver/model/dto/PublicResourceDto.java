package ru.tbcarus.photocloudserver.model.dto;

import java.time.Instant;

// Корень публичной ссылки: тип, ID и имя; file — только у ссылки на файл. expiresAt — срок ссылки.
public record PublicResourceDto(
        ShareResourceType type,
        Long id,
        String name,
        PublicFileDto file,
        Instant expiresAt
) {
}
