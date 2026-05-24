package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.NotNull;

public record MoveFileRequest(
        @NotNull
        Long targetFolderId
) {
}
