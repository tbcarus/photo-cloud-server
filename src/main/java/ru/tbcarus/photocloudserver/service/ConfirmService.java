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
public class ConfirmService {

    UserRepository userRepository;
    EmailRequestRepository emailRequestRepository;

    public String generateCode(User user, EmailRequestType type) {
        String code = UUID.randomUUID().toString();
        EmailRequest verificationToken = EmailRequest.builder()
                .code(code)
                .type(type)
                .used(false)
                .user(user)
                .build();
        emailRequestRepository.save(verificationToken);
        return code;
    }

    public String checkAndGenerateCode(User user, EmailRequestType type) {
        int tokensCount = emailRequestRepository.countByUserAndTypeAndCreateDateAfter(user, type, LocalDateTime.now().minusDays(3));

        if (tokensCount >= 3) {
            throw new BadRegistrationRequest(ErrorType.TOO_MUCH_REPEAT_REQUESTS);
        }
        return generateCode(user, type);
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
