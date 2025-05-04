package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.*;
import ru.tbcarus.photocloudserver.service.UserService;

@RestController
@RequiredArgsConstructor
@Tag(name = "Registration & Authentication")
public class RegisterController {
    public static final String USER_URL = "/api/user";
    public static final String REFRESH_TOKEN_URL = "/api/user/refresh-token";
    public static final String REGISTER_URL = "/api/auth/register";
    public static final String LOGIN_URL = "/api/auth/login";
    public static final String VERIFY_EMAIL_URL = "/api/auth/verify-email";
    public static final String RESEND_VERIFY_EMAIL_URL = "/api/auth/resend-verify-email";
    public static final String FORGOT_PASSWORD_URL = "/api/auth/forgot-password";
    public static final String RESET_PASSWORD_URL = "/api/auth/reset-password";

    private final UserService userService;

    @Operation(summary = "User registration")
    @PostMapping(REGISTER_URL)
    public ResponseEntity<Void> register(@Validated @RequestBody UserRegisterDto userRegisterDto) {
        User savedUser = userService.register(userRegisterDto);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "User authentication")
    @PostMapping(LOGIN_URL)
    public ResponseEntity<LoginResponse> login(@Validated @RequestBody LoginRequest loginRequest) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.login(loginRequest));
    }

    @Operation(summary = "Take new access token")
    @PostMapping(REFRESH_TOKEN_URL)
    public ResponseEntity<RefreshResponse> refresh(@RequestBody RefreshRequest refreshRequest) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.refreshToken(refreshRequest.getRefreshToken()));
    }

    @GetMapping(USER_URL+"/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.status(HttpStatus.OK).body("All good!");
    }

}
