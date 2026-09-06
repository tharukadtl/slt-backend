package lk.slt.fieldops.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.MaterialRequestDTO;
import lk.slt.fieldops.dto.ReviewPaymentRequest;
import lk.slt.fieldops.dto.SubmitPaymentRequest;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA_Compliance_Consolidated_Report.md — genuine end-to-end re-verification of NOTIF-001, NOTIF-002,
 * and RES-015 now that the Sec-WebSocket-Protocol auth fix (Stage G Major, resolved 2026-08-28) means a
 * real browser can actually hold an authenticated, registered {@code /ws/notifications} connection.
 *
 * <p><b>Why this test exists, not just re-reading the code.</b> All three findings were originally
 * scored Major on the assumption "delivered live to a connected client" was at least a real fallback —
 * an assumption that was true on paper ({@code WebSocketEventPublisher.sendToUser}/{@code sendToRole}
 * calls genuinely exist at each site) but was never actually reachable by a real Web Admin Portal
 * browser, since no browser could authenticate the socket at all. Now that the transport is fixed, the
 * only way to know whether these three deliver for real — as opposed to merely having a plausible-looking
 * call site — is to connect a client the same way a real browser now does (token in
 * {@code Sec-WebSocket-Protocol}, not the {@code Authorization} header a Java test client could always
 * set) and observe whether the frame actually arrives when the real action fires.</p>
 *
 * <p><b>Deliberately not using {@code Authorization}-header auth here</b> (unlike
 * {@link NotifWebSocketTest}, which predates this fix and tests the NOTIF-004 gap specifically) —
 * that header was never the browser's blocker in the first place (a Java client could always set it),
 * so testing through it would prove nothing new about this fix.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationDeliveryReverificationTest {

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider        jwt;
    @Autowired private UserRepository          userRepo;
    @Autowired private WorkGroupRepository     workGroupRepo;
    @Autowired private OpmcRepository          opmcRepo;
    @Autowired private FaultRepository         faultRepo;
    @Autowired private FaultHistoryRepository  faultHistoryRepo;
    @Autowired private JobRepository           jobRepo;
    @Autowired private PaymentRepository       paymentRepo;
    @Autowired private MaterialRequestRepository materialRequestRepo;
    @Autowired private MaterialRepository      materialRepo;
    @Autowired private NotificationRepository  notificationRepo;
    @Autowired private ObjectMapper            json;

    private static final Long REAL_OPMC_ID = 1L;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private final List<User> createdUsers = new ArrayList<>();
    private final List<Fault> createdFaults = new ArrayList<>();
    private final List<Job> createdJobs = new ArrayList<>();
    private final List<Payment> createdPayments = new ArrayList<>();
    private final List<MaterialRequest> createdMaterialRequests = new ArrayList<>();
    private final List<Material> createdMaterials = new ArrayList<>();
    private final List<WorkGroup> createdWorkGroups = new ArrayList<>();
    private final List<Opmc> createdOpmcs = new ArrayList<>();

    private static class RecordingHandler extends TextWebSocketHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }

    private User newUser(User.Role role, String prefix) {
        long n = uniq();
        User u = new User();
        u.setUsername(prefix + n);
        u.setPasswordHash("x");
        u.setFirstName("Reverify");
        u.setLastName(role.name());
        u.setFullName("Reverify " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_OPMC_ID);
        User saved = userRepo.save(u);
        createdUsers.add(saved);
        return saved;
    }

    // Same established pattern as InventoryIntegrationTest/MaterialRequestIntegrationTest/
    // MaterialRequestRejectRoleRestrictionTest/ResourceAllocationHierarchyTrackingTest/
    // SltResourcesCollectionTest -- a real Material row, not a hardcoded materialId(1L) that
    // only ever existed on the old shared dev database.
    private Material newMaterial(String name, int stock) {
        Material m = new Material();
        m.setName(name);
        m.setSku("SKU-" + uniq());
        m.setUnit("m");
        m.setUnitPrice(BigDecimal.valueOf(120));
        m.setCurrentStock(BigDecimal.valueOf(stock));
        m.setMinimumThreshold(BigDecimal.TEN);
        m.setChargeType(Material.ChargeType.CHARGEABLE);
        m.setOpmcId(REAL_OPMC_ID);
        m.setIsActive(true);
        Material saved = materialRepo.save(m);
        createdMaterials.add(saved);
        return saved;
    }

    // Creates its own OPMC fixture rather than assuming a pre-existing OPMC id=1 -- that only
    // ever held against the old shared dev database and throws NoSuchElementException against a
    // genuinely fresh, empty one.
    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("Reverify OPMC " + n);
        o.setCode("RV" + n);
        o.setAddress("123 Test Road");
        Opmc saved = opmcRepo.save(o);
        createdOpmcs.add(saved);
        return saved;
    }

    private String bearer(Long userId, String role) {
        return jwt.createAccessToken(userId, "user" + userId, role, REAL_OPMC_ID);
    }

    /** Connects exactly the way a real browser now does post-fix: token as the sole Sec-WebSocket-Protocol entry. */
    private WebSocketSession connectAndRegister(RecordingHandler handler, Long userId, String role, String token)
            throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of(token));
        WebSocketSession session = new StandardWebSocketClient()
            .execute(handler, headers, URI.create("ws://localhost:" + port + "/ws/notifications"))
            .get(10, TimeUnit.SECONDS);
        assertTrue(session.isOpen(), "Handshake must succeed via Sec-WebSocket-Protocol, matching a real browser");
        // Wait for the CONNECTED greeting before registering, same order the real frontend uses.
        awaitMessage(handler, WebSocketMessage.TYPE_CONNECTED, 5, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage(json.writeValueAsString(
            WebSocketMessage.builder().type("REGISTER").senderId(String.valueOf(userId)).senderRole(role).build())));
        Thread.sleep(400); // let REGISTER land before the trigger fires, avoiding a race
        return session;
    }

    private JsonNode awaitMessage(RecordingHandler handler, String type, long timeout, TimeUnit unit) throws Exception {
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

    @AfterEach
    void cleanUp() {
        for (MaterialRequest r : createdMaterialRequests) materialRequestRepo.deleteById(r.getId());
        for (Material m : createdMaterials) materialRepo.deleteById(m.getId());
        for (Payment p : createdPayments) paymentRepo.deleteById(p.getId());
        for (Job j : createdJobs) jobRepo.deleteById(j.getId());
        // Same root cause as FaultTransferToAdminJobCleanupLiveTest: assignFault
        // (FaultAssignmentService.java:163) unconditionally writes a real fault_history row (a
        // genuine @ManyToOne FK to the fault) -- must go before the Fault delete below or the
        // real, committed database rejects it with a foreign-key violation.
        for (Fault f : createdFaults) faultHistoryRepo.deleteAll(faultHistoryRepo.findByFaultId(f.getId()));
        for (Fault f : createdFaults) faultRepo.deleteById(f.getId());
        for (WorkGroup w : createdWorkGroups) workGroupRepo.deleteById(w.getId());
        for (Opmc o : createdOpmcs) opmcRepo.deleteById(o.getId());
        for (User u : createdUsers) {
            notificationRepo.deleteAll(notificationRepo.findByRecipientIdOrderByCreatedAtDesc(u.getId()));
            userRepo.deleteById(u.getId());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NOTIF-001 — fault assignment -> Team Lead
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void faultAssignment_nowDeliversLiveToConnectedTeamLead() throws Exception {
        User admin    = newUser(User.Role.ADMIN, "rvfa_admin");
        User teamLead = newUser(User.Role.TEAM_LEAD, "rvfa_tl");
        User customer = newUser(User.Role.CLIENT, "rvfa_cust");

        Opmc opmc = newOpmc();
        WorkGroup wg = new WorkGroup();
        wg.setName("Reverify WG " + uniq());
        wg.setOpmc(opmc);
        wg.setTeamLead(teamLead);
        wg.setIsActive(true);
        wg = workGroupRepo.save(wg);
        createdWorkGroups.add(wg);

        Fault f = new Fault();
        f.setFaultNumber("FLT-RV-" + uniq());
        f.setOpmcId(opmc.getId());
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("NOTIF-001 live-delivery re-verification");
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.REPORTED);
        f = faultRepo.save(f);
        createdFaults.add(f);

        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connectAndRegister(handler, teamLead.getId(), "team_lead", bearer(teamLead.getId(), "TEAM_LEAD"));
        try {
            String body = "{\"workGroupId\":" + wg.getId() + "}";
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/faults/" + f.getId() + "/assign"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer(admin.getId(), "ADMIN"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode(), "Assign must succeed. Body: " + res.body());

            JsonNode frame = awaitMessage(handler, WebSocketMessage.TYPE_NOTIFICATION, 5, TimeUnit.SECONDS);
            assertNotNull(frame,
                "NOTIF-001: fault assignment must now deliver live to a real, correctly-authenticated, "
                    + "connected Team Lead session. Frames received: " + handler.messages);
            String payload = frame.toString();
            assertTrue(payload.contains("FAULT_ASSIGNED"),
                "Frame must be typed FAULT_ASSIGNED. Frame: " + frame);
        } finally {
            if (session.isOpen()) session.close(CloseStatus.NORMAL);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NOTIF-002 — payment approval -> Team Lead
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void paymentApproval_nowDeliversLiveToConnectedTeamLead() throws Exception {
        User admin    = newUser(User.Role.ADMIN, "rvpay_admin");
        User teamLead = newUser(User.Role.TEAM_LEAD, "rvpay_tl");
        User customer = newUser(User.Role.CLIENT, "rvpay_cust");

        Fault f = new Fault();
        f.setFaultNumber("FLT-RVPAY-" + uniq());
        f.setOpmcId(REAL_OPMC_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("NOTIF-002 live-delivery re-verification");
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.COMPLETED);
        f = faultRepo.save(f);
        createdFaults.add(f);

        Job j = new Job();
        j.setJobNumber("JOB-RVPAY-" + uniq());
        j.setFaultId(f.getId());
        j.setFaultNumber(f.getFaultNumber());
        j.setCustomerId(customer.getId());
        j.setCustomerName(customer.getFullName());
        j.setTeamLeadId(teamLead.getId());
        j.setTeamLeadName(teamLead.getFullName());
        j.setStatus(Job.JobStatus.COMPLETED);
        j.setPriority(Job.JobPriority.MEDIUM);
        j = jobRepo.save(j);
        createdJobs.add(j);

        SubmitPaymentRequest submit = new SubmitPaymentRequest();
        submit.setJobId(j.getId());
        submit.setMaterialsFocTotal(new BigDecimal("0.00"));
        submit.setMaterialsChargeableTotal(new BigDecimal("0.00"));
        submit.setLabourCharge(new BigDecimal("1000.00"));
        submit.setMaterialJustification("None");
        submit.setWorkSummary("Re-verification fixture");
        submit.setCustomerSignatureUrl("data:image/png;base64,X");

        HttpRequest submitReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/payments"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + bearer(teamLead.getId(), "TEAM_LEAD"))
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(submit)))
            .build();
        HttpResponse<String> submitRes = HTTP.send(submitReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, submitRes.statusCode(), "Submit must succeed. Body: " + submitRes.body());
        long paymentId = json.readTree(submitRes.body()).path("id").asLong();
        Payment saved = paymentRepo.findById(paymentId).orElseThrow();
        createdPayments.add(saved);

        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connectAndRegister(handler, teamLead.getId(), "team_lead", bearer(teamLead.getId(), "TEAM_LEAD"));
        try {
            ReviewPaymentRequest review = new ReviewPaymentRequest();
            review.setDecision("APPROVED");
            HttpRequest reviewReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/payments/" + paymentId + "/review"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer(admin.getId(), "ADMIN"))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json.writeValueAsString(review)))
                .build();
            HttpResponse<String> reviewRes = HTTP.send(reviewReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reviewRes.statusCode(), "Review must succeed. Body: " + reviewRes.body());

            JsonNode frame = awaitMessage(handler, WebSocketMessage.TYPE_NOTIFICATION, 5, TimeUnit.SECONDS);
            assertNotNull(frame,
                "NOTIF-002: payment approval must now deliver live to a real, correctly-authenticated, "
                    + "connected Team Lead session. Frames received: " + handler.messages);
            assertTrue(frame.toString().contains("PAYMENT_UPDATE"),
                "Frame must be typed PAYMENT_UPDATE. Frame: " + frame);
        } finally {
            if (session.isOpen()) session.close(CloseStatus.NORMAL);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RES-015 — material request approval -> requester
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void materialRequestApproval_nowDeliversLiveToConnectedRequester() throws Exception {
        User admin      = newUser(User.Role.ADMIN, "rvmr_admin");
        User technician = newUser(User.Role.TECHNICIAN, "rvmr_tech");

        Material material = newMaterial("Reverify Cable " + uniq(), 40);
        MaterialRequestDTO.RequestItemDTO item = MaterialRequestDTO.RequestItemDTO.builder()
            .materialId(material.getId()).quantity(1).build();
        MaterialRequestDTO.SubmitRequest submit = MaterialRequestDTO.SubmitRequest.builder()
            .items(List.of(item)).urgency("NORMAL").notes("NOTIF re-verification fixture").build();

        HttpRequest submitReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/inventory/material-request"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + bearer(technician.getId(), "TECHNICIAN"))
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(submit)))
            .build();
        HttpResponse<String> submitRes = HTTP.send(submitReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, submitRes.statusCode(), "Material request submit must succeed. Body: " + submitRes.body());
        long requestId = json.readTree(submitRes.body()).path("id").asLong();
        MaterialRequest saved = materialRequestRepo.findById(requestId).orElseThrow();
        createdMaterialRequests.add(saved);

        RecordingHandler handler = new RecordingHandler();
        WebSocketSession session = connectAndRegister(handler, technician.getId(), "technician", bearer(technician.getId(), "TECHNICIAN"));
        try {
            HttpRequest approveReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/inventory/material-requests/" + requestId + "/approve"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer(admin.getId(), "ADMIN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"notifyRequester\":true}"))
                .build();
            HttpResponse<String> approveRes = HTTP.send(approveReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, approveRes.statusCode(), "Approve must succeed. Body: " + approveRes.body());

            JsonNode frame = awaitMessage(handler, WebSocketMessage.TYPE_NOTIFICATION, 5, TimeUnit.SECONDS);
            assertNotNull(frame,
                "RES-015: material request approval must now deliver live to a real, correctly-authenticated, "
                    + "connected requester session. Frames received: " + handler.messages);
            assertTrue(frame.toString().contains("MATERIAL_REQUEST_APPROVED"),
                "Frame must be typed MATERIAL_REQUEST_APPROVED. Frame: " + frame);
        } finally {
            if (session.isOpen()) session.close(CloseStatus.NORMAL);
        }
    }
}
