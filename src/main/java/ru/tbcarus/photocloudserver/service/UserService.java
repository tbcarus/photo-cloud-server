package ru.tbcarus.photocloudserver.service;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.exception.EntityNotFoundException;
import ru.tbcarus.photocloudserver.exception.DuplicateEmailException;
import ru.tbcarus.photocloudserver.exception.InvalidCredentialsException;
import ru.tbcarus.photocloudserver.exception.InvalidRefreshTokenException;
import ru.tbcarus.photocloudserver.model.*;
import ru.tbcarus.photocloudserver.model.dto.*;
import ru.tbcarus.photocloudserver.model.dto.mapper.UserRegisterMapper;
import ru.tbcarus.photocloudserver.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements UserDetailsService {
    // Manages users and currently hosts authentication, registration, and password workflows.

    private final UserRepository userRepository;
    private final UserRegisterMapper userRegisterMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final EmailRequestService emailRequestService;
    private final EmailService emailService;

    public User register(RegisterRequest registerRequest) {
        if (userRepository.existsByEmail(registerRequest.email().toLowerCase())) {
            throw new DuplicateEmailException();
        }
        User user = userRegisterMapper.toUser(registerRequest);
        user.setRoles(Set.of(Role.USER));
        user.setEmail(user.getEmail().toLowerCase());
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setBanned(false);
        user.setEnabled(false);
        User savedUser = userRepository.save(user);
        EmailRequest emailRequest = emailRequestService.generateEmailRequest(user, EmailRequestType.ACTIVATE);
        try {
            emailService.sendEmail(emailRequest);
        } catch (MessagingException e) {
            log.error("Nothing was sent {}", e.getCause().getMessage());
        }
        return savedUser;
    }

    public void forgotPassword (String email) {
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow(() ->
                new EntityNotFoundException(email, String.format("User %s not found", email)));
        EmailRequest emailRequest = emailRequestService.generateEmailRequest(user, EmailRequestType.PASSWORD_RESET);
        try {
            emailService.sendEmail(emailRequest);
        } catch (MessagingException e) {
            log.error("Nothing was sent {}", e.getCause().getMessage());
        }
    }

    public void resetPassword (String password, String code) {
        emailRequestService.resetPassword(password, code);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(username).get();
    }

    public LoginResponse login(LoginRequest loginRequest) {
        User user = getUserByEmailForLogin(loginRequest.email());
        if (!passwordEncoder.matches(loginRequest.password(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled() || user.isBanned()) {
            throw new InvalidCredentialsException();
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        return new LoginResponse(accessToken, refreshToken);
    }

    public void logout(LogoutRequest logoutRequest, User user) {
        jwtService.revokeOwnedToken(logoutRequest.refreshToken(), user);
    }

    public void logoutAll(User user) {
        jwtService.revokeAll(user);
    }

    public void logoutOther(LogoutRequest logoutRequest, User user) {
        jwtService.revokeOtherOwnedToken(logoutRequest.refreshToken(), user);
    }

    public RefreshResponse refreshToken(String refreshToken) {
        RefreshToken tokenDb = jwtService.getRefreshToken(refreshToken);
        String email = tokenDb.getUserName();
        User user = getUserByEmailForRefresh(email);
        return new RefreshResponse(jwtService.refreshAccessToken(user, tokenDb));
    }

    private User getUserByEmailForLogin(String email) {
        Optional<User> optionalUser = userRepository.findByEmailIgnoreCase(email);
        return optionalUser.orElseThrow(InvalidCredentialsException::new);
    }

    private User getUserByEmailForRefresh(String email) {
        Optional<User> optionalUser = userRepository.findByEmailIgnoreCase(email);
        return optionalUser.orElseThrow(InvalidRefreshTokenException::new);
    }
}
