package ru.tbcarus.photocloudserver.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "file_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_file_item_user_checksum", columnNames = {"user_id", "checksum"}),
        indexes = {
                @Index(name = "idx_file_item_user_captured_uploaded_id", columnList = "user_id,captured_at,uploaded_at,id"),
                @Index(name = "idx_file_item_user_file_type", columnList = "user_id,file_type"),
                @Index(name = "idx_file_item_folder", columnList = "folder_id"),
                @Index(name = "idx_file_item_stored_object", columnList = "stored_object_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id", nullable = false)
    private Folder folder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stored_object_id", nullable = false)
    private StoredObject storedObject;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, length = 100)
    private String mimeType;

    @Column(nullable = false)
    private Long size;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileType fileType;

    @Column(nullable = false)
    private LocalDateTime capturedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    // TODO: поле заложено под будущую корзину/soft delete; сейчас используется hard delete.
    private LocalDateTime deletedAt;

    @OneToOne(mappedBy = "fileItem", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private FileMetadata metadata;
}
