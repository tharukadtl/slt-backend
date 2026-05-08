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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LocationWebSocketHandler
        extends TextWebSocketHandler {

    // All active sessions
    private final Map<String, WebSocketSession>
            sessions = new ConcurrentHashMap<>();

    // Map technicianId → sessionId for targeted messaging
    private final Map<String, String>
            technicianSessionMap = new ConcurrentHashMap<>();

    // Map sessionId → userId for cleanup
    private final Map<String, String>
            sessionUserMap = new ConcurrentHashMap<>();

    // Latest location per technician
    private final Map<String, WebSocketMessage.LocationData>
            latestLocations = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public LocationWebSocketHandler() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ─── Connection Lifecycle ─────────────────────────────

    @Override
    public void afterConnectionEstablished(
            WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        log.info("Location WS connected: sessionId={}",
                session.getId());

        // Send welcome message
        sendToSession(session,
                WebSocketMessage.connected(session.getId()));
    }

    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status) {
        String sessionId = session.getId();
        sessions.remove(sessionId);

        // Remove technician mapping
        String userId = sessionUserMap.remove(sessionId);
        if (userId != null) {
            technicianSessionMap.remove(userId);
            log.info(
                    "Location WS disconnected: userId={}, "
                            + "sessionId={}",
                    userId, sessionId);
        }
    }

    @Override
    public void handleTransportError(
            WebSocketSession session,
            Throwable exception) {
        log.error("Location WS transport error: "
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

                case WebSocketMessage.TYPE_LOCATION_UPDATE:
                    handleLocationUpdate(session, wsMessage);
                    break;

                case WebSocketMessage.TYPE_PING:
                    sendToSession(session,
                            WebSocketMessage.pong());
                    break;

                case "REGISTER":
                    handleRegister(session, wsMessage);
                    break;

                default:
                    log.warn(
                            "Unknown message type: {}",
                            wsMessage.getType());
            }

        } catch (Exception e) {
            log.error(
                    "Error handling location WS message: {}",
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
        if (userId != null) {
            technicianSessionMap.put(
                    userId, session.getId());
            sessionUserMap.put(session.getId(), userId);
            log.info(
                    "Technician registered: userId={}, "
                            + "sessionId={}",
                    userId, session.getId());
        }
    }

    private void handleLocationUpdate(
            WebSocketSession session,
            WebSocketMessage message) throws IOException {

        // Register sender if not already registered
        if (message.getSenderId() != null) {
            technicianSessionMap.put(
                    message.getSenderId(), session.getId());
            sessionUserMap.put(
                    session.getId(), message.getSenderId());
        }

        // Store latest location
        if (message.getData() instanceof Map) {
            try {
                WebSocketMessage.LocationData locationData =
                        objectMapper.convertValue(
                                message.getData(),
                                WebSocketMessage.LocationData
                                        .class);
                latestLocations.put(
                        message.getSenderId(), locationData);
                log.debug(
                        "Location updated: technicianId={}, "
                                + "lat={}, lng={}",
                        message.getSenderId(),
                        locationData.getLatitude(),
                        locationData.getLongitude());
            } catch (Exception e) {
                log.error(
                        "Error parsing location data: {}",
                        e.getMessage());
            }
        }

        // Broadcast location update to all admin sessions
        broadcastToAll(message);
    }

    // ─── Public Methods (called from services) ───────────

    public void broadcastLocationUpdate(
            String technicianId,
            double latitude,
            double longitude,
            String address) {
        WebSocketMessage message =
                WebSocketMessage.locationUpdate(
                        technicianId,
                        latitude,
                        longitude,
                        address);
        broadcastToAll(message);
    }

    public void sendLocationToUser(
            String userId,
            WebSocketMessage message) {
        String sessionId = technicianSessionMap.get(userId);
        if (sessionId != null) {
            WebSocketSession session =
                    sessions.get(sessionId);
            if (session != null && session.isOpen()) {
                try {
                    sendToSession(session, message);
                } catch (IOException e) {
                    log.error(
                            "Error sending to user {}: {}",
                            userId, e.getMessage());
                }
            }
        }
    }

    public Map<String, WebSocketMessage.LocationData>
    getAllLatestLocations() {
        return new ConcurrentHashMap<>(latestLocations);
    }

    public int getActiveConnectionsCount() {
        return sessions.size();
    }

    // ─── Utility Methods ─────────────────────────────────

    private void broadcastToAll(WebSocketMessage message) {
        String json = toJson(message);
        if (json == null) return;

        sessions.values().forEach(session -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(
                            new TextMessage(json));
                } catch (IOException e) {
                    log.error(
                            "Broadcast error to session {}: {}",
                            session.getId(),
                            e.getMessage());
                }
            }
        });
    }

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
            log.error("JSON serialization error: {}",
                    e.getMessage());
            return null;
        }
    }
}