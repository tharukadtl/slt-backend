package lk.slt.fieldops.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class NotificationWebSocketHandler
        extends TextWebSocketHandler {

    // All active sessions
    private final Map<String, WebSocketSession>
            sessions = new ConcurrentHashMap<>();

    // userId → sessionId mapping
    private final Map<String, String>
            userSessionMap = new ConcurrentHashMap<>();

    // sessionId → userId mapping
    private final Map<String, String>
            sessionUserMap = new ConcurrentHashMap<>();

    // role → Set of userIds for role-based broadcasting
    private final Map<String, Set<String>>
            roleUserMap = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public NotificationWebSocketHandler() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ─── Connection Lifecycle ─────────────────────────────

    @Override
    public void afterConnectionEstablished(
            WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        log.info(
                "Notification WS connected: sessionId={}",
                session.getId());
        sendToSession(session,
                WebSocketMessage.connected(session.getId()));
    }

    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status) {
        String sessionId = session.getId();
        sessions.remove(sessionId);

        String userId = sessionUserMap.remove(sessionId);
        if (userId != null) {
            userSessionMap.remove(userId);

            // Remove from role maps
            roleUserMap.values().forEach(
                    userSet -> userSet.remove(userId));

            log.info(
                    "Notification WS disconnected: "
                            + "userId={}, sessionId={}",
                    userId, sessionId);
        }
    }

    @Override
    public void handleTransportError(
            WebSocketSession session,
            Throwable exception) {
        log.error(
                "Notification WS transport error: "
                        + "sessionId={}, error={}",
                session.getId(), exception.getMessage());
        sessions.remove(session.getId());
    }

    // ─── Message Handling ─────────────────────────────────

    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message) throws Exception {
        try {
            WebSocketMessage wsMessage = objectMapper
                    .readValue(
                            message.getPayload(),
                            WebSocketMessage.class);

            switch (wsMessage.getType()) {

                case "REGISTER":
                    handleRegister(session, wsMessage);
                    break;

                case WebSocketMessage.TYPE_PING:
                    sendToSession(session,
                            WebSocketMessage.pong());
                    break;

                default:
                    log.warn(
                            "Unknown notification WS "
                                    + "message type: {}",
                            wsMessage.getType());
            }

        } catch (Exception e) {
            log.error(
                    "Error handling notification WS "
                            + "message: {}",
                    e.getMessage());
            sendToSession(session,
                    WebSocketMessage.error(
                            "Invalid message format"));
        }
    }

    // ─── Private Handlers ────────────────────────────────

    private void handleRegister(
            WebSocketSession session,
            WebSocketMessage message) throws IOException {
        String userId = message.getSenderId();
        String role = message.getSenderRole();

        if (userId != null) {
            userSessionMap.put(userId, session.getId());
            sessionUserMap.put(session.getId(), userId);

            // Map user to role
            if (role != null) {
                roleUserMap.computeIfAbsent(
                                role.toLowerCase(),
                                k -> ConcurrentHashMap.newKeySet())
                        .add(userId);
            }

            log.info(
                    "User registered for notifications: "
                            + "userId={}, role={}, "
                            + "sessionId={}",
                    userId, role, session.getId());
        }
    }

    // ─── Public Broadcast Methods ─────────────────────────

    /**
     * Send notification to a specific user
     */
    public void sendToUser(
            String userId,
            String title,
            String message,
            String type) {
        String sessionId = userSessionMap.get(userId);
        if (sessionId != null) {
            WebSocketSession session =
                    sessions.get(sessionId);
            if (session != null && session.isOpen()) {
                try {
                    WebSocketMessage wsMessage =
                            WebSocketMessage.notification(
                                    title,
                                    message,
                                    userId,
                                    type);
                    sendToSession(session, wsMessage);
                    log.debug(
                            "Notification sent to user {}: {}",
                            userId, title);
                } catch (IOException e) {
                    log.error(
                            "Error sending notification "
                                    + "to user {}: {}",
                            userId, e.getMessage());
                }
            }
        } else {
            log.debug(
                    "User {} not connected, notification "
                            + "queued",
                    userId);
        }
    }

    /**
     * Send notification to all users with a specific role
     * e.g. all admins, all technicians
     */
    public void sendToRole(
            String role,
            String title,
            String message,
            String type) {
        Set<String> userIds =
                roleUserMap.get(role.toLowerCase());
        if (userIds != null) {
            userIds.forEach(userId ->
                    sendToUser(userId, title, message, type));
            log.debug(
                    "Notification sent to role {}: {} users",
                    role, userIds.size());
        }
    }

    /**
     * Broadcast to all connected users
     */
    public void broadcastToAll(
            String title,
            String message,
            String type) {
        WebSocketMessage wsMessage =
                WebSocketMessage.notification(
                        title, message, null, type);
        sessions.values().forEach(session -> {
            if (session.isOpen()) {
                try {
                    sendToSession(session, wsMessage);
                } catch (IOException e) {
                    log.error(
                            "Broadcast notification error: {}",
                            e.getMessage());
                }
            }
        });
        log.debug(
                "Notification broadcast to {} sessions",
                sessions.size());
    }

    /**
     * Send job status update notification
     */
    public void notifyJobStatusChange(
            String customerId,
            String technicianId,
            String jobId,
            String newStatus) {
        // Notify customer
        sendToUser(
                customerId,
                "Job Status Updated",
                "Your job #" + jobId
                        + " status changed to: " + newStatus,
                WebSocketMessage.TYPE_JOB_STATUS_UPDATE);

        // Notify admins
        sendToRole(
                "admin",
                "Job Status Updated",
                "Job #" + jobId + " is now " + newStatus,
                WebSocketMessage.TYPE_JOB_STATUS_UPDATE);
    }

    /**
     * Send technician assignment notification
     */
    public void notifyTechnicianAssigned(
            String customerId,
            String technicianName,
            String faultId) {
        sendToUser(
                customerId,
                "Technician Assigned",
                technicianName
                        + " has been assigned to your fault #"
                        + faultId,
                WebSocketMessage.TYPE_TECHNICIAN_ASSIGNED);
    }

    /**
     * Send payment status notification
     */
    public void notifyPaymentUpdate(
            String teamLeadId,
            String paymentId,
            String status) {
        sendToUser(
                teamLeadId,
                "Payment " + status,
                "Your payment #" + paymentId
                        + " has been " + status.toLowerCase(),
                WebSocketMessage.TYPE_PAYMENT_UPDATE);
    }

    /**
     * Send fault update notification
     */
    public void notifyFaultUpdate(
            String customerId,
            String faultId,
            String message) {
        sendToUser(
                customerId,
                "Fault Update",
                message,
                WebSocketMessage.TYPE_FAULT_UPDATE);
    }

    public int getActiveConnectionsCount() {
        return sessions.size();
    }

    public boolean isUserConnected(String userId) {
        String sessionId = userSessionMap.get(userId);
        if (sessionId == null) return false;
        WebSocketSession session = sessions.get(sessionId);
        return session != null && session.isOpen();
    }

    // ─── Utility ─────────────────────────────────────────

    private void sendToSession(
            WebSocketSession session,
            WebSocketMessage message) throws IOException {
        if (session.isOpen()) {
            String json = toJson(message);
            if (json != null) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }

    private String toJson(WebSocketMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error(
                    "JSON serialization error: {}",
                    e.getMessage());
            return null;
        }
    }
}