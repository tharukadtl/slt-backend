package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.MaterialRepository;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RES-009 (05_RESOURCE_MGMT, FR-15) — the material request submit-then-approve workflow driven as
 * an API collection.
 *
 * <p><b>Tool substitution.</b> The row maps to a Newman/Postman artifact,
 * {@code SLT_Resources_Collection.json → MaterialRequest_Submit_And_Approve}. That collection is
 * checked in at {@code fieldops/src/test/postman/SLT_Resources_Collection.json} so CI can run it once
 * {@code newman} and a live server are available; neither is available in this environment (newman
 * is not installed and nothing listens on :8080), so it cannot produce a verdict on its own. This
 * class is the executable twin — the same two requests, the same {@code pm.test} assertions — driven
 * through the REAL filter chain with MockMvc against the real database. Same precedent as AUTH-009's
 * {@code SltAuthCollectionTest}, FAULT-016's {@link SltFaultsCollectionTest} and JOB-017's
 * {@link SltJobsCollectionTest}.</p>
 *
 * <p>It additionally asserts the checked-in collection file exists and declares the mapped folder
 * name, so the artifact and its twin cannot silently drift apart.</p>
 *
 * <p><b>Endpoints and status code.</b> The row posts to {@code /api/material-requests} and expects
 * 201; the implemented routes are {@code POST /api/inventory/material-request} (singular, returning
 * 200) and {@code POST /api/inventory/material-requests/{id}/approve}. See
 * {@link MaterialRequestIntegrationTest} for the full write-up of that deviation — both this class
 * and the collection drive the real routes and assert a 2xx on submit.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SltResourcesCollectionTest {

    @Autowired private MockMvc            mvc;
    @Autowired private JwtTokenProvider   jwt;
    @Autowired private UserRepository     userRepo;
    @Autowired private MaterialRepository materialRepo;
    @Autowired private ObjectMapper       json;

    private static final Long BRANCH_ID = 1L;
    private static final Path COLLECTION =
        Path.of("src", "test", "postman", "SLT_Resources_Collection.json");

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    /** Mirrors the collection's {{baseUrl}}/{{techToken}}/{{adminToken}}/{{reqId}} variables. */
    private final Map<String, String> environment = new HashMap<>();

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, BRANCH_ID);
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
        u.setOpmcId(BRANCH_ID);
        return userRepo.save(u);
    }

    private Material newMaterial() {
        Material m = new Material();
        m.setName("Fibre Drop Cable");
        m.setSku("SKU-" + uniq());
        m.setUnit("m");
        m.setUnitPrice(BigDecimal.valueOf(120));
        m.setCurrentStock(BigDecimal.valueOf(40));
        m.setMinimumThreshold(BigDecimal.TEN);
        m.setChargeType(Material.ChargeType.CHARGEABLE);
        m.setOpmcId(BRANCH_ID);
        m.setIsActive(true);
        return materialRepo.save(m);
    }

    @Test
    void materialRequestSubmitAndApprove_collectionTwin() throws Exception {
        // ── The checked-in artifact the row maps to must exist and name this folder ──────────
        assertTrue(Files.exists(COLLECTION),
            "The Newman collection this row maps to must be checked in at "
                + COLLECTION.toAbsolutePath());
        String collectionJson = Files.readString(COLLECTION);
        assertTrue(collectionJson.contains("MaterialRequest_Submit_And_Approve"),
            "The collection must declare the folder the row maps to");

        // ── Fixture, i.e. the collection's environment ──────────────────────────────────────
        User tech  = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User admin = newUser(User.Role.ADMIN, "Admin Amelia");
        Material material = newMaterial();
        userRepo.flush();
        materialRepo.flush();

        environment.put("techToken",  bearer(tech.getId(),  "TECHNICIAN"));
        environment.put("adminToken", bearer(admin.getId(), "ADMIN"));
        environment.put("materialId", String.valueOf(material.getId()));

        // ── Request 1: POST /api/inventory/material-request ─────────────────────────────────
        MvcResult submit = mvc.perform(post("/api/inventory/material-request")
                .header("Authorization", environment.get("techToken"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"materialId\":" + environment.get("materialId")
                    + ",\"quantity\":3}],\"taskId\":\"42\",\"urgency\":\"HIGH\","
                    + "\"notes\":\"Needed for job 42\"}"))
            .andReturn();

        String submitBody = submit.getResponse().getContentAsString();

        // pm.test('2xx PENDING')
        assertTrue(submit.getResponse().getStatus() >= 200 && submit.getResponse().getStatus() < 300,
            "Submit must succeed. Status " + submit.getResponse().getStatus()
                + ", body: " + submitBody);
        JsonNode submitted = json.readTree(submitBody);
        assertEquals("PENDING", submitted.path("status").asText(),
            "A newly submitted request must be PENDING, body: " + submitBody);

        // pm.test('attributed to the submitting technician')
        assertEquals(tech.getId().longValue(), submitted.path("requesterId").asLong(),
            "The request must be attributed to the submitting technician, body: " + submitBody);

        // pm.environment.set('reqId', ...)
        environment.put("reqId", String.valueOf(submitted.path("id").asLong()));

        // ── Request 2: POST /api/inventory/material-requests/{{reqId}}/approve ──────────────
        MvcResult approve = mvc.perform(
                post("/api/inventory/material-requests/{id}/approve", environment.get("reqId"))
                    .header("Authorization", environment.get("adminToken"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"notes\":\"Approved - in stock\",\"notifyRequester\":true}"))
            .andReturn();

        String approveBody = approve.getResponse().getContentAsString();

        // pm.test('200 APPROVED')
        assertEquals(200, approve.getResponse().getStatus(),
            "Approve must return 200. Body: " + approveBody);
        JsonNode approved = json.readTree(approveBody);
        assertEquals("APPROVED", approved.path("status").asText(),
            "The approved request must come back APPROVED, body: " + approveBody);

        // pm.test('reviewer recorded')
        assertEquals(admin.getId().longValue(), approved.path("reviewerId").asLong(),
            "The reviewing admin must be recorded, body: " + approveBody);
        assertFalse(approved.path("reviewedAt").isNull(),
            "The review must be timestamped, body: " + approveBody);
    }
}
