package lk.slt.fieldops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.AmendBillRequest;
import lk.slt.fieldops.dto.ReportDisputeRequest;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.PaymentApprovalRepository;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sheet {@code 04_PAYMENT_FLOW} row PAY-023 (FR-32 / REP-10) — a bill amendment must surface in
 * the generic audit-trail report, carrying its before/after amounts, its justification, its actor
 * and its timestamp.
 *
 * <p>The row insists this be confirmed by a <b>real round trip</b> rather than code inspection, so
 * the test disputes a bill, amends it, and then reads {@code GET /api/reports/audit-trail} through
 * the actual filter chain and {@code ReportService.getAuditTrailReport} — it does not read
 * {@code payment_approvals} directly (that is already covered, at the persistence layer, by
 * {@code BillDisputeAmendmentIntegrationTest.check3and4_...}). What is new here is whether the
 * REP-10 report actually projects an AMENDED row into its output.</p>
 *
 * <p><b>Tool substitution.</b> REST Assured is not a dependency of this module; MockMvc through
 * the real filter chain is the module's convention and exercises the identical path. Requires
 * {@code SPRING_PROFILES_ACTIVE=local}. {@code @Transactional} so the fixtures roll back.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportServiceAuditTrailTest {

    @Autowired private MockMvc                   mvc;
    @Autowired private JwtTokenProvider          jwt;
    @Autowired private PaymentRepository         paymentRepo;
    @Autowired private PaymentApprovalRepository approvalRepo;
    @Autowired private UserRepository            userRepo;
    @Autowired private ObjectMapper              json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_BRANCH_ID = 1L;
    private static final Long REAL_JOB_ID    = 7L;

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
        // Stage F #2/#3 — OpmcAccessGuard.resolveOpmcFilter now fails closed for a non-SUPER_ADMIN
        // caller with no opmcId on file; every other test fixture in this module already sets one.
        u.setOpmcId(REAL_BRANCH_ID);
        return userRepo.save(u);
    }

    private Payment newFinalBill(Long customerId) {
        long n = uniq();
        Payment p = new Payment();
        p.setPaymentNumber("PAY-AUDIT-" + n);
        p.setPaymentReference("PAY-AUDIT-" + n);
        p.setJobId(REAL_JOB_ID);
        p.setJobNumber("JOB-AUDIT-" + n);
        p.setOpmcId(REAL_BRANCH_ID);
        p.setCustomerId(customerId);
        p.setCustomerName("Audit Client " + customerId);
        p.setTeamLeadId(500L);
        p.setTeamLeadName("Test TL");
        p.setMaterialsFocTotal(new BigDecimal("100.00"));
        p.setMaterialsChargeableTotal(new BigDecimal("4000.00"));
        p.setLabourCharge(new BigDecimal("1000.00"));
        p.setTotalAmount(new BigDecimal("5000.00"));
        p.setApprovedAmount(new BigDecimal("5000.00"));
        p.setStatus(Payment.PaymentStatus.FINAL);
        return paymentRepo.save(p);
    }

    private void flushAndClear() {
        paymentRepo.flush();
        approvalRepo.flush();
        em.clear();
    }

    @Test
    void amendmentSurfacesInAuditReport() throws Exception {
        User   client   = newUser(User.Role.CLIENT, "Audit Trail Client");
        User   admin    = newUser(User.Role.ADMIN,  "Audit Admin Amelia");
        String adminHdr = bearer(admin.getId(), "ADMIN");

        Payment bill = newFinalBill(client.getId());
        Long id = bill.getId();
        flushAndClear();

        // ── Round trip step 1: the client disputes the bill ──────────────────────
        ReportDisputeRequest dispute = new ReportDisputeRequest();
        dispute.setCategory("WRONG_AMOUNT");
        dispute.setDescription("overcharged on materials");
        MvcResult disputeRes = mvc.perform(post("/api/billing/{id}/dispute", id)
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(dispute)))
            .andReturn();
        assertEquals(200, disputeRes.getResponse().getStatus(),
            "Dispute setup failed: " + disputeRes.getResponse().getContentAsString());
        flushAndClear();

        // ── Round trip step 2: the Admin amends it 5000 -> 3300 ──────────────────
        final String justification = "Removed the duplicated router line item the client flagged";
        AmendBillRequest amend = new AmendBillRequest();
        amend.setMaterialsChargeableTotal(new BigDecimal("2500.00"));
        amend.setLabourCharge(new BigDecimal("800.00"));
        amend.setJustification(justification);

        MvcResult amendRes = mvc.perform(patch("/api/payments/{id}/amend", id)
                .header("Authorization", adminHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(amend)))
            .andReturn();
        assertEquals(200, amendRes.getResponse().getStatus(),
            "Amend setup failed: " + amendRes.getResponse().getContentAsString());
        flushAndClear();

        // ── Round trip step 3: read the REP-10 audit trail report ───────────────
        MvcResult report = mvc.perform(get("/api/reports/audit-trail")
                .param("period", "THIS_MONTH")
                .param("entityType", "PAYMENT")
                .header("Authorization", adminHdr))
            .andReturn();

        String body = report.getResponse().getContentAsString();
        assertEquals(200, report.getResponse().getStatus(),
            "GET /api/reports/audit-trail must answer 200. Body: " + body);

        JsonNode data = json.readTree(body).path("data");
        assertTrue(data.isArray() && data.size() > 0,
            "The audit trail report must carry a non-empty data array. Body: " + body);

        // Find OUR amendment: the AMENDED entry for this payment id.
        JsonNode amended = null;
        for (JsonNode item : data) {
            if ("AMENDED".equals(item.path("action").asText())
                    && String.valueOf(id).equals(item.path("entityId").asText())) {
                amended = item;
                break;
            }
        }
        assertNotNull(amended,
            "The amendment must surface in the REP-10 audit trail as an action=AMENDED entry for "
                + "payment " + id + ". Entries returned: " + data);

        // ── The row's required fields on that entry ─────────────────────────────
        assertEquals("PAYMENT", amended.path("entityType").asText(),
            "The amendment entry must be typed PAYMENT. Entry: " + amended);

        assertEquals(0, new BigDecimal(amended.path("previousValue").asText())
                .compareTo(new BigDecimal("5000")),
            "previousValue must be the pre-amendment amount 5000. Entry: " + amended);
        assertEquals(0, new BigDecimal(amended.path("newValue").asText())
                .compareTo(new BigDecimal("3300")),
            "newValue must be the post-amendment amount 2500 + 800 = 3300. Entry: " + amended);

        assertEquals(justification, amended.path("details").asText(),
            "The amendment justification must surface in the report. Entry: " + amended);

        assertEquals("Audit Admin Amelia", amended.path("actorName").asText(),
            "The acting admin must be named on the audit entry. Entry: " + amended);
        assertEquals("ADMIN", amended.path("actorRole").asText(),
            "The actor's role must be recorded. Entry: " + amended);

        assertFalse(amended.path("timestamp").asText().isBlank(),
            "The audit entry must be timestamped. Entry: " + amended);
    }
}
