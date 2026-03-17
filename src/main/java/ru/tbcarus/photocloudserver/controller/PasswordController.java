package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.tbcarus.photocloudserver.service.UserService;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Password management")
public class PasswordController {
    public static final String FORGOT_PASSWORD_URL = "/api/auth/forgot-password";
    public static final String RESET_PASSWORD_URL = "/api/auth/reset-password";

    private final UserService userService;

    @Operation(summary = "Forgot password request")
    @PostMapping(FORGOT_PASSWORD_URL)
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestParam String email) {
        userService.forgotPassword(email);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("message", "Email was sent"));
    }

    @Operation(summary = "Reset password")
    @PostMapping(RESET_PASSWORD_URL)
    public ResponseEntity<Map<String, String>> resetPassword(@RequestParam String password,
                                                             @RequestParam String code) {
        userService.resetPassword(password, code);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("message", "Password was reset"));
    }
}
