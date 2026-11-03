package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateBackupSourceRequest(
        @NotBlank
        @Size(max = 255)
        String name,
        @NotNull
        UUID sourceUuid
) {
}
