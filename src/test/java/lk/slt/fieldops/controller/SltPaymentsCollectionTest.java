package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.SubmitPaymentRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sheet {@code 04_PAYMENT_FLOW} row PAY-013 (FR-10 / FR-11) — the payment submit-then-approve
 * lifecycle driven as an API collection.
 *
 * <p><b>Tool substitution.</b> The row maps to a Newman/Postman artifact,
 * {@code SLT_Payments_Collection.json → Submit_Then_Approve_Lifecycle}. That collection is checked
 * in at {@code fieldops/src/test/postman/SLT_Payments_Collection.json} so CI can run it once
 * {@code newman} and a live server are available; neither is available in this environment (newman
 * is not installed and nothing listens on :8080), so it cannot produce a verdict on its own. This
 * class is the executable twin — the same four requests with the same {@code pm.test} assertions —
 * driven through the REAL filter chain with MockMvc against the real database. Same precedent as
 * AUTH-009's {@code SltAuthCollectionTest}, FAULT-016's {@link SltFaultsCollectionTest} and
 * JOB-017's {@link SltJobsCollectionTest}.</p>
 *
 * <p>It additionally asserts the checked-in collection file exists and declares the mapped folder
 * name, so the artifact and its twin cannot silently drift apart.</p>
 *
 * <p><b>Status vocabulary.</b> The row's script asserts {@code 'PENDING'} after submit and
 * {@code 'APPROVED'} after approve; {@code Payment.PaymentStatus} has neither constant. DRAFT is
 * the submitted-awaiting-review state ({@code GET /api/payments/pending} serves exactly it) and
 * FINAL is approved. The row's endpoint {@code PATCH /api/payments/{id}/approve} is really
 * {@code PATCH /api/payments/{id}/review} with a {@code {"decision":"APPROVED"}} body. The real
 * machine is what is driven here and in the collection.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SltPaymentsCollectionTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JobRepository    jobRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private UserRepository   userRepo;
    @Autowired private ObjectMapper     json;

    private static final Long BRANCH_ID = 1L;
    private static final Path COLLECTION =
        Path.of("src", "test", "postman", "SLT_Payments_Collection.json");

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

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
        u.setRole(role);
        // §A-4 (Cross-OPMC bill exposure) scoping added OpmcAccessGuard checks to /pending, /all and
        // /{id}/review; every payment in this fixture is BRANCH_ID, so give every user that same OPMC.
        u.setOpmcId(BRANCH_ID);
        return userRepo.save(u);
    }

    private Job newCompletedJob(User customer, User teamLead) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-PAYCOL-" + n);
        f.setOpmcId(BRANCH_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("Payment collection fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.COMPLETED);
        f = faultRepo.save(f);

        Job j = new Job();
        j.setJobNumber("JOB-PAYCOL-" + n);
        j.setFaultId(f.getId());
        j.setFaultNumber(f.getFaultNumber());
        j.setCustomerId(customer.getId());
        j.setCustomerName(customer.getFullName());
        j.setTeamLeadId(teamLead.getId());          // jobs.team_lead_id is NOT NULL
        j.setTeamLeadName(teamLead.getFullName());
        j.setStatus(Job.JobStatus.COMPLETED);
        j.setPriority(Job.JobPriority.MEDIUM);
        return jobRepo.save(j);
    }

    @Test
    void submitThenApproveLifecycle() throws Exception {
        // ── The checked-in collection must exist and declare the mapped folder ──
        assertTrue(Files.exists(COLLECTION),
            "The mapped Newman artifact must be checked in at " + COLLECTION.toAbsolutePath());
        String collection = Files.readString(COLLECTION);
        assertTrue(collection.contains("\"Submit_Then_Approve_Lifecycle\""),
            "The collection must declare the folder named in the row's Automation Mapping");
        assertTrue(collection.contains("/api/payments/{{paymentId}}/review"),
            "The collection must drive the real review endpoint");

        User customer = newUser(User.Role.CLIENT,    "Collection Client");
        User teamLead = newUser(User.Role.TEAM_LEAD, "Collection TL");
        User admin    = newUser(User.Role.ADMIN,     "Collection Admin");
        Job  job      = newCompletedJob(customer, teamLead);

        String tlHdr    = bearer(teamLead.getId(), "TEAM_LEAD");
        String adminHdr = bearer(admin.getId(),    "ADMIN");

        // ── Request 1: POST /api/payments → 201, status DRAFT (the row's "PENDING") ──
        SubmitPaymentRequest req = new SubmitPaymentRequest();
        req.setJobId(job.getId());
        req.setMaterialsFocTotal(new BigDecimal("1000.00"));
        req.setMaterialsChargeableTotal(new BigDecimal("800.00"));
        req.setLabourCharge(new BigDecimal("3750.00"));
        req.setMaterialJustification("Standard repair");
        req.setWorkSummary("Standard repair");
        req.setCustomerSignatureUrl("data:image/png;base64,B64SIGNATUREDATA");

        MvcResult submit = mvc.perform(post("/api/payments")
                .header("Authorization", tlHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        String submitBody = submit.getResponse().getContentAsString();
        assertEquals(201, submit.getResponse().getStatus(), "Body: " + submitBody);
        JsonNode submitted = json.readTree(submitBody);
        assertEquals("DRAFT", submitted.path("status").asText(),
            "pm.test('PENDING') — DRAFT is this codebase's awaiting-review state. Body: " + submitBody);
        assertEquals(0, new BigDecimal(submitted.path("totalAmount").asText())
                .compareTo(new BigDecimal("4550")),
            "Billed total must exclude the FOC 1000: 800 + 3750 = 4550. Body: " + submitBody);

        long paymentId = submitted.path("id").asLong();
        assertTrue(paymentId > 0, "pm.environment.set('paymentId', ...) needs an id. Body: " + submitBody);

        // ── Request 2: GET /api/payments/pending → the payment is queued ────────
        MvcResult queue = mvc.perform(get("/api/payments/pending").header("Authorization", adminHdr))
            .andReturn();
        assertEquals(200, queue.getResponse().getStatus(),
            "Body: " + queue.getResponse().getContentAsString());
        boolean queued = false;
        for (JsonNode p : json.readTree(queue.getResponse().getContentAsString())) {
            if (p.path("id").asLong() == paymentId) { queued = true; break; }
        }
        assertTrue(queued, "The submitted payment must appear in the admin review queue");

        // ── Request 3: PATCH /api/payments/{id}/review → 200, FINAL ─────────────
        MvcResult approve = mvc.perform(patch("/api/payments/{id}/review", paymentId)
                .header("Authorization", adminHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}"))
            .andReturn();

        String approveBody = approve.getResponse().getContentAsString();
        assertEquals(200, approve.getResponse().getStatus(), "Body: " + approveBody);
        JsonNode approved = json.readTree(approveBody);
        assertEquals("FINAL", approved.path("status").asText(),
            "pm.test('APPROVED') — FINAL is this codebase's approved state. Body: " + approveBody);
        assertEquals(0, new BigDecimal(approved.path("approvedAmount").asText())
                .compareTo(new BigDecimal("4550")),
            "approvedAmount must be the chargeable-only 4550. Body: " + approveBody);
        assertFalse(approved.path("billReference").asText().isBlank(),
            "Approval must mint a bill reference. Body: " + approveBody);

        // ── Request 4: GET /api/payments/{id}/approvals → APPROVED audit row ────
        MvcResult approvals = mvc.perform(get("/api/payments/{id}/approvals", paymentId)
                .header("Authorization", adminHdr))
            .andReturn();
        String approvalsBody = approvals.getResponse().getContentAsString();
        assertEquals(200, approvals.getResponse().getStatus(), "Body: " + approvalsBody);

        boolean audited = false;
        for (JsonNode a : json.readTree(approvalsBody)) {
            if ("APPROVED".equals(a.path("action").asText())) { audited = true; break; }
        }
        assertTrue(audited, "An APPROVED audit row must exist. Body: " + approvalsBody);
    }
}
