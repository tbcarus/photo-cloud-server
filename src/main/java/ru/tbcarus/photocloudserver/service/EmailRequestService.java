package ru.tbcarus.photocloudserver.service;

import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.exception.BadRegistrationRequest;
import ru.tbcarus.photocloudserver.exception.ErrorType;
import ru.tbcarus.photocloudserver.exception.RegistrationLimitExceededException;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.repository.EmailRequestRepository;
import ru.tbcarus.photocloudserver.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailRequestService {
    // Creates, validates, expires, and consumes email verification codes.

    private final UserRepository userRepository;
    private final EmailRequestRepository emailRequestRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${auth.request.activation-valid-days}")
    private int activationValidDays;
    @Value("${auth.request.reset-valid-days}")
    private int resetValidDays;
    @Value("${auth.request.active-max}")
    private int activeRequestsMax;

    @PostConstruct
    void validateConfig() {
        if (activationValidDays < 1 || resetValidDays < 1) {
            throw new IllegalStateException(String.format(
                    "auth.request.activation-valid-days=%d and auth.request.reset-valid-days=%d must be at least 1",
                    activationValidDays, resetValidDays));
        }
        if (activeRequestsMax < 1) {
            throw new IllegalStateException(String.format(
                    "auth.request.active-max=%d must be at least 1", activeRequestsMax));
        }
    }

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

    // Лимит регистраций: учитываются все ACTIVATE-коды, ещё действующие по сроку на момент now, независимо от used.
    // Граница та же, что в EmailRequest.isActive: код учитывается, пока createdAt + срок позже now.
    public void checkActivationLimit(User user, LocalDateTime now) {
        List<EmailRequest> activeCodes = emailRequestRepository.findAllByUserAndTypeAndCreatedAtAfterOrderByCreatedAtAsc(
                user, EmailRequestType.ACTIVATE, now.minusDays(activationValidDays));
        if (activeCodes.size() < activeRequestsMax) {
            return;
        }
        // Попытка освобождается, когда истекает код, после которого в окне остаётся N - 1 кодов.
        LocalDateTime retryAt = activeCodes.get(activeCodes.size() - activeRequestsMax).getCreatedAt()
                .plusDays(activationValidDays);
        Duration untilRetry = Duration.between(now, retryAt);
        throw new RegistrationLimitExceededException(untilRetry.getSeconds() + (untilRetry.getNano() > 0 ? 1 : 0));
    }

    // Вызывается внутри транзакции регистрации: изменения фиксируются dirty checking при commit.
    public void invalidateActivationCodes(User user) {
        emailRequestRepository.findAllByUserAndTypeAndUsedFalse(user, EmailRequestType.ACTIVATE)
                .forEach(emailRequest -> emailRequest.setUsed(true));
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
    public String confirmRegistration(String code) {
        EmailRequest emailRequest = getEmailRequestByCode(code);
        checkEmailRequest(emailRequest, EmailRequestType.ACTIVATE);
        User user = userRepository.findById(emailRequest.getUser().getId()).orElseThrow(() -> new BadRegistrationRequest(ErrorType.NOT_FOUND));
        emailRequest.setUsed(true);
        user.setEnabled(true);
        return user.getEmail();
    }

    @Transactional
    public void resetPassword(String password, String code) {
        EmailRequest emailRequest = getEmailRequestByCode(code);
        checkEmailRequest(emailRequest, EmailRequestType.PASSWORD_RESET);
        User user = userRepository.findById(emailRequest.getUser().getId()).get();
        user.setPassword(passwordEncoder.encode(password));
        emailRequest.setUsed(true);
        List<EmailRequest> list = emailRequestRepository.findAllByUserIdAndCreatedAtBetweenAndTypeOrderByCreatedAtDesc(
                user.getId(),
                LocalDateTime.now().minusDays(resetValidDays),
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
        if (emailRequest.isUsed() || emailRequest.getType() != type || emailRequest.isExpired(validDays(type))) {
            throw new BadRegistrationRequest(ErrorType.NOT_FOUND);
        }
    }

    private int validDays(EmailRequestType type) {
        return switch (type) {
            case ACTIVATE -> activationValidDays;
            case PASSWORD_RESET -> resetValidDays;
        };
    }
}
