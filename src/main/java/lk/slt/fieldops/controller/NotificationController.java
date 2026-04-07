package lk.slt.fieldops.notification.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.notification.dto.NotificationCountDTO;
import lk.slt.fieldops.notification.dto.NotificationDTO;
import lk.slt.fieldops.notification.dto.SendPushRequest;
import lk.slt.fieldops.notification.entity.Notification;
import lk.slt.fieldops.notification.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * NotificationController
 *
 * GET   /api/notifications            All notifications   → List<NotificationDTO>
 * GET   /api/notifications/unread     Unread only         → List<NotificationDTO>
 * GET   /api/notifications/count      Badge count         → NotificationCountDTO
 * PATCH /api/notifications/read-all   Mark all as read
 * PATCH /api/notifications/{id}/read  Mark one as read    → NotificationDTO
 * POST  /api/notifications/push       Admin manual push
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationDTO>> getAll(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(notificationService.getNotificationsForUser(userId));
    }

    @GetMapping("/unread")
    public ResponseEntity<List<NotificationDTO>> getUnread(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(notificationService.getUnreadForUser(userId));
    }

    @GetMapping("/count")
    public ResponseEntity<NotificationCountDTO> getCount(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(notificationService.getUnreadCount(userId));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Map<String, String>> markAllRead(
            @AuthenticationPrincipal Long userId) {
        notificationService.markAllRead(userId);
        return ResponseEntity.ok(Map.of("message", "All notifications marked as read."));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationDTO> markOneRead(@PathVariable Long id) {
        return ResponseEntity.ok(notificationService.markOneRead(id));
    }

    @PostMapping("/push")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> sendPush(
            @Valid @RequestBody SendPushRequest request) {
        notificationService.sendPush(
            request.getFcmToken(), request.getTitle(), request.getBody());
        if (request.getRecipientId() != null) {
            notificationService.saveInApp(
                request.getRecipientId(),
                Notification.NotificationType.GENERAL,
                request.getTitle(), request.getBody(),
                request.getReferenceId(), request.getReferenceType());
        }
        return ResponseEntity.ok(Map.of("message", "Push notification sent."));
    }
}
