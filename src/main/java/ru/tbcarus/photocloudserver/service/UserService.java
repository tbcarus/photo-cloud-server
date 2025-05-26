package ru.tbcarus.photocloudserver.service;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.exception.EntityAlreadyExistException;
import ru.tbcarus.photocloudserver.exception.EntityNotFoundException;
import ru.tbcarus.photocloudserver.model.*;
import ru.tbcarus.photocloudserver.model.dto.*;
import ru.tbcarus.photocloudserver.model.dto.mapper.UserRegisterMapper;
import ru.tbcarus.photocloudserver.repository.UserRepository;

import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserRegisterMapper userRegisterMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final EmailRequestService emailRequestService;
    private final EmailService emailService;

    public User register(RegisterRequest registerRequest) {
        if (userRepository.existsByEmail(registerRequest.email().toLowerCase())) {
            throw new EntityAlreadyExistException(registerRequest.email(), String.format("User %s already exist", registerRequest.email()));
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

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(username).get();
    }

    public LoginResponse login(LoginRequest loginRequest) {
        User user = getUserByEmail(loginRequest.email());
        if (!passwordEncoder.matches(loginRequest.password(), user.getPassword())) {
            throw new EntityNotFoundException(loginRequest.email(), String.format("Wrong user %s or password", loginRequest.email()));
        }
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        return new LoginResponse(accessToken, refreshToken);
    }

    public void logout(LogoutRequest logoutRequest) {
        jwtService.revoke(logoutRequest.refreshToken());
    }

    public void logoutAll(User user) {
        jwtService.revokeAll(user);
    }

    public void logoutOther(LogoutRequest logoutRequest, User user) {
        jwtService.revokeOther(logoutRequest.refreshToken(), user);
    }

    public RefreshResponse refreshToken(String refreshToken) {
        RefreshToken tokenDb = jwtService.getRefreshToken(refreshToken);
        String email = tokenDb.getUserName();
        User user = getUserByEmail(email);
        return new RefreshResponse(jwtService.refreshAccessToken(user, tokenDb));
    }

    private User getUserByEmail(String email) {
        Optional<User> optionalUser = userRepository.findByEmailIgnoreCase(email);
        return optionalUser.orElseThrow(() ->
                new EntityNotFoundException(email, String.format("Wrong user %s or password", email))
        );
    }
}
