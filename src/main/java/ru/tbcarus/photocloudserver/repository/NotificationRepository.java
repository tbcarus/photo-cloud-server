package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.Notification;

import java.time.Instant;
import java.util.Optional;

// Каждый запрос ограничен получателем: чужой ID неотличим от отсутствующего.
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipient_Id(Long recipientId, Pageable pageable);

    Optional<Notification> findByIdAndRecipient_Id(Long id, Long recipientId);

    long countByRecipient_IdAndReadAtIsNull(Long recipientId);

    // Условие readAt IS NULL сохраняет исходное время прочтения, в том числе при параллельных запросах.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Notification n SET n.readAt = :readAt
            WHERE n.id = :id AND n.recipient.id = :recipientId AND n.readAt IS NULL
            """)
    int markRead(Long id, Long recipientId, Instant readAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Notification n SET n.readAt = :readAt
            WHERE n.recipient.id = :recipientId AND n.readAt IS NULL
            """)
    int markAllRead(Long recipientId, Instant readAt);
}
