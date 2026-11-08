package ru.tbcarus.photocloudserver.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;

// Ровно один из fileId/folderId; expiresAt null — бессрочно.
public record GrantUserShareRequest(
        Long fileId,
        Long folderId,
        @NotEmpty(message = "At least one recipient email is required")
        List<@NotBlank @Email String> emails,
        Instant expiresAt
) {
}
