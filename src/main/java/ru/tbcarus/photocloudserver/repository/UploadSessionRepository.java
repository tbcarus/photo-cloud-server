package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Pageable;
import ru.tbcarus.photocloudserver.model.UploadSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {
    Optional<UploadSession> findByIdAndUserId(UUID id, Long userId);
    Optional<UploadSession> findByUserIdAndClientRequestId(Long userId, UUID clientRequestId);
    List<UploadSession> findByUserIdAndPublicationPathIsNotNull(Long userId);

    @Modifying
    @Query("update UploadSession s set s.publicationPath = null where s.id = :id")
    void clearPublicationPath(UUID id);

    @Query("select coalesce(sum(s.size), 0) from UploadSession s where s.cleaned = false")
    long reservedBytes();

    @Query("select s.id from UploadSession s order by s.createdAt, s.id")
    List<UUID> sessionIds(Pageable pageable);

    @Query("select s.id from UploadSession s where s.state = 'FINALIZING' order by s.createdAt, s.id")
    List<UUID> finalizingIds(Pageable pageable);
}
