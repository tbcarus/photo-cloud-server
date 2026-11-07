package ru.tbcarus.photocloudserver.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

// Доступ к одному объекту: файлу или папке. Инварианты полей проверяет БД (18-share.sql).
// Право чтения не выводится из owner: ReadAccess сверяет его с фактическим владельцем объекта.
@Entity
@Table(name = "share")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Share {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private Folder folder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_item_id")
    private FileItem fileItem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ShareKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id")
    private User recipient;

    // Только LINK; сам секрет открытым текстом не хранится.
    @Column(length = 64)
    private String tokenHash;

    private String encryptedToken;

    // null — бессрочно; доступ действует, пока текущее время строго меньше expiresAt.
    private Instant expiresAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
