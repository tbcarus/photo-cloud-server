package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Root actions")
public class RootController {
    public static final String TEST_URL = "api/test";

    @Operation(summary = "Test connection")
    @GetMapping(TEST_URL)
    public ResponseEntity<String> test() {
        return ResponseEntity.status(HttpStatus.OK).body("All good!");
    }
}
