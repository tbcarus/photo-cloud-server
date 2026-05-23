package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFolderRequest(
        Long parentId,
        @NotBlank
        @Size(max = 255)
        String name
) {
}
