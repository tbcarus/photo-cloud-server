package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import ru.tbcarus.photocloudserver.model.dto.CreateLinkShareRequest;
import ru.tbcarus.photocloudserver.model.dto.LinkShareDto;
import ru.tbcarus.photocloudserver.model.dto.PageResponse;
import ru.tbcarus.photocloudserver.model.dto.UpdateShareRequest;
import ru.tbcarus.photocloudserver.service.ShareService;

// Публичные ссылки глазами владельца. Чужая ссылка неотличима от отсутствующей: 404.
@RestController
@RequiredArgsConstructor
@RequestMapping(LinkShareController.BASE_URL)
@Tag(name = "Public links")
public class LinkShareController {

    public static final String BASE_URL = ShareController.BASE_URL + "/links";
    public static final String LINK_ID_URL = "/{id}";

    private final ShareService shareService;

    @Operation(summary = "Create public link to a file or folder")
    @PostMapping
    public ResponseEntity<LinkShareDto> create(@RequestBody CreateLinkShareRequest request, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(shareService.createLink(user, request));
    }

    @Operation(summary = "List public links of current user")
    @GetMapping
    public PageResponse<LinkShareDto> list(@RequestParam(required = false) Long fileId,
                                           @RequestParam(required = false) Long folderId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size,
                                           @AuthenticationPrincipal User user) {
        return PageResponse.of(shareService.listLinks(user, fileId, folderId, page, size));
    }

    @Operation(summary = "Change expiry of public link")
    @PatchMapping(LINK_ID_URL)
    public ResponseEntity<LinkShareDto> updateExpiry(@PathVariable Long id,
                                                     @RequestBody UpdateShareRequest request,
                                                     @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(shareService.updateLinkExpiry(user, id, request));
    }

    @Operation(summary = "Revoke public link")
    @DeleteMapping(LINK_ID_URL)
    public ResponseEntity<Void> revoke(@PathVariable Long id, @AuthenticationPrincipal User user) {
        shareService.revokeLink(user, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Revoke all public links to a file or folder")
    @DeleteMapping
    public ResponseEntity<Void> revokeAll(@RequestParam(required = false) Long fileId,
                                          @RequestParam(required = false) Long folderId,
                                          @AuthenticationPrincipal User user) {
        shareService.revokeAllLinks(user, fileId, folderId);
        return ResponseEntity.noContent().build();
    }
}
