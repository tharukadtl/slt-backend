package lk.slt.fieldops.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.LocationUpdateRequest;
import lk.slt.fieldops.entity.TechnicianLocation;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.TechnicianLocationRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FAULT-011 (02_FAULT_TRACKING, FR-6) — a technician GPS update is broadcast in real time to every
 * subscribed WebSocket client.
 *
 * <p><b>Transport reality — this is not a STOMP application.</b> The row's Pre-Conditions say
 * "STOMP WebSocket. Admin subscribed to /topic/locations", which does not describe this codebase.
 * {@code WebSocketConfig} is a plain {@code @EnableWebSocket} / {@code WebSocketConfigurer}
 * registration — <b>not</b> {@code @EnableWebSocketMessageBroker} — and registers two raw
 * {@link org.springframework.web.socket.handler.TextWebSocketHandler}s:
 * {@code /ws/location} ({@link LocationWebSocketHandler}) and {@code /ws/notifications}
 * ({@link NotificationWebSocketHandler}). There is no message broker, no SockJS fallback, and no
 * {@code /topic/**} destination anywhere in the project — so there is nothing for a
 * {@code WebSocketStompClient} / {@code StompSession} to subscribe to. Fan-out is
 * {@code LocationWebSocketHandler.broadcastToAll}, which writes the JSON to every open session;
 * "subscribers" therefore means "every client connected to {@code /ws/location}". This test uses a
 * raw {@link StandardWebSocketClient} accordingly.</p>
 *
 * <p><b>Authentication.</b> {@code SecurityConfig} does not exempt {@code /ws/**}, so the handshake
 * falls under {@code .anyRequest().authenticated()} and the upgrade request must carry a Bearer
 * token like any other HTTP call. That header is set on the handshake below.</p>
 *
 * <p><b>Why RANDOM_PORT and not MockMvc.</b> A WebSocket upgrade needs a real servlet container;
 * MockMvc cannot perform one. That means the server side runs on its own threads and outside the
 * test's transaction, so {@code @Transactional} rollback does not apply — every row this test
 * creates is deleted explicitly in {@link #cleanUp()}.</p>
 *
 * <p>The trigger is the REST path the row describes (a technician posting a GPS fix), which reaches
 * the broadcast through {@code LocationService.updateLocation} ->
 * {@code WebSocketEventPublisher.publishLocationUpdate} ->
 * {@code LocationWebSocketHandler.broadcastLocationUpdate}. The endpoint is
 * {@code POST /api/location/update}, not the {@code POST /api/locations} the row names.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FaultWebSocketTest {

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider             jwt;
    @Autowired private UserRepository               userRepo;
    @Autowired private TechnicianLocationRepository locationRepo;
    @Autowired private TestRestTemplate             rest;
    @Autowired private ObjectMapper                 json;

    private static final Long   REAL_BRANCH_ID = 1L;
    private static final double LAT = 6.9271;
    private static final double LNG = 79.8612;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private User technician;

    /** Collects every text frame the server pushes to this client. */
    private static class RecordingHandler extends TextWebSocketHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }

    @BeforeEach
    void createTechnician() {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername("wsu" + n);
        u.setPasswordHash("x");
        u.setFirstName("Ws");
        u.setLastName("Tech");
        u.setFullName("WS Tech " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(User.Role.TECHNICIAN);
        u.setOpmcId(REAL_BRANCH_ID);
        technician = userRepo.save(u);
    }

    /**
     * RANDOM_PORT runs the server outside this test's transaction, so nothing rolls back —
     * remove the rows this test committed to the shared dev database.
     */
    @AfterEach
    void cleanUp() {
        if (technician == null) return;
        List<TechnicianLocation> mine = locationRepo.findAll().stream()
            .filter(l -> l.getUser() != null && technician.getId().equals(l.getUser().getId()))
            .toList();
        locationRepo.deleteAll(mine);
        userRepo.deleteById(technician.getId());
    }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_BRANCH_ID);
    }

    /** Waits for the first frame whose {@code type} matches, up to the given timeout. */
    private JsonNode awaitMessage(RecordingHandler handler, String type, long timeout, TimeUnit unit)
            throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            long remainingMs = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            String payload = handler.messages.poll(remainingMs, TimeUnit.MILLISECONDS);
            if (payload == null) return null;
            JsonNode node = json.readTree(payload);
            if (type.equals(node.path("type").asText())) return node;
        }
        return null;
    }

    @Test
    void statusChange_broadcastsToSubscribers() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.AUTHORIZATION, bearer(technician.getId(), "TECHNICIAN"));

        // ── Step 1: subscribe — connect to the real /ws/location endpoint ────────────────
        // (the project's equivalent of the row's "/topic/locations" STOMP subscription)
        WebSocketSession session;
        try {
            session = new StandardWebSocketClient()
                .execute(handler, handshakeHeaders,
                    URI.create("ws://localhost:" + port + "/ws/location"))
                .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(
                "Could not open a WebSocket session to ws://localhost:" + port + "/ws/location — "
                    + "the handshake must succeed for an authenticated technician. Cause: " + e, e);
        }

        try {
            assertTrue(session.isOpen(), "The WebSocket session must be open after the handshake");

            // The handler greets every new session; receiving it proves the connection is
            // registered in LocationWebSocketHandler.sessions, i.e. it will receive broadcasts.
            JsonNode welcome = awaitMessage(handler, WebSocketMessage.TYPE_CONNECTED, 5, TimeUnit.SECONDS);
            assertNotNull(welcome,
                "Expected the CONNECTED greeting LocationWebSocketHandler sends on connect");

            // ── Step 2: the technician posts a GPS fix over REST ─────────────────────────
            HttpHeaders postHeaders = new HttpHeaders();
            postHeaders.setContentType(MediaType.APPLICATION_JSON);
            postHeaders.add(HttpHeaders.AUTHORIZATION, bearer(technician.getId(), "TECHNICIAN"));

            LocationUpdateRequest ping = LocationUpdateRequest.builder()
                .latitude(LAT)
                .longitude(LNG)
                .address("Colombo 03")
                .status("TRAVELLING")
                .build();

            ResponseEntity<String> posted = rest.exchange(
                "/api/location/update", HttpMethod.POST,
                new HttpEntity<>(json.writeValueAsString(ping), postHeaders), String.class);

            assertEquals(200, posted.getStatusCode().value(),
                "The GPS update must be accepted. Body: " + posted.getBody());

            // ── Step 3: the broadcast must arrive within 5 seconds ───────────────────────
            JsonNode broadcast = awaitMessage(
                handler, WebSocketMessage.TYPE_LOCATION_UPDATE, 5, TimeUnit.SECONDS);
            assertNotNull(broadcast,
                "No LOCATION_UPDATE frame was broadcast within 5s of the REST update. Frames "
                    + "received: " + handler.messages);

            // ── Steps 4-6: the payload identifies the technician and carries the coords ──
            assertEquals(String.valueOf(technician.getId()), broadcast.path("senderId").asText(),
                "The broadcast must identify the technician who moved. Frame: " + broadcast);

            JsonNode data = broadcast.path("data");
            assertFalse(data.isMissingNode(), "The broadcast must carry a data payload: " + broadcast);
            assertEquals(String.valueOf(technician.getId()), data.path("technicianId").asText(),
                "Frame: " + broadcast);
            assertEquals(LAT, data.path("latitude").asDouble(), 0.00001,
                "Broadcast latitude must match the posted fix. Frame: " + broadcast);
            assertEquals(LNG, data.path("longitude").asDouble(), 0.00001,
                "Broadcast longitude must match the posted fix. Frame: " + broadcast);

        } finally {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }
    }
}
