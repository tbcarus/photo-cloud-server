package ru.tbcarus.photocloudserver.model.dto;

import ru.tbcarus.photocloudserver.model.UploadSession.State;
import java.time.Instant;
import java.util.UUID;

public record UploadSessionDto(UUID id, UUID clientRequestId, Long folderId, String originalFilename,
                               long size, String checksum, UUID backupSource, long confirmedOffset,
                               State state, Instant createdAt, Instant lastActivityAt, Instant finishedAt,
                               long maxChunkSizeBytes, FileItemDto result, String error) { }
