package ru.tbcarus.photocloudserver.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.exception.BadRegistrationRequest;
import ru.tbcarus.photocloudserver.exception.ErrorType;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.EmailRequestRepository;
import ru.tbcarus.photocloudserver.repository.UserRepository;
import ru.tbcarus.photocloudserver.util.ConfigUtil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailRequestService {

    private final UserRepository userRepository;
    private final EmailRequestRepository emailRequestRepository;
    private final PasswordEncoder passwordEncoder;

    public EmailRequest generateEmailRequest(User user, EmailRequestType type) {
        EmailRequest emailRequest = EmailRequest.builder()
                .code(UUID.randomUUID().toString())
                .type(type)
                .used(false)
                .user(user)
                .build();
        EmailRequest save = emailRequestRepository.save(emailRequest);
        return save;
    }

    public EmailRequest checkAndGenerateCode(User user, EmailRequestType type) {
        int tokensCount = emailRequestRepository.countByUserAndTypeAndCreateDateAfter(user, type, LocalDateTime.now().minusDays(3));

        if (tokensCount >= 3) {
            throw new BadRegistrationRequest(ErrorType.TOO_MUCH_REPEAT_REQUESTS);
        }
        return generateEmailRequest(user, type);
    }

    @Transactional
    public void confirmEmail(String email, String code) {
        EmailRequest emailRequest = getEmailRequestByCode(code);
        checkEmailRequest(emailRequest, EmailRequestType.ACTIVATE);
        Optional<User> optU = userRepository.findByEmailIgnoreCase(email);
        User user = optU.orElseThrow(() -> new BadRegistrationRequest(ErrorType.NOT_FOUND));
        emailRequest.setUsed(true);
        user.setEnabled(true);
    }

    @Transactional
    public void resetPassword(String password, String code) {
        EmailRequest emailRequest = getEmailRequestByCode(code);
        checkEmailRequest(emailRequest, EmailRequestType.PASSWORD_RESET);
        User user = userRepository.findById(emailRequest.getUser().getId()).get();
        user.setPassword(passwordEncoder.encode(password));
        emailRequest.setUsed(true);
        List<EmailRequest> list = emailRequestRepository.findAllByUserIdAndCreateDateBetweenAndTypeOrderByCreateDateDesc(
                user.getId(),
                LocalDateTime.now().minusDays(ConfigUtil.DEFAULT_EXPIRED_DAYS),
                LocalDateTime.now(),
                EmailRequestType.PASSWORD_RESET);
        list.forEach(er -> er.setUsed(true));
    }

    public void delete(EmailRequest emailRequest) {

    }

    public EmailRequest getEmailRequestByCode(String code) {
        return emailRequestRepository.findByCode(code)
                .orElseThrow(() -> new BadRegistrationRequest(ErrorType.NOT_FOUND));
    }

    public void checkEmailRequest(EmailRequest emailRequest, EmailRequestType type) {
        if (emailRequest.isUsed() || emailRequest.getType() != type || emailRequest.isExpired()) {
            throw new BadRegistrationRequest(ErrorType.NOT_FOUND);
        }
    }
}
