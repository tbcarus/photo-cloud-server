package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
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
    public static final String LOGOUT_URL = "/api/auth/logout";
    public static final String LOGOUT_ALL_URL = "/api/auth/logout-all";
    public static final String LOGOUT_OTHER_URL = "/api/auth/logout-other";
    public static final String VERIFY_EMAIL_URL = "/api/auth/verify-email";
    public static final String RESEND_VERIFY_EMAIL_URL = "/api/auth/resend-verify-email";
    public static final String FORGOT_PASSWORD_URL = "/api/auth/forgot-password";
    public static final String RESET_PASSWORD_URL = "/api/auth/reset-password";

    private final UserService userService;

    @Operation(summary = "User registration")
    @PostMapping(REGISTER_URL)
    public ResponseEntity<String> register(@Validated @RequestBody RegisterRequest registerRequest) {
        User savedUser = userService.register(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body("Email was sent");
    }

    @Operation(summary = "User authentication")
    @PostMapping(LOGIN_URL)
    public ResponseEntity<LoginResponse> login(@Validated @RequestBody LoginRequest loginRequest) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.login(loginRequest));
    }

    @Operation(summary = "User logout")
    @PostMapping(LOGOUT_URL)
    public ResponseEntity<String> logout(@Validated @RequestBody LogoutRequest logoutRequest, @AuthenticationPrincipal User user) {
        userService.logout(logoutRequest);
        return ResponseEntity.status(HttpStatus.OK).body("Logged out successfully");
    }

    @Operation(summary = "User logout all")
    @PostMapping(LOGOUT_ALL_URL)
    public ResponseEntity<String> logoutAll(@AuthenticationPrincipal User user) {
        userService.logoutAll(user);
        return ResponseEntity.status(HttpStatus.OK).body("All logged out successfully");
    }

    @Operation(summary = "User logout all other except current")
    @PostMapping(LOGOUT_OTHER_URL)
    public ResponseEntity<String> logoutOther(@Validated @RequestBody LogoutRequest logoutRequest, @AuthenticationPrincipal User user) {
        userService.logoutOther(logoutRequest, user);
        return ResponseEntity.status(HttpStatus.OK).body("All other logged out successfully");
    }

    @Operation(summary = "Take new access token")
    @PostMapping(REFRESH_TOKEN_URL)
    public ResponseEntity<RefreshResponse> refresh(@RequestBody RefreshRequest refreshRequest) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.refreshToken(refreshRequest.refreshToken()));
    }

    @Operation(summary = "Forgot password request")
    @PostMapping(FORGOT_PASSWORD_URL)
    public ResponseEntity<String> forgotPassword(@RequestParam String email) {
        userService.forgotPassword(email);
        return ResponseEntity.status(HttpStatus.CREATED).body("Email was sent");
    }

    @Operation(summary = "Reset password")
    @PostMapping(RESET_PASSWORD_URL)
    public ResponseEntity<String> resetPassword(@RequestParam String password, @RequestParam String code) {

        return ResponseEntity.status(HttpStatus.OK).body("Password was reset");
    }

    @GetMapping(USER_URL+"/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.status(HttpStatus.OK).body("All good!");
    }

}
