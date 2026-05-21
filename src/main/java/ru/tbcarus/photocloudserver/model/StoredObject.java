package ru.tbcarus.photocloudserver.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "stored_object",
        uniqueConstraints = @UniqueConstraint(name = "uk_stored_object_user_checksum", columnNames = {"user_id", "checksum"}),
        indexes = @Index(name = "idx_stored_object_user", columnList = "user_id")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredObject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "file_extension", nullable = false, length = 20)
    private String fileExtension;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(nullable = false)
    private Long size;

    @Column(name = "detected_mime_type", nullable = false, length = 100)
    private String detectedMimeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileType fileType;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
