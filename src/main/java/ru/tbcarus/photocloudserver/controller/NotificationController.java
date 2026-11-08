package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.NotificationDto;
import ru.tbcarus.photocloudserver.model.dto.PageResponse;
import ru.tbcarus.photocloudserver.model.dto.UnreadNotificationCountDto;
import ru.tbcarus.photocloudserver.service.NotificationService;

// Уведомления текущего пользователя. Чужое уведомление неотличимо от отсутствующего: 404.
@RestController
@RequiredArgsConstructor
@RequestMapping(NotificationController.BASE_URL)
@Tag(name = "Notifications")
public class NotificationController {

    public static final String BASE_URL = ApiPaths.API_V1 + "/notifications";
    public static final String UNREAD_COUNT_URL = "/unread-count";
    public static final String READ_URL = "/{id}/read";
    public static final String READ_ALL_URL = "/read-all";

    private final NotificationService notificationService;

    @Operation(summary = "List notifications of current user")
    @GetMapping
    public PageResponse<NotificationDto> list(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @AuthenticationPrincipal User user) {
        return PageResponse.of(notificationService.list(user, page, size));
    }

    @Operation(summary = "Count unread notifications of current user")
    @GetMapping(UNREAD_COUNT_URL)
    public UnreadNotificationCountDto unreadCount(@AuthenticationPrincipal User user) {
        return notificationService.unreadCount(user);
    }

    @Operation(summary = "Mark notification as read")
    @PatchMapping(READ_URL)
    public ResponseEntity<NotificationDto> markRead(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(notificationService.markRead(user, id));
    }

    @Operation(summary = "Mark all notifications of current user as read")
    @PatchMapping(READ_ALL_URL)
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal User user) {
        notificationService.markAllRead(user);
        return ResponseEntity.noContent().build();
    }
}
