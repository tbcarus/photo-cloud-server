package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.GrantUserShareRequest;
import ru.tbcarus.photocloudserver.model.dto.PageResponse;
import ru.tbcarus.photocloudserver.model.dto.ShareDto;
import ru.tbcarus.photocloudserver.model.dto.UpdateShareRequest;
import ru.tbcarus.photocloudserver.service.ShareService;

import java.util.List;

// USER-доступы глазами владельца ресурса. Чужой доступ неотличим от отсутствующего: 404.
@RestController
@RequiredArgsConstructor
@RequestMapping(ShareController.BASE_URL)
@Tag(name = "Shares")
public class ShareController {

    public static final String BASE_URL = ApiPaths.API_V1 + "/shares";
    public static final String USERS_URL = "/users";
    public static final String SHARE_ID_URL = "/{id}";

    private final ShareService shareService;

    @Operation(summary = "Grant USER access to a file or folder")
    @PostMapping(USERS_URL)
    public ResponseEntity<List<ShareDto>> grant(@Valid @RequestBody GrantUserShareRequest request,
                                                @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(shareService.grant(user, request));
    }

    @Operation(summary = "List USER access granted by current user")
    @GetMapping
    public PageResponse<ShareDto> list(@RequestParam(required = false) Long fileId,
                                       @RequestParam(required = false) Long folderId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @AuthenticationPrincipal User user) {
        return PageResponse.of(shareService.listGranted(user, fileId, folderId, page, size));
    }

    @Operation(summary = "Change expiry of USER access")
    @PatchMapping(SHARE_ID_URL)
    public ResponseEntity<ShareDto> updateExpiry(@PathVariable Long id,
                                                 @RequestBody UpdateShareRequest request,
                                                 @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(shareService.updateExpiry(user, id, request));
    }

    @Operation(summary = "Revoke USER access")
    @DeleteMapping(SHARE_ID_URL)
    public ResponseEntity<Void> revoke(@PathVariable Long id, @AuthenticationPrincipal User user) {
        shareService.revoke(user, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Revoke all USER access to a file or folder")
    @DeleteMapping(USERS_URL)
    public ResponseEntity<Void> revokeAll(@RequestParam(required = false) Long fileId,
                                          @RequestParam(required = false) Long folderId,
                                          @AuthenticationPrincipal User user) {
        shareService.revokeAll(user, fileId, folderId);
        return ResponseEntity.noContent().build();
    }
}
