package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.RefreshToken;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Integer> {

    Optional<RefreshToken> findByToken(String token);
    Optional<RefreshToken> findByUserNameAndRevokedAndExpiresAfter(String userName, boolean revoked, LocalDateTime expires);
    List<RefreshToken> findAllByUserNameAndRevoked(String userName, boolean revoked);

    // Возвращает число отозванных строк: 0 — токен уже отозван, например параллельной заменой.
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revoked = true, t.revokedAt = :revokedAt WHERE t.id = :id AND t.revoked = false")
    int revokeIfActive(Long id, LocalDateTime revokedAt);
}
