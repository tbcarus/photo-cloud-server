package ru.tbcarus.photocloudserver.model.dto;

import java.time.Instant;

// Ровно один из fileId/folderId; expiresAt null — бессрочно.
public record CreateLinkShareRequest(
        Long fileId,
        Long folderId,
        Instant expiresAt
) {
}
