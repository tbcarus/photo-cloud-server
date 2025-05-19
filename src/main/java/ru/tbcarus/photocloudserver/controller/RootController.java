package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tbcarus.photocloudserver.model.User;

@RestController
@RequiredArgsConstructor
@Tag(name = "Root actions")
public class RootController {
    public static final String TEST_URL = "api/test";

    @Operation(summary = "Test permit all connection")
    @GetMapping(TEST_URL)
    public ResponseEntity<String> testPermitAll() {
        return ResponseEntity.status(HttpStatus.OK).body("All good! Permit all connection");
    }

    @Operation(summary = "Test authenticated connection")
    @GetMapping(TEST_URL + "/auth")
    public ResponseEntity<String> testAuth(@AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.OK).body("All good! Authenticated connection. Hello " + user.getUsername());
    }
}
