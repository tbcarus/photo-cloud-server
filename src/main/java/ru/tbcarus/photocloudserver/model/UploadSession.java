package ru.tbcarus.photocloudserver.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_sessions")
@Getter
@Setter
public class UploadSession {
    public enum State { UPLOADING, FINALIZING, COMPLETED, CANCELLED, FAILED, EXPIRED }

    @Id
    private UUID id;
    private Long userId;
    private UUID clientRequestId;
    private Long folderId;
    private UUID backupSource;
    private String originalFilename;
    private long size;
    private String checksum;
    private long confirmedOffset;
    @Enumerated(EnumType.STRING)
    private State state;
    private Instant createdAt;
    private Instant lastActivityAt;
    private Instant finishedAt;
    @Column(columnDefinition = "text")
    private String resultJson;
    private String error;
    @Column(columnDefinition = "text")
    private String publicationPath;
    private boolean cleaned;

    public boolean terminal() {
        return state != State.UPLOADING && state != State.FINALIZING;
    }
}
