package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.UserDto;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping(UserController.BASE_URL)
@Tag(name = "User profile")
public class UserController {
    // Exposes user profile, settings, and user-owned account data entrypoints.
    public static final String BASE_URL = ApiPaths.API_V1 + "/profile";
    public static final String SETTINGS_URL = "/settings";

    @Operation(summary = "Get current user profile")
    @GetMapping
    public ResponseEntity<UserDto> getProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(new UserDto(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.isEnabled(),
                user.isBanned(),
                user.getRoles(),
                user.getCreatedAt(),
                user.getLastUpdate()
        ));
    }

    @Operation(summary = "Reserved endpoint for updating current user profile")
    @PatchMapping
    public ResponseEntity<Map<String, String>> updateProfile() {
        // TODO: Update editable profile fields after profile update rules and DTO are defined.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Profile update is not implemented yet"));
    }

    @Operation(summary = "Reserved endpoint for getting current user settings")
    @GetMapping(SETTINGS_URL)
    public ResponseEntity<Map<String, String>> getSettings() {
        // TODO: Return user settings after settings model is defined.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Profile settings are not implemented yet"));
    }

    @Operation(summary = "Reserved endpoint for updating current user settings")
    @PatchMapping(SETTINGS_URL)
    public ResponseEntity<Map<String, String>> updateSettings() {
        // TODO: Update user settings after settings model and validation rules are defined.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Profile settings update is not implemented yet"));
    }
}
