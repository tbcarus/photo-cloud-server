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
@RequestMapping(RegisterController.BASE_URL)
@Tag(name = "Registration")
public class RegisterController {
    // Handles user registration, registration confirmation, and confirmation resend entrypoints.
    public static final String BASE_URL = ApiPaths.API_V1 + "/auth/register";
    public static final String REGISTER_URL = "";
    public static final String CONFIRM_URL = "/confirm";
    public static final String RESEND_URL = "/resend";

    private final UserService userService;
    private final EmailRequestService emailRequestService;

    @Operation(summary = "User registration")
    @PostMapping(REGISTER_URL)
    public ResponseEntity<Map<String, String>> register(@Validated @RequestBody RegisterRequest registerRequest) {
        userService.register(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "Email was sent"));
    }

    @Operation(summary = "Confirm user registration")
    @GetMapping(CONFIRM_URL)
    public ResponseEntity<String> verifyEmail(@RequestParam String code) {
        String email = emailRequestService.confirmRegistration(code);
        return ResponseEntity.status(HttpStatus.OK).body(String.format("User %s was verified", email));
    }

    @Operation(summary = "Reserved endpoint for resending registration confirmation")
    @PostMapping(RESEND_URL)
    public ResponseEntity<Map<String, String>> resendVerifyEmail() {
        // TODO: Resend registration confirmation with cooldown, attempt limit, and code expiration checks.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Registration confirmation resend is not implemented yet"));
    }
}
