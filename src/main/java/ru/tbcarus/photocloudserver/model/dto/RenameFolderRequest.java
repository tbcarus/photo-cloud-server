package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RenameFolderRequest(
        @NotBlank
        @Size(max = 255)
        String name,
        // Только для механизма резервирования: UUID активного источника, в дереве которого выполняется операция.
        UUID backupSource
) {
}
