package ru.tbcarus.photocloudserver.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.UploadSession.State;
import ru.tbcarus.photocloudserver.model.dto.*;
import ru.tbcarus.photocloudserver.service.upload.UploadSessionService;
import java.io.IOException;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping(ApiPaths.API_V1 + "/upload-sessions")
public class UploadSessionController {
    private final UploadSessionService service;

    @PostMapping
    public UploadSessionDto create(@Valid @RequestBody CreateUploadSessionRequest request, @AuthenticationPrincipal User user) {
        return service.create(request, user);
    }

    @GetMapping("/{id}")
    public UploadSessionDto get(@PathVariable UUID id, @AuthenticationPrincipal User user) { return service.get(id, user); }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public UploadSessionDto patch(@PathVariable UUID id, @RequestHeader("Upload-Offset") long offset,
                                   HttpServletRequest request, @AuthenticationPrincipal User user) throws IOException {
        return service.patch(id, user, offset, request.getContentLengthLong(), request.getInputStream());
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<UploadSessionDto> complete(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        UploadSessionDto result = service.complete(id, user);
        return ResponseEntity.status(result.state() == State.FINALIZING ? 202 : 200).body(result);
    }

    @DeleteMapping("/{id}")
    public UploadSessionDto cancel(@PathVariable UUID id, @AuthenticationPrincipal User user) { return service.cancel(id, user); }
}
