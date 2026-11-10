package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.tbcarus.photocloudserver.model.User;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.email = LOWER(:email)")
    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findById(Long id);

    // Login меняет только время входа. Сохранение всей сущности, прочитанной до проверки пароля, вернуло бы
    // прежние значения остальных полей, например password hash, изменённый параллельным reset.
    // @UpdateTimestamp на массовый UPDATE не действует, поэтому lastUpdate задаётся явно.
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = :at, u.lastUpdate = :at WHERE u.id = :id")
    int updateLastLogin(Long id, LocalDateTime at);

}
