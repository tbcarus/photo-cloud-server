package ru.tbcarus.photocloudserver.model.dto;

// Папка публичной ссылки: ID для навигации и имя; тип, backupSource и родитель не выдаются.
public record PublicFolderDto(
        Long id,
        String name
) {
}
