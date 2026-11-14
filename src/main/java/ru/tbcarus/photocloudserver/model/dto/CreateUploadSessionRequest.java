package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record CreateUploadSessionRequest(
        @NotNull UUID clientRequestId,
        @NotNull @Positive Long folderId,
        @NotBlank @Size(max = 255) String originalFilename,
        @Positive long size,
        @NotNull @Pattern(regexp = "[0-9a-fA-F]{64}") String checksum,
        UUID backupSource) { }
