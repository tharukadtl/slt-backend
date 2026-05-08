package lk.slt.fieldops.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketMessage {

    // Message types
    public static final String TYPE_LOCATION_UPDATE =
            "LOCATION_UPDATE";
    public static final String TYPE_JOB_STATUS_UPDATE =
            "JOB_STATUS_UPDATE";
    public static final String TYPE_NOTIFICATION =
            "NOTIFICATION";
    public static final String TYPE_TECHNICIAN_ASSIGNED =
            "TECHNICIAN_ASSIGNED";
    public static final String TYPE_FAULT_UPDATE =
            "FAULT_UPDATE";
    public static final String TYPE_PAYMENT_UPDATE =
            "PAYMENT_UPDATE";
    public static final String TYPE_CONNECTED =
            "CONNECTED";
    public static final String TYPE_ERROR =
            "ERROR";
    public static final String TYPE_PING =
            "PING";
    public static final String TYPE_PONG =
            "PONG";

    private String type;
    private Object data;
    private String senderId;
    private String senderRole;
    private String targetUserId;
    private LocalDateTime timestamp;
    private String sessionId;

    // Static factory methods for common message types
    public static WebSocketMessage locationUpdate(
            String technicianId,
            double latitude,
            double longitude,
            String address) {
        return WebSocketMessage.builder()
                .type(TYPE_LOCATION_UPDATE)
                .senderId(technicianId)
                .data(LocationData.builder()
                        .technicianId(technicianId)
                        .latitude(latitude)
                        .longitude(longitude)
                        .address(address)
                        .timestamp(
                                LocalDateTime.now().toString())
                        .build())
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static WebSocketMessage jobStatusUpdate(
            String jobId,
            String status,
            String technicianId) {
        return WebSocketMessage.builder()
                .type(TYPE_JOB_STATUS_UPDATE)
                .senderId(technicianId)
                .data(JobStatusData.builder()
                        .jobId(jobId)
                        .status(status)
                        .technicianId(technicianId)
                        .timestamp(
                                LocalDateTime.now().toString())
                        .build())
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static WebSocketMessage notification(
            String title,
            String message,
            String targetUserId,
            String type) {
        return WebSocketMessage.builder()
                .type(TYPE_NOTIFICATION)
                .targetUserId(targetUserId)
                .data(NotificationData.builder()
                        .title(title)
                        .message(message)
                        .notificationType(type)
                        .timestamp(
                                LocalDateTime.now().toString())
                        .build())
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static WebSocketMessage connected(String sessionId) {
        return WebSocketMessage.builder()
                .type(TYPE_CONNECTED)
                .sessionId(sessionId)
                .data("Connected to SLT WebSocket Server")
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static WebSocketMessage pong() {
        return WebSocketMessage.builder()
                .type(TYPE_PONG)
                .data("pong")
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static WebSocketMessage error(String message) {
        return WebSocketMessage.builder()
                .type(TYPE_ERROR)
                .data(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ─── Inner Data Classes ───────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationData {
        private String technicianId;
        private String technicianName;
        private double latitude;
        private double longitude;
        private String address;
        private double speed;
        private double heading;
        private String currentJobId;
        private String status;
        private String timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JobStatusData {
        private String jobId;
        private String faultId;
        private String status;
        private String previousStatus;
        private String technicianId;
        private String technicianName;
        private String customerId;
        private String notes;
        private String timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationData {
        private String title;
        private String message;
        private String notificationType;
        private String referenceId;
        private String referenceType;
        private boolean isRead;
        private String timestamp;
    }
}