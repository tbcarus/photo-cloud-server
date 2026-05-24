package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.Size;

public record CopyFileRequest(
        Long targetFolderId,
        @Size(max = 255)
        String originalName
) {
}
