package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.SubmitPaymentRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.PaymentRepository;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sheet {@code 04_PAYMENT_FLOW} rows PAY-001 and PAY-015 (FR-10 / FR-11) — the Team Lead payment
 * submission endpoint and the Team Lead's own payment history, driven end-to-end.
 *
 * <p><b>Tool substitution.</b> Both rows name REST Assured, which is not a dependency of this
 * module ({@code pom.xml} carries only {@code spring-boot-starter-test} +
 * {@code spring-security-test}). The module's established convention is MockMvc through the REAL
 * JWT filter chain and method-security AOP against the project's MySQL instance — see
 * {@link BillDisputeAmendmentIntegrationTest} — which exercises the identical code path. Same
 * substitution already applied across sheets 02, 03 and 13.</p>
 *
 * <p>Each test is {@code @Transactional} so its rows roll back and never pollute the shared dev
 * database, and {@code SPRING_PROFILES_ACTIVE=local} is required for the context to boot at all
 * (the app deliberately has no insecure secret defaults).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentIntegrationTest {

    @Autowired private MockMvc           mvc;
    @Autowired private JwtTokenProvider  jwt;
    @Autowired private PaymentRepository paymentRepo;
    @Autowired private JobRepository     jobRepo;
    @Autowired private FaultRepository   faultRepo;
    @Autowired private UserRepository    userRepo;
    @Autowired private ObjectMapper      json;
    @Autowired private jakarta.persistence.EntityManager em;

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
        u.setRole(role);
        // §A-4 (Cross-OPMC bill exposure) scoping added OpmcAccessGuard.resolveOpmcFilter/
        // assertSameOpmc checks to PaymentController; those fail closed for a non-SUPER_ADMIN caller
        // with no opmcId on file. Every user in this fixture operates against REAL_BRANCH_ID, so give
        // them all that OPMC rather than leaving opmcId null.
        u.setOpmcId(REAL_BRANCH_ID);
        return userRepo.save(u);
    }

    /** A completed Job (with its Fault) for a payment to be submitted against. */
    private Job newJobWithFault(User customer, User teamLead) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-PAY-" + n);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("Payment-flow fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.COMPLETED);
        f = faultRepo.save(f);

        Job j = new Job();
        j.setJobNumber("JOB-PAY-" + n);
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

    private void flushAndClear() {
        paymentRepo.flush();
        em.clear();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-001 — POST /api/payments returns 201 with the payment awaiting review
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A Team Lead submits a full payment payload; the API answers 201, the payment lands in the
     * admin review queue, the customer signature is persisted, and Admin is notified.
     *
     * <p><b>Two deviations from the row as written, both schema facts rather than choices:</b></p>
     * <ol>
     *   <li>The row asserts {@code status == 'PENDING'}. There is no PENDING constant —
     *       {@code Payment.PaymentStatus.DRAFT} is the awaiting-admin-review state
     *       ({@code PaymentService.getPendingPayments()} queries exactly it, and
     *       {@code GET /api/payments/pending} serves it). Both the literal status and the
     *       queue membership that gives it meaning are asserted.</li>
     *   <li>The row asserts "payment_materials: 2 rows" and "payment_signatures: 1 row". Neither
     *       table exists. Materials are two opaque component totals on the payment row
     *       ({@code materialsFocTotal} / {@code materialsChargeableTotal}) and the signature is the
     *       single {@code customer_signature_url} column, so the row's intent — the FOC item and
     *       the chargeable item are both recorded, and exactly one signature is stored — is
     *       asserted against those columns. The schema-shape row itself is PAY-014
     *       ({@code PaymentMaterialTest}).</li>
     * </ol>
     */
    @Test
    void submitPayment_returns201Pending() throws Exception {
        User customer = newUser(User.Role.CLIENT, "Payment Client");
        User teamLead = newUser(User.Role.TEAM_LEAD, "TL Tharindu");
        Job  job      = newJobWithFault(customer, teamLead);

        // materials: [{qty 2, FOC}, {qty 1, chargeable 800}] -> FOC 1000 / chargeable 800;
        // labour 2.5h -> 3750. Grand total billed = 800 + 3750 = 4550.
        SubmitPaymentRequest req = new SubmitPaymentRequest();
        req.setJobId(job.getId());
        req.setMaterialsFocTotal(new BigDecimal("1000.00"));
        req.setMaterialsChargeableTotal(new BigDecimal("800.00"));
        req.setLabourCharge(new BigDecimal("3750.00"));
        req.setMaterialJustification("Standard repair");
        req.setWorkSummary("Standard repair");
        req.setCustomerSignatureUrl("data:image/png;base64,B64SIGNATUREDATA");

        MvcResult res = mvc.perform(post("/api/payments")
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(201, res.getResponse().getStatus(),
            "Submitting a payment must answer 201 Created. Body: " + body);

        JsonNode payload = json.readTree(body);
        assertEquals("DRAFT", payload.path("status").asText(),
            "A freshly submitted payment must await admin review. There is no PENDING constant in "
                + "Payment.PaymentStatus; DRAFT is that state. Body: " + body);

        Long paymentId = payload.path("id").asLong();
        assertTrue(paymentId > 0, "The response must carry the new payment id. Body: " + body);
        flushAndClear();

        Payment saved = paymentRepo.findById(paymentId).orElseThrow();

        // Both material components recorded (the row's "2 material rows").
        assertEquals(0, saved.getMaterialsFocTotal().compareTo(new BigDecimal("1000.00")),
            "The FOC material component must be recorded, was " + saved.getMaterialsFocTotal());
        assertEquals(0, saved.getMaterialsChargeableTotal().compareTo(new BigDecimal("800.00")),
            "The chargeable material component must be recorded, was "
                + saved.getMaterialsChargeableTotal());

        // Exactly one signature (the row's "payment_signatures: 1 row").
        assertEquals("data:image/png;base64,B64SIGNATUREDATA", saved.getCustomerSignatureUrl(),
            "The customer signature must be persisted on the payment");

        // The billed total excludes FOC.
        assertEquals(0, saved.getTotalAmount().compareTo(new BigDecimal("4550.00")),
            "Billed total must be chargeable 800 + labour 3750 = 4550 (FOC excluded), was "
                + saved.getTotalAmount());

        // Admin notification: the payment is in the queue Admin's review screen reads.
        MvcResult queue = mvc.perform(get("/api/payments/pending")
                .header("Authorization", bearer(newUser(User.Role.ADMIN, "Admin Amelia").getId(), "ADMIN")))
            .andReturn();
        assertEquals(200, queue.getResponse().getStatus(),
            "Body: " + queue.getResponse().getContentAsString());

        boolean inQueue = false;
        for (JsonNode p : json.readTree(queue.getResponse().getContentAsString())) {
            if (p.path("id").asLong() == paymentId) { inQueue = true; break; }
        }
        assertTrue(inQueue,
            "The submitted payment must appear in the admin review queue "
                + "(GET /api/payments/pending) — that queue IS the admin notification surface");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-015 — a Team Lead sees only their OWN payment history
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The row calls {@code GET /api/payments?submittedBy=<tlId>}. No such query parameter exists;
     * the real endpoint is {@code GET /api/payments/my}, which resolves the Team Lead from the JWT
     * rather than trusting a caller-supplied id — a strictly stronger guarantee, since a
     * {@code submittedBy} parameter could be pointed at another Team Lead. The row's assertions
     * (five results, all this TL's, the other TL's absent) are unchanged.
     */
    @Test
    void teamLeadSeesOwnPaymentsOnly() throws Exception {
        User customer = newUser(User.Role.CLIENT, "History Client");
        User mine     = newUser(User.Role.TEAM_LEAD, "TL Mine");
        User theirs   = newUser(User.Role.TEAM_LEAD, "TL Theirs");

        List<Long> myPaymentIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            myPaymentIds.add(submitAs(mine, customer, "1" + i + "00.00"));
        }
        Long theirPaymentId = submitAs(theirs, customer, "9900.00");
        flushAndClear();

        MvcResult res = mvc.perform(get("/api/payments/my")
                .header("Authorization", bearer(mine.getId(), "TEAM_LEAD")))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);

        JsonNode list = json.readTree(body);
        assertEquals(5, list.size(),
            "The Team Lead must see exactly their own 5 payments, got " + list.size()
                + ". Body: " + body);

        for (JsonNode p : list) {
            assertEquals(mine.getId().longValue(), p.path("teamLeadId").asLong(),
                "Every returned payment must be this Team Lead's. Offender: " + p);
            assertNotEquals(theirPaymentId.longValue(), p.path("id").asLong(),
                "The other Team Lead's payment must not be visible");
        }

        List<Long> returnedIds = new ArrayList<>();
        list.forEach(p -> returnedIds.add(p.path("id").asLong()));
        assertTrue(returnedIds.containsAll(myPaymentIds),
            "All 5 of this Team Lead's payments must be returned. Expected " + myPaymentIds
                + ", got " + returnedIds);
    }

    /** Submit one payment as {@code teamLead} against a fresh job, returning its id. */
    private Long submitAs(User teamLead, User customer, String chargeable) throws Exception {
        Job job = newJobWithFault(customer, teamLead);
        SubmitPaymentRequest req = new SubmitPaymentRequest();
        req.setJobId(job.getId());
        req.setMaterialsFocTotal(BigDecimal.ZERO);
        req.setMaterialsChargeableTotal(new BigDecimal(chargeable));
        req.setLabourCharge(BigDecimal.ZERO);
        req.setCustomerSignatureUrl("data:image/png;base64,SIG");
        // Chargeable-materials justification: mandatory once the billed total reaches LKR 5000
        // (PAY-004), which the LKR 9900 submission in this test crosses.
        req.setMaterialJustification("Chargeable materials replaced on site during the repair");

        MvcResult res = mvc.perform(post("/api/payments")
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus(),
            "Fixture submission failed: " + res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString()).path("id").asLong();
    }
}
