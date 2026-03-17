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

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class AuthController {
    public static final String LOGIN_URL = "/api/auth/login";
    public static final String LOGOUT_URL = "/api/auth/logout";
    public static final String LOGOUT_ALL_URL = "/api/auth/logout-all";
    public static final String LOGOUT_OTHER_URL = "/api/auth/logout-other";
    public static final String REFRESH_TOKEN_URL = "/api/user/refresh-token";

    private final UserService userService;

    @Operation(summary = "User authentication")
    @PostMapping(LOGIN_URL)
    public ResponseEntity<LoginResponse> login(@Validated @RequestBody LoginRequest loginRequest) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.login(loginRequest));
    }

    @Operation(summary = "User logout")
    @PostMapping(LOGOUT_URL)
    public ResponseEntity<Map<String, String>> logout(@Validated @RequestBody LogoutRequest logoutRequest,
                                                      @AuthenticationPrincipal User user) {
        userService.logout(logoutRequest);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("message", "Logged out successfully"));
    }

    @Operation(summary = "User logout all sessions")
    @PostMapping(LOGOUT_ALL_URL)
    public ResponseEntity<Map<String, String>> logoutAll(@AuthenticationPrincipal User user) {
        userService.logoutAll(user);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("message", "All logged out successfully"));
    }

    @Operation(summary = "User logout all other sessions except current")
    @PostMapping(LOGOUT_OTHER_URL)
    public ResponseEntity<Map<String, String>> logoutOther(@Validated @RequestBody LogoutRequest logoutRequest,
                                                           @AuthenticationPrincipal User user) {
        userService.logoutOther(logoutRequest, user);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("message", "All other logged out successfully"));
    }

    @Operation(summary = "Refresh access token")
    @PostMapping(REFRESH_TOKEN_URL)
    public ResponseEntity<RefreshResponse> refresh(@RequestBody RefreshRequest refreshRequest) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.refreshToken(refreshRequest.refreshToken()));
    }
}
