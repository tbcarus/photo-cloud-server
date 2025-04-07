package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.exception.EntityAlreadyExistException;
import ru.tbcarus.photocloudserver.exception.EntityNotFoundException;
import ru.tbcarus.photocloudserver.model.RefreshToken;
import ru.tbcarus.photocloudserver.model.Role;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.*;
import ru.tbcarus.photocloudserver.model.dto.mapper.UserRegisterMapper;
import ru.tbcarus.photocloudserver.repository.UserRepository;

import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserRegisterMapper userRegisterMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public User register(UserRegisterDto userRegisterDto) {
        if (userRepository.existsByEmail(userRegisterDto.getEmail().toLowerCase())) {
            throw new EntityAlreadyExistException(userRegisterDto.getEmail(), String.format("User %s already exist", userRegisterDto.getEmail()));
        }
        User user = userRegisterMapper.toUser(userRegisterDto);
        user.setRoles(Set.of(Role.USER));
        user.setEmail(user.getEmail().toLowerCase());
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepository.save(user);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(username).get();
    }

    public LoginResponse login(LoginRequest loginRequest) {
        User user = getUserByEmail(loginRequest.getEmail());
        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            throw new EntityNotFoundException(loginRequest.getEmail(), String.format("Wrong user %s or password", loginRequest.getEmail()));
        }
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        return new LoginResponse(accessToken, refreshToken);
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
