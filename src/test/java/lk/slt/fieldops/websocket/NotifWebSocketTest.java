package lk.slt.fieldops.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.Notification;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.NotificationRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOTIF-004 (08_NOTIFICATIONS, FR-22) — a fault status change must be pushed in real time to the
 * Admin dashboard over the notification WebSocket.
 *
 * <p><b>Transport reality — this is not a STOMP application.</b> The row's Pre-Conditions say
 * "STOMP WS. Admin subscribed to /topic/faults", which does not describe this codebase.
 * {@code WebSocketConfig} is a plain {@code @EnableWebSocket} / {@code WebSocketConfigurer}
 * registration — <b>not</b> {@code @EnableWebSocketMessageBroker} — so there is no broker and no
 * {@code /topic/**} destination for a {@code WebSocketStompClient} to subscribe to. The real
 * equivalent of "the admin is subscribed to fault events" is: connect to {@code /ws/notifications}
 * ({@link NotificationWebSocketHandler}) and send the {@code REGISTER} frame carrying
 * {@code senderRole: "admin"}, which is exactly what the Web Admin portal does on connect
 * ({@code frontend-admin/src/context/NotificationSocketContext.js}) and what buckets the session
 * into {@code roleUserMap} for {@code sendToRole("admin", …)} fan-out. This test does the same.
 * Same substitution and rationale as {@link FaultWebSocketTest} (FAULT-011), which covers the
 * location socket.</p>
 *
 * <p><b>Authentication.</b> {@code SecurityConfig} does not exempt {@code /ws/**}, so the handshake
 * falls under {@code .anyRequest().authenticated()} and the upgrade request must carry a Bearer
 * token like any other HTTP call.</p>
 *
 * <p><b>Why RANDOM_PORT and not MockMvc.</b> A WebSocket upgrade needs a real servlet container.
 * That puts the server on its own threads and outside the test's transaction, so
 * {@code @Transactional} rollback does not apply — every row this test creates is deleted
 * explicitly in {@link #cleanUp()}.</p>
 *
 * <p><b>Status vocabulary.</b> The row PATCHes straight to {@code IN_PROGRESS}.
 * {@code FaultService.validateTransition} only allows {@code REPORTED → ASSIGNED → IN_PROGRESS}, so
 * the fixture fault is seeded as {@code ASSIGNED} and the single transition under test is
 * {@code ASSIGNED → IN_PROGRESS} — the row's status change, from the only state it is legal in.</p>
 *
 * <p><b>Where this is expected to be red.</b> {@code FaultService} has no
 * {@link WebSocketEventPublisher} dependency at all (its five constructor arguments are four
 * repositories and {@code NotificationService}), so {@code updateStatus} broadcasts nothing.
 * {@code NotificationWebSocketHandler} does have the machinery — {@code notifyFaultUpdate} and
 * {@code sendToRole("admin", …)} both exist — but on the fault-status path nothing calls either.
 * The persisted, customer-facing side of the same event is asserted too, since that half does work
 * and pinpoints the gap as "no live broadcast" rather than "no notification at all".</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotifWebSocketTest {

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider        jwt;
    @Autowired private UserRepository          userRepo;
    @Autowired private FaultRepository         faultRepo;
    @Autowired private FaultHistoryRepository  historyRepo;
    @Autowired private NotificationRepository  notificationRepo;
    @Autowired private ObjectMapper            json;

    /**
     * {@code TestRestTemplate}'s default {@code SimpleClientHttpRequestFactory} is built on
     * {@code HttpURLConnection}, which rejects PATCH outright ("Invalid HTTP method: PATCH") — and
     * the endpoint under test is a PATCH. Apache HttpClient is not a dependency of this module, so
     * the JDK's own {@link HttpClient} is used for the trigger call instead of adding one.
     */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final Long REAL_BRANCH_ID = 1L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private User  admin;
    private User  customer;
    private Fault fault;

    /** Collects every text frame the server pushes to this client. */
    private static class RecordingHandler extends TextWebSocketHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }

    private User newUser(User.Role role, String prefix) {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername(prefix + n);
        u.setPasswordHash("x");
        u.setFirstName("Ws");
        u.setLastName(role.name());
        u.setFullName("WS " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_BRANCH_ID);
        return userRepo.save(u);
    }

    @BeforeEach
    void seedAssignedFault() {
        admin    = newUser(User.Role.ADMIN,  "wsa");
        customer = newUser(User.Role.CLIENT, "wsc");

        Fault f = new Fault();
        f.setFaultNumber("FLT-WS-" + SEQ.incrementAndGet());
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("No internet since 08:00 — WebSocket broadcast fixture");
        f.setPriority(Fault.FaultPriority.HIGH);
        // ASSIGNED is the only state IN_PROGRESS is a legal transition from.
        f.setStatus(Fault.FaultStatus.ASSIGNED);
        fault = faultRepo.save(f);
    }

    /**
     * RANDOM_PORT runs the server outside this test's transaction, so nothing rolls back —
     * remove the rows this test committed to the shared dev database.
     */
    @AfterEach
    void cleanUp() {
        if (fault != null) {
            List<FaultHistory> history = historyRepo.findByFaultId(fault.getId());
            historyRepo.deleteAll(history);
            faultRepo.deleteById(fault.getId());
        }
        if (customer != null) {
            notificationRepo.deleteAll(
                notificationRepo.findByRecipientIdOrderByCreatedAtDesc(customer.getId()));
            userRepo.deleteById(customer.getId());
        }
        if (admin != null) {
            notificationRepo.deleteAll(
                notificationRepo.findByRecipientIdOrderByCreatedAtDesc(admin.getId()));
            userRepo.deleteById(admin.getId());
        }
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
    void faultStatusChange_broadcastsToAdmin() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.AUTHORIZATION, bearer(admin.getId(), "ADMIN"));

        // ── Step 1: "subscribe" — connect to /ws/notifications and register as an admin ─────
        WebSocketSession session;
        try {
            session = new StandardWebSocketClient()
                .execute(handler, handshakeHeaders,
                    URI.create("ws://localhost:" + port + "/ws/notifications"))
                .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(
                "Could not open a WebSocket session to ws://localhost:" + port
                    + "/ws/notifications — the handshake must succeed for an authenticated admin. "
                    + "Cause: " + e, e);
        }

        try {
            assertTrue(session.isOpen(), "The WebSocket session must be open after the handshake");

            // The handler greets every new session; receiving it proves the connection is
            // registered in NotificationWebSocketHandler.sessions.
            JsonNode welcome =
                awaitMessage(handler, WebSocketMessage.TYPE_CONNECTED, 5, TimeUnit.SECONDS);
            assertNotNull(welcome,
                "Expected the CONNECTED greeting NotificationWebSocketHandler sends on connect");

            // The portal's own REGISTER frame — this is what puts the session in roleUserMap
            // under "admin" and userSessionMap under the admin's id, i.e. what makes both
            // sendToRole("admin", …) and sendToUser(adminId, …) deliverable.
            session.sendMessage(new TextMessage(json.writeValueAsString(
                WebSocketMessage.builder()
                    .type("REGISTER")
                    .senderId(String.valueOf(admin.getId()))
                    .senderRole("admin")
                    .build())));

            // Give the server a moment to process REGISTER before the trigger fires, so a missing
            // broadcast cannot be blamed on a race.
            Thread.sleep(500);

            // ── Step 2: PATCH /api/faults/{id}/status {"newStatus":"IN_PROGRESS"} ───────────
            HttpRequest patch = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/faults/" + fault.getId()
                    + "/status"))
                .header("Content-Type", "application/json")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin.getId(), "ADMIN"))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                    "{\"newStatus\":\"IN_PROGRESS\"}"))
                .build();

            HttpResponse<String> patched = HTTP.send(patch, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, patched.statusCode(),
                "The status change must be accepted. Body: " + patched.body());
            assertTrue(patched.body() != null && patched.body().contains("IN_PROGRESS"),
                "The fault must now be IN_PROGRESS. Body: " + patched.body());

            // ── The half that does work: the customer's persisted notification ──────────────
            List<Notification> customerRows =
                notificationRepo.findByRecipientIdOrderByCreatedAtDesc(customer.getId());
            assertFalse(customerRows.isEmpty(),
                "Precondition — FaultService.notifyCustomerOfStatusChange must have persisted a "
                    + "notification for the reporting customer");

            // ── Steps 3-5: the admin's live frame, within 5 seconds ─────────────────────────
            JsonNode broadcast =
                awaitMessage(handler, WebSocketMessage.TYPE_NOTIFICATION, 5, TimeUnit.SECONDS);
            assertNotNull(broadcast,
                "No NOTIFICATION frame reached the registered admin within 5s of the fault status "
                    + "change. FaultService has no WebSocketEventPublisher dependency at all, so "
                    + "updateStatus broadcasts nothing — the Admin dashboard only learns of a "
                    + "status change when it next polls or the page is reloaded. "
                    + "NotificationWebSocketHandler.notifyFaultUpdate(customerId, faultId, message) "
                    + "and sendToRole(\"admin\", …) both already exist and are used elsewhere "
                    + "(payment submission, material requests, attendance) — the fault-status path "
                    + "simply never calls either. Frames received: " + handler.messages);

            JsonNode data = broadcast.path("data");
            assertFalse(data.isMissingNode(),
                "The broadcast must carry a data payload: " + broadcast);
            String payload = broadcast.toString();
            assertTrue(payload.contains(String.valueOf(fault.getId()))
                    || payload.contains(fault.getFaultNumber()),
                "Step 4: the frame must identify the fault that changed (" + fault.getId()
                    + " / " + fault.getFaultNumber() + "). Frame: " + broadcast);
            assertTrue(payload.contains("IN_PROGRESS"),
                "Step 5: the frame must carry the new status IN_PROGRESS. Frame: " + broadcast);

        } finally {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }
    }
}
