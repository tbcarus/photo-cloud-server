package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.tbcarus.photocloudserver.model.dto.RegisterRequest;
import ru.tbcarus.photocloudserver.service.EmailRequestService;
import ru.tbcarus.photocloudserver.service.UserService;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Registration")
public class RegisterController {
    public static final String REGISTER_URL = "/api/auth/register";
    public static final String VERIFY_EMAIL_URL = "/register/ACTIVATE";
    public static final String RESEND_VERIFY_EMAIL_URL = "/api/auth/resend-verify-email";

    private final UserService userService;
    private final EmailRequestService emailRequestService;

    @Operation(summary = "User registration")
    @PostMapping(REGISTER_URL)
    public ResponseEntity<Map<String, String>> register(@Validated @RequestBody RegisterRequest registerRequest) {
        userService.register(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "Email was sent"));
    }

    @Operation(summary = "Confirm user registration")
    @GetMapping(VERIFY_EMAIL_URL)
    public ResponseEntity<String> verifyEmail(@RequestParam String email, @RequestParam String code) {
        emailRequestService.confirmEmail(email, code);
        return ResponseEntity.status(HttpStatus.OK).body(String.format("User %s was verified", email));
    }
}
