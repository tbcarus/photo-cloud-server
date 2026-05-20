package ru.tbcarus.photocloudserver.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "stored_object",
        uniqueConstraints = @UniqueConstraint(name = "uk_stored_object_user_storage_key", columnNames = {"user_id", "storage_key"}),
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

    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
