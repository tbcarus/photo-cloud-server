package ru.tbcarus.photocloudserver.model.dto;

// Файл публичной ссылки: только данные для показа и скачивания. Checksum, метаданные (в том числе GPS),
// пути хранения и владелец не выдаются.
public record PublicFileDto(
        Long id,
        String name,
        String mimeType,
        Long size,
        String fileType
) {
}
