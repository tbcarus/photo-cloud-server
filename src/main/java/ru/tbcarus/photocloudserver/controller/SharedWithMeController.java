package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.PageResponse;
import ru.tbcarus.photocloudserver.model.dto.SharedResourceDto;
import ru.tbcarus.photocloudserver.service.ShareService;

// «Доступно мне»: прямые действующие USER-доступы текущего пользователя. Содержимое открывают обычные read-endpoints.
@RestController
@RequiredArgsConstructor
@RequestMapping(SharedWithMeController.BASE_URL)
@Tag(name = "Shared with me")
public class SharedWithMeController {

    public static final String BASE_URL = ApiPaths.API_V1 + "/shared-with-me";
    public static final String SHARE_ID_URL = "/{shareId}";

    private final ShareService shareService;

    @Operation(summary = "List resources shared with current user")
    @GetMapping
    public PageResponse<SharedResourceDto> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size,
                                                @AuthenticationPrincipal User user) {
        return PageResponse.of(shareService.listSharedWithMe(user, page, size));
    }

    @Operation(summary = "Remove a resource shared with current user")
    @DeleteMapping(SHARE_ID_URL)
    public ResponseEntity<Void> refuse(@PathVariable Long shareId, @AuthenticationPrincipal User user) {
        shareService.refuse(user, shareId);
        return ResponseEntity.noContent().build();
    }
}
