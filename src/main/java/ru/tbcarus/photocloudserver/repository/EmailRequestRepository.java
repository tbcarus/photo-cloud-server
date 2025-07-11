package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailRequestRepository extends JpaRepository<EmailRequest, Integer> {

    EmailRequest getByCode(String code);

    EmailRequest getByUserIdAndCode(Long user_id, String code);

    Optional<EmailRequest> findByCode(String code);

    List<EmailRequest> findAllByUserIdAndCreatedAtBetweenAndTypeOrderByCreatedAtDesc(Long userId,
                                                                                     LocalDateTime after,
                                                                                     LocalDateTime before,
                                                                                     EmailRequestType type);

    int countByUserAndTypeAndCreatedAtAfter(User user, EmailRequestType type, LocalDateTime after);
}
