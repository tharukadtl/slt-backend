package lk.slt.fieldops.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.NotificationRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA_Compliance_Consolidated_Report.md Stage G Major — "browser cannot authenticate
 * /ws/notifications and /ws/location" — plus the sibling Minor, {@code setAllowedOrigins("*")}.
 *
 * <p><b>Root cause, confirmed live before this fix.</b> A real browser {@code WebSocket()} cannot
 * attach an {@code Authorization} header, and the JWT lives in {@code localStorage}, not a cookie —
 * so no real browser client could ever authenticate this handshake at all. Live-verified via
 * Chrome DevTools Protocol earlier this session (handshake died every time), and again after this
 * fix (real login as a SUPER_ADMIN, real 101 handshake, real CONNECTED + REGISTER frames).</p>
 *
 * <p><b>Fix under test.</b> The token now travels as the sole entry of the
 * {@code Sec-WebSocket-Protocol} list — {@code new WebSocket(url, [token])}, the one piece of
 * custom data a browser's native WebSocket API can send at connect time — validated by
 * {@code SecurityConfig.jwtAuthFilter}'s third fallback source, then re-checked by
 * {@link lk.slt.fieldops.config.WebSocketAuthInterceptor}, which also echoes the requested
 * subprotocol back verbatim (required by RFC 6455 or browsers abort the connection outright).
 * {@code Sec-WebSocket-Protocol} was chosen over a {@code ?token=} query parameter because query
 * strings land in access logs; this header generally doesn't.</p>
 *
 * <p><b>Why RANDOM_PORT and not MockMvc.</b> Same as {@link NotifWebSocketTest} — a WebSocket
 * upgrade needs a real servlet container, so rows this test creates are cleaned up explicitly
 * rather than relying on {@code @Transactional} rollback.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketAuthTest {

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider       jwt;
    @Autowired private UserRepository         userRepo;
    @Autowired private NotificationRepository notificationRepo;
    @Autowired private ObjectMapper           json;

    private static final Long REAL_OPMC_ID = 1L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private User admin;

    private static class RecordingHandler extends TextWebSocketHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }

    @BeforeEach
    void seedAdmin() {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername("wsauth" + n);
        u.setPasswordHash("x");
        u.setFirstName("Ws");
        u.setLastName("Auth");
        u.setFullName("WS Auth " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(User.Role.SUPER_ADMIN);
        u.setOpmcId(REAL_OPMC_ID);
        admin = userRepo.save(u);
    }

    @AfterEach
    void cleanUp() {
        if (admin != null) {
            notificationRepo.deleteAll(
                notificationRepo.findByRecipientIdOrderByCreatedAtDesc(admin.getId()));
            userRepo.deleteById(admin.getId());
        }
    }

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
    void validTokenAsSubprotocol_handshakeSucceedsAndEchoesSubprotocol() throws Exception {
        String token = jwt.createAccessToken(admin.getId(), admin.getUsername(), "SUPER_ADMIN", REAL_OPMC_ID);

        RecordingHandler handler = new RecordingHandler();
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        // The real browser-side mechanism under test: the token as the ONLY entry of
        // Sec-WebSocket-Protocol — no Authorization header is sent at all, matching exactly what
        // a real browser's native WebSocket(url, [token]) does.
        handshakeHeaders.setSecWebSocketProtocol(List.of(token));

        WebSocketSession session;
        try {
            session = new StandardWebSocketClient()
                .execute(handler, handshakeHeaders,
                    URI.create("ws://localhost:" + port + "/ws/notifications"))
                .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(
                "A valid token carried only via Sec-WebSocket-Protocol must be enough to complete "
                    + "the handshake — this is the exact mechanism a real browser uses. Cause: " + e, e);
        }

        try {
            assertTrue(session.isOpen(), "Session must be open — a valid subprotocol token must authenticate");
            assertEquals(token, session.getAcceptedProtocol(),
                "The server must echo the exact requested subprotocol back, or real browsers abort "
                    + "the connection per RFC 6455 — this is the trap WebSocketAuthInterceptor exists to avoid");

            JsonNode welcome = awaitMessage(handler, WebSocketMessage.TYPE_CONNECTED, 5, TimeUnit.SECONDS);
            assertNotNull(welcome, "Expected the CONNECTED greeting after a successful authenticated handshake");
        } finally {
            if (session.isOpen()) session.close(CloseStatus.NORMAL);
        }
    }

    @Test
    void noToken_handshakeRejected() {
        RecordingHandler handler = new RecordingHandler();
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();

        ExecutionException ex = assertThrows(ExecutionException.class, () ->
            new StandardWebSocketClient()
                .execute(handler, handshakeHeaders, URI.create("ws://localhost:" + port + "/ws/notifications"))
                .get(10, TimeUnit.SECONDS),
            "A handshake with no Authorization header, no ?token=, and no Sec-WebSocket-Protocol "
                + "must be rejected — WebSocketAuthInterceptor requires a populated SecurityContext");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("401"),
            "Expected a 401 handshake failure, got: " + ex.getMessage());
    }

    @Test
    void invalidTokenAsSubprotocol_handshakeRejected() {
        RecordingHandler handler = new RecordingHandler();
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.setSecWebSocketProtocol(List.of("not-a-real-jwt-at-all"));

        ExecutionException ex = assertThrows(ExecutionException.class, () ->
            new StandardWebSocketClient()
                .execute(handler, handshakeHeaders, URI.create("ws://localhost:" + port + "/ws/notifications"))
                .get(10, TimeUnit.SECONDS),
            "A garbage subprotocol value must not be treated as a valid token");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("401"),
            "Expected a 401 handshake failure, got: " + ex.getMessage());
    }
}
