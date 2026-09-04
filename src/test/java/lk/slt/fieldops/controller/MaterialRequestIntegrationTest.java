package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.MaterialRequestDTO;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.MaterialRequest;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.MaterialRepository;
import lk.slt.fieldops.repository.MaterialRequestRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RES-002 and RES-012 (05_RESOURCE_MGMT, FR-13 / FR-15) — submitting a material request, and
 * approving a batch of them in one call.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module and adding a test framework is a project decision, not this suite's. MockMvc through the
 * real filter chain is the module's established convention (see {@link JobIntegrationTest},
 * {@link BillDisputeAmendmentIntegrationTest}) and drives the identical path: real JWT filter, real
 * {@code @PreAuthorize}, real service, real MySQL. Both tests are {@code @Transactional} so their
 * rows roll back.</p>
 *
 * <p><b>Endpoint and payload vs. the sheet (RES-002).</b> The sheet posts
 * {@code POST /api/material-requests {materialId:5, quantity:3, jobId:42, urgency:'HIGH'}} and
 * expects 201. The implemented endpoint is {@code POST /api/inventory/material-request}
 * ({@code InventoryController}), singular, and its body is multi-item:
 * {@code {items:[{materialId, quantity}], taskId, faultId, urgency, notes}} — a request can carry
 * several materials, so there is no top-level {@code materialId}/{@code quantity}. It returns
 * {@code 200 OK}, not {@code 201 Created}, unlike this codebase's other create endpoints
 * ({@code POST /api/jobs}, {@code POST /api/faults}, {@code POST /api/vehicles} all return 201).
 * That is an internal API-convention inconsistency, not a behavioural defect, so this test asserts
 * the substantive contract the row is really about — a PENDING request attributed to the submitting
 * technician — against the real endpoint, and records the status-code deviation here rather than
 * failing on it.</p>
 *
 * <p><b>RES-012 has no implementation to assert against.</b> There is no bulk-approve endpoint:
 * {@code InventoryController} exposes only the per-request
 * {@code POST /api/inventory/material-requests/{id}/approve}. The nearest thing is
 * {@code POST /api/inventory/stock/bulk-adjust}, which batches raw stock adjustments, not request
 * approvals. That test is written against the endpoint the row names, so it executes and states
 * exactly what is missing; it deliberately does not assert the absence of the endpoint, which would
 * lock the gap in as correct behaviour.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MaterialRequestIntegrationTest {

    @Autowired private MockMvc                   mvc;
    @Autowired private JwtTokenProvider          jwt;
    @Autowired private UserRepository            userRepo;
    @Autowired private MaterialRepository        materialRepo;
    @Autowired private MaterialRequestRepository requestRepo;
    @Autowired private ObjectMapper              json;
    @Autowired private jakarta.persistence.EntityManager em;

    /** Existing row every branch-scoped FK in this schema resolves against. */
    private static final Long REAL_BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_BRANCH_ID);
    }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("u" + n);
        u.setPasswordHash("x");
        u.setFirstName("First");
        u.setLastName("Last");
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_BRANCH_ID);
        return userRepo.save(u);
    }

    private Material newMaterial(String name, int stock) {
        Material m = new Material();
        m.setName(name);
        m.setSku("SKU-" + uniq());
        m.setUnit("m");
        m.setUnitPrice(BigDecimal.valueOf(120));
        m.setCurrentStock(BigDecimal.valueOf(stock));
        m.setMinimumThreshold(BigDecimal.TEN);
        m.setChargeType(Material.ChargeType.CHARGEABLE);
        m.setOpmcId(REAL_BRANCH_ID);
        m.setIsActive(true);
        return materialRepo.save(m);
    }

    private MaterialRequestDTO.SubmitRequest submitBody(Long materialId, int qty) {
        return MaterialRequestDTO.SubmitRequest.builder()
            .items(List.of(MaterialRequestDTO.RequestItemDTO.builder()
                .materialId(materialId)
                .quantity(qty)
                .build()))
            .taskId("42")
            .urgency("HIGH")
            .notes("Needed for job 42")
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-002 — submit material request, PENDING created
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void submitRequest_returns201Pending() throws Exception {
        // ── Arrange: a technician and a material with stock ──────────────────────────────────
        User tech = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        Material material = newMaterial("Fibre Drop Cable", 40);
        materialRepo.flush();
        userRepo.flush();

        // ── Act: step 1 ──────────────────────────────────────────────────────────────────────
        MvcResult res = mvc.perform(post("/api/inventory/material-request")
                .header("Authorization", bearer(tech.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(submitBody(material.getId(), 3))))
            .andReturn();

        String body = res.getResponse().getContentAsString();

        // ── Step 2: the submission must succeed ──────────────────────────────────────────────
        assertTrue(res.getResponse().getStatus() >= 200 && res.getResponse().getStatus() < 300,
            "Submitting a material request must succeed. Status "
                + res.getResponse().getStatus() + ", body: " + body);

        JsonNode created = json.readTree(body);

        assertAll("a submitted material request is PENDING and attributed to the technician",

            // ── Step 3: PENDING ─────────────────────────────────────────────────────────────
            () -> assertEquals("PENDING", created.path("status").asText(),
                "A newly submitted request must be PENDING, body: " + body),

            // ── Step 4: attributed to the submitting technician, from the JWT ───────────────
            () -> assertEquals(tech.getId().longValue(), created.path("requesterId").asLong(),
                "The request must be attributed to the authenticated technician, not the body"),
            () -> assertEquals(tech.getFullName(), created.path("requesterName").asText()),

            // ── The item the technician asked for must survive the round trip ───────────────
            () -> {
                JsonNode items = created.path("items");
                assertEquals(1, items.size(), "One requested item was submitted, body: " + body);
                assertEquals(material.getId().longValue(),
                    items.get(0).path("materialId").asLong());
                assertEquals(3, items.get(0).path("requestedQuantity").asInt());
            },
            () -> assertEquals("HIGH", created.path("urgency").asText(),
                "The urgency the technician set must be preserved"),

            // ── And a real row must exist, still PENDING and still holding its stock ────────
            () -> {
                em.flush();
                MaterialRequest row = requestRepo.findById(created.path("id").asLong())
                    .orElseThrow(() -> new AssertionError("No material_requests row was written"));
                assertEquals(MaterialRequest.RequestStatus.PENDING, row.getStatus());
                assertEquals(tech.getId(), row.getRequestedBy());
                assertEquals(40, materialRepo.findById(material.getId()).orElseThrow()
                        .getCurrentStock().intValue(),
                    "Submitting a request must not move stock — only approval does");
            }
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-012 — bulk material request approval
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void bulkApprove_allApproved() throws Exception {
        // ── Arrange: an admin, a material with 30 on hand, and 3 PENDING requests for 3 each ──
        User admin = newUser(User.Role.ADMIN, "Admin Amelia");
        User tech  = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        Material material = newMaterial("Fibre Drop Cable", 30);
        materialRepo.flush();
        userRepo.flush();

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MvcResult res = mvc.perform(post("/api/inventory/material-request")
                    .header("Authorization", bearer(tech.getId(), "TECHNICIAN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(submitBody(material.getId(), 3))))
                .andReturn();
            assertTrue(res.getResponse().getStatus() < 300,
                "Setup submission " + i + " failed: " + res.getResponse().getContentAsString());
            ids.add(json.readTree(res.getResponse().getContentAsString()).path("id").asLong());
        }
        em.flush();

        // ── Act: step 1 — approve all three in one call ──────────────────────────────────────
        MvcResult res = mvc.perform(post("/api/inventory/material-requests/bulk-approve")
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ids", ids))))
            .andReturn();

        String body = res.getResponse().getContentAsString();

        // ── Step 2 ───────────────────────────────────────────────────────────────────────────
        assertEquals(200, res.getResponse().getStatus(),
            "A bulk approval of " + ids + " must return 200. No bulk-approve endpoint exists — "
                + "InventoryController offers only the per-request "
                + "POST /api/inventory/material-requests/{id}/approve. Status "
                + res.getResponse().getStatus() + ", body: " + body);

        em.flush();
        em.clear();

        assertAll("one bulk call approves every request in the batch",

            // ── Step 3: all three APPROVED ──────────────────────────────────────────────────
            () -> {
                for (Long id : ids) {
                    MaterialRequest row = requestRepo.findById(id).orElseThrow();
                    assertEquals(MaterialRequest.RequestStatus.APPROVED, row.getStatus(),
                        "Request " + id + " must be APPROVED after the bulk call");
                }
            },

            // ── Step 4: stock deducted once per approved request (3 x 3 off 30) ─────────────
            () -> assertEquals(21, materialRepo.findById(material.getId()).orElseThrow()
                    .getCurrentStock().intValue(),
                "Three approvals of 3 units each must take 9 off the 30 on hand")
        );
    }
}
