package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tbcarus.photocloudserver.service.ConfirmService;
import ru.tbcarus.photocloudserver.service.UserService;

@RestController
@RequiredArgsConstructor
@Tag(name = "Email actions: confirm e-mail & reset password ")
public class ConfirmController {
    public static final String CONFIRM_EMAIL_URL = "/api/auth/confirm-email";
    public static final String RESEND_CONFIRM_EMAIL_URL = "/api/auth/resend-confirm-email";
    public static final String FORGOT_PASSWORD_URL = "/api/auth/forgot-password";
    public static final String RESET_PASSWORD_URL = "/api/auth/reset-password";

    private final UserService userService;
    private final ConfirmService confirmService;

    @Operation(summary = "Confirm user registration")
    @GetMapping(CONFIRM_EMAIL_URL)
    public ResponseEntity<String> verifyEmail(@RequestParam String email, @RequestParam String code) {
        confirmService.ConfirmEmail(email, code);
        return ResponseEntity.status(HttpStatus.OK).body(String.format("User %s was verified", "dsd"));
    }
}
