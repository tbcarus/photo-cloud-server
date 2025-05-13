package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.exception.BadRegistrationRequest;
import ru.tbcarus.photocloudserver.exception.ErrorType;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.EmailRequestRepository;
import ru.tbcarus.photocloudserver.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailRequestService {

    private final UserRepository userRepository;
    private final EmailRequestRepository emailRequestRepository;

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

    public void ConfirmEmail(String email, String code) {
//        EmailRequest verificationToken = verificationTokenService.validateToken(token, TokenType.EMAIL_VERIFICATION);
//        User user = verificationToken.getUser();
//        user.setEnabled(true);
//        userRepository.save(user);
//        verificationTokenService.delete(verificationToken);
    }

    public void delete(EmailRequest emailRequest) {

    }

}
