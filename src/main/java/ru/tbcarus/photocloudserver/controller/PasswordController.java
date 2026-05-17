package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.tbcarus.photocloudserver.service.UserService;

import java.util.Map;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(PasswordController.BASE_URL)
@Tag(name = "Password management")
public class PasswordController {
    // Handles password recovery and future password-management entrypoints.
    public static final String BASE_URL = ApiPaths.API_V1 + "/auth/password";
    public static final String RESET_REQUEST_URL = "/reset/request";
    public static final String RESET_CONFIRM_URL = "/reset/confirm";
    public static final String RESET_RESEND_URL = "/reset/resend";
    public static final String RESET_PAGE_URL = "/reset/page";

    private final UserService userService;

    @Operation(summary = "Password reset request")
    @PostMapping(RESET_REQUEST_URL)
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestParam @NotBlank(message = "Email must not be blank") String email) {
        userService.forgotPassword(email);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("message", "Email was sent"));
    }

    @Operation(summary = "Password reset confirmation")
    @PostMapping(RESET_CONFIRM_URL)
    public ResponseEntity<Map<String, String>> resetPassword(@RequestParam @NotBlank(message = "Password must not be blank") String password,
                                                             @RequestParam @NotBlank(message = "Code must not be blank") String code) {
        userService.resetPassword(password, code);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("message", "Password was reset"));
    }

    @Operation(summary = "Reserved endpoint for resending password reset link")
    @PostMapping(RESET_RESEND_URL)
    public ResponseEntity<Map<String, String>> resendResetPassword() {
        // TODO: Resend password reset link with cooldown, attempt limit, and code expiration checks.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Password reset resend is not implemented yet"));
    }

    @Operation(summary = "Reserved endpoint for password reset HTML page")
    @GetMapping(RESET_PAGE_URL)
    public ResponseEntity<Map<String, String>> getResetPasswordPage(@RequestParam String code) {
        // TODO: Render a password reset HTML page when browser-based reset flow is implemented.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Password reset page is not implemented yet"));
    }

    @Operation(summary = "Reserved endpoint for password reset HTML page submit")
    @PostMapping(RESET_PAGE_URL)
    public ResponseEntity<Map<String, String>> submitResetPasswordPage() {
        // TODO: Accept password reset form submit when browser-based reset flow is implemented.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Password reset page submit is not implemented yet"));
    }
}
