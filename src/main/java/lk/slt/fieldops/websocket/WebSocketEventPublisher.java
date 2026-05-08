package lk.slt.fieldops.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Central publisher for all WebSocket events.
 * Inject this into any service that needs to
 * send real-time updates.
 *
 * Usage example in JobService:
 *   webSocketEventPublisher.publishJobStatusChange(
 *       customerId, technicianId, jobId, newStatus);
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventPublisher {

    private final LocationWebSocketHandler
            locationHandler;
    private final NotificationWebSocketHandler
            notificationHandler;

    // ─── Location Events ──────────────────────────────────

    public void publishLocationUpdate(
            String technicianId,
            double latitude,
            double longitude,
            String address) {
        try {
            locationHandler.broadcastLocationUpdate(
                    technicianId, latitude,
                    longitude, address);
            log.debug(
                    "Published location update for "
                            + "technician: {}",
                    technicianId);
        } catch (Exception e) {
            log.error(
                    "Error publishing location update: {}",
                    e.getMessage());
        }
    }

    // ─── Job Events ───────────────────────────────────────

    public void publishJobStatusChange(
            String customerId,
            String technicianId,
            String jobId,
            String newStatus) {
        try {
            // Send location update message
            WebSocketMessage jobMsg =
                    WebSocketMessage.jobStatusUpdate(
                            jobId, newStatus, technicianId);
            locationHandler.broadcastLocationUpdate(
                    technicianId, 0, 0, "");

            // Send notification to customer
            notificationHandler.notifyJobStatusChange(
                    customerId, technicianId,
                    jobId, newStatus);

            log.debug(
                    "Published job status change: "
                            + "jobId={}, status={}",
                    jobId, newStatus);
        } catch (Exception e) {
            log.error(
                    "Error publishing job status change: {}",
                    e.getMessage());
        }
    }

    // ─── Fault Events ─────────────────────────────────────

    public void publishTechnicianAssigned(
            String customerId,
            String technicianName,
            String faultId) {
        try {
            notificationHandler.notifyTechnicianAssigned(
                    customerId, technicianName, faultId);
            log.debug(
                    "Published technician assigned: "
                            + "faultId={}",
                    faultId);
        } catch (Exception e) {
            log.error(
                    "Error publishing technician "
                            + "assigned event: {}",
                    e.getMessage());
        }
    }

    public void publishFaultUpdate(
            String customerId,
            String faultId,
            String message) {
        try {
            notificationHandler.notifyFaultUpdate(
                    customerId, faultId, message);
        } catch (Exception e) {
            log.error(
                    "Error publishing fault update: {}",
                    e.getMessage());
        }
    }

    // ─── Payment Events ───────────────────────────────────

    public void publishPaymentUpdate(
            String teamLeadId,
            String paymentId,
            String status) {
        try {
            notificationHandler.notifyPaymentUpdate(
                    teamLeadId, paymentId, status);
            log.debug(
                    "Published payment update: "
                            + "paymentId={}, status={}",
                    paymentId, status);
        } catch (Exception e) {
            log.error(
                    "Error publishing payment update: {}",
                    e.getMessage());
        }
    }

    // ─── Broadcast Events ─────────────────────────────────

    public void broadcastSystemNotification(
            String title,
            String message) {
        try {
            notificationHandler.broadcastToAll(
                    title, message, "SYSTEM");
            log.debug(
                    "Broadcast system notification: {}",
                    title);
        } catch (Exception e) {
            log.error(
                    "Error broadcasting system "
                            + "notification: {}",
                    e.getMessage());
        }
    }

    public void sendToRole(
            String role,
            String title,
            String message,
            String type) {
        try {
            notificationHandler.sendToRole(
                    role, title, message, type);
        } catch (Exception e) {
            log.error(
                    "Error sending role notification: {}",
                    e.getMessage());
        }
    }

    public void sendToUser(
            String userId,
            String title,
            String message,
            String type) {
        try {
            notificationHandler.sendToUser(
                    userId, title, message, type);
        } catch (Exception e) {
            log.error(
                    "Error sending user notification: {}",
                    e.getMessage());
        }
    }
}