package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.AmendBillRequest;
import lk.slt.fieldops.dto.ReportDisputeRequest;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.PaymentApproval;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Stage-1 backend verification for the Bill Dispute & Amendment feature
 * (QA_Compliance_Consolidated_Report §2.1, FR-31 / FR-32, SRS §5.2.3 & §5.5.2.1) —
 * the parts that require REAL Spring Security enforcement.
 *
 * <p>{@code @SpringBootTest} boots the full context against the project's MySQL DB (the
 * established pattern; see {@code ApplicationTests}), and every request goes through the
 * ACTUAL JWT filter chain (tokens minted with the app's own {@link JwtTokenProvider}) and
 * method-security AOP. It does NOT {@code new} the controllers, so {@code @PreAuthorize} and
 * {@code @AuthenticationPrincipal Long userId} resolution are genuinely exercised — a bare
 * Mockito unit test calling {@code controller.amend(...)} directly would bypass both.</p>
 *
 * <p>Each test is {@code @Transactional} so rows created here roll back and never pollute the
 * shared dev DB.</p>
 *
 * <p><b>Coverage.</b> #1 (ownership 403), #2 (role 403) and #5 (invalid-status 400) exercise
 * guards that throw before any status write. #3 (full Report→Amend→Resend→Report cycle) and
 * #4 (audit-trail chaining across two amendments) persist the new {@code DISPUTED} /
 * {@code PENDING_CLIENT_REVIEW} states and {@code AMENDED} audit rows for real — these became
 * runnable end-to-end once the {@code payments.status} / {@code payment_approvals.action} ENUM
 * columns were widened by {@code migrations/manual/add_dispute_amendment_enum_values.sql}. A
 * faster repo-mocked twin of #3/#4 also lives in {@code PaymentServiceDisputeAmendTest}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BillDisputeAmendmentIntegrationTest {

    @Autowired private MockMvc                    mvc;
    @Autowired private JwtTokenProvider           jwt;
    @Autowired private PaymentRepository          paymentRepo;
    @Autowired private PaymentApprovalRepository  approvalRepo;
    @Autowired private UserRepository             userRepo;
    @Autowired private ObjectMapper               json;
    @Autowired private jakarta.persistence.EntityManager em;

    // Existing rows the payments FKs require (fk_pay_branch -> branches.id, fk_pay_job -> jobs.id).
    private static final Long REAL_BRANCH_ID = 1L;
    private static final Long REAL_JOB_ID    = 7L;   // job_id index is NON-unique, so reuse is fine

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        // The JWT filter turns this into UsernamePasswordAuthenticationToken(userId, null,
        // [ROLE_<role>]) — exactly the principal shape @AuthenticationPrincipal Long / @PreAuthorize expect.
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, 1L);
    }

    /** Persist a payment in the given (schema-valid) status owned by {@code customerId}. */
    private Payment newPayment(Payment.PaymentStatus status, Long customerId) {
        long n = uniq();
        Payment p = new Payment();
        p.setPaymentNumber("PAY-TEST-" + n);
        p.setPaymentReference("PAY-TEST-" + n);
        p.setJobId(REAL_JOB_ID);
        p.setJobNumber("JOB-TEST-" + n);
        p.setBranchId(REAL_BRANCH_ID);
        p.setCustomerId(customerId);
        p.setCustomerName("Test Client " + customerId);
        p.setTeamLeadId(500L);
        p.setTeamLeadName("Test TL");
        p.setMaterialsFocTotal(new BigDecimal("100.00"));
        p.setMaterialsChargeableTotal(new BigDecimal("4000.00"));
        p.setLabourCharge(new BigDecimal("1000.00"));
        p.setTotalAmount(new BigDecimal("5000.00"));
        p.setApprovedAmount(new BigDecimal("5000.00"));
        p.setStatus(status);
        return paymentRepo.save(p);
    }

    private ReportDisputeRequest dispute(String category, String description) {
        ReportDisputeRequest r = new ReportDisputeRequest();
        r.setCategory(category);
        r.setDescription(description);
        return r;
    }

    private AmendBillRequest amend(String chargeable, String labour, String justification) {
        AmendBillRequest r = new AmendBillRequest();
        r.setMaterialsChargeableTotal(new BigDecimal(chargeable));
        r.setLabourCharge(new BigDecimal(labour));
        r.setJustification(justification);
        return r;
    }

    /** Persist a real user so PaymentController.resolveFullName(adminId) returns a known name. */
    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("u" + n);
        u.setPasswordHash("x");
        u.setFirstName("First");
        u.setLastName("Last");
        u.setFullName(fullName);
        u.setRole(role);
        return userRepo.save(u);
    }

    /**
     * Flush pending writes to the DB and detach everything, so each post-request assertion reads
     * freshly-loaded row state rather than the in-memory instance the request mutated. Stays within
     * the same (rolled-back) test transaction.
     */
    private void flushAndClear() {
        paymentRepo.flush();
        approvalRepo.flush();
        em.clear();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #1 — POST /api/billing/{id}/dispute rejects a non-owner client
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check1_dispute_byNonOwnerClient_isForbidden() throws Exception {
        Long ownerId    = 1001L;
        Long strangerId = 2002L; // a different, authenticated CLIENT
        Payment bill = newPayment(Payment.PaymentStatus.FINAL, ownerId);

        MvcResult res = mvc.perform(post("/api/billing/{id}/dispute", bill.getId())
                .header("Authorization", bearer(strangerId, "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(dispute("WRONG_AMOUNT", "not my bill"))))
            .andReturn();

        int st = res.getResponse().getStatus();
        String body = res.getResponse().getContentAsString();

        assertEquals(403, st,
            "Non-owner dispute must be 403 Forbidden, not 500/200. Body: " + body);
        // Proves it's the ownership guard specifically (the request reached the controller and
        // hit AccessDeniedException) — not a blanket filter rejection or a server error.
        assertTrue(body.contains("You do not have access to this bill"),
            "403 should carry the ownership AccessDeniedException message. Body: " + body);

        // The stranger must not have mutated the bill.
        Payment after = paymentRepo.findById(bill.getId()).orElseThrow();
        assertEquals(Payment.PaymentStatus.FINAL, after.getStatus());
        assertNull(after.getDisputeDescription(), "No dispute fields should have been written");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #2 — PATCH /api/payments/{id}/amend rejects non-admin roles with 403
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check2_amend_byNonAdminRole_isForbidden() throws Exception {
        // FINAL is a schema-valid setup status; @PreAuthorize fires before amendBill's status
        // guard, so the ONLY reason for rejection here is the role check.
        Payment bill = newPayment(Payment.PaymentStatus.FINAL, 1004L);

        for (String role : List.of("TECHNICIAN", "TEAM_LEAD", "CLIENT")) {
            MvcResult res = mvc.perform(patch("/api/payments/{id}/amend", bill.getId())
                    .header("Authorization", bearer(7777L, role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(amend("3000.00", "1000.00", "should be blocked"))))
                .andReturn();

            assertEquals(403, res.getResponse().getStatus(),
                "Role " + role + " must be blocked by @PreAuthorize with 403, not 500/200. Body: "
                    + res.getResponse().getContentAsString());
        }

        // No amendment persisted, no audit row written by any non-admin.
        Payment after = paymentRepo.findById(bill.getId()).orElseThrow();
        assertEquals(Payment.PaymentStatus.FINAL, after.getStatus());
        assertTrue(approvalRepo.findByPaymentIdOrderByCreatedAtDesc(bill.getId()).isEmpty(),
            "No AMENDED audit row should exist after blocked attempts");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #5 — dispute blocked from statuses other than FINAL / PENDING_CLIENT_REVIEW
    // (closest real boundary to "already-accepted bill"; ACCEPTED does not exist in Stage-1)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check5_dispute_fromInvalidStatuses_isBlocked() throws Exception {
        Long clientId = 1007L;
        // DRAFT, NOT_APPROVED, CLARIFICATION_REQUESTED are all schema-valid and all outside the
        // allowed-from set, so reportDispute's guard must reject each with 400. (The 4th invalid
        // state, already-DISPUTED, cannot be set up here because the DB status ENUM lacks it —
        // that transition is covered logic-only in PaymentServiceDisputeAmendTest.)
        for (Payment.PaymentStatus bad : List.of(
                Payment.PaymentStatus.DRAFT,
                Payment.PaymentStatus.NOT_APPROVED,
                Payment.PaymentStatus.CLARIFICATION_REQUESTED)) {

            Payment bill = newPayment(bad, clientId);

            MvcResult res = mvc.perform(post("/api/billing/{id}/dispute", bill.getId())
                    .header("Authorization", bearer(clientId, "CLIENT"))   // the OWNER — isolates the status guard
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(dispute("WRONG_AMOUNT", "trying to dispute from " + bad))))
                .andReturn();

            String body = res.getResponse().getContentAsString();
            assertEquals(400, res.getResponse().getStatus(),
                "Disputing from " + bad + " must be rejected as 400 Bad Request. Body: " + body);
            assertTrue(body.contains("Only an approved or amended bill"),
                "Rejection should carry the guard's message for status " + bad + ". Body: " + body);

            // Guard threw before any mutation — status unchanged.
            assertEquals(bad, paymentRepo.findById(bill.getId()).orElseThrow().getStatus());
        }
    }

    /**
     * #5 (already-DISPUTED boundary), now persistable after the ENUM migration: a bill already in
     * DISPUTED cannot be disputed again from that state — same guard, message and 400 as the other
     * invalid statuses. (Disputing an *amended* PENDING_CLIENT_REVIEW bill IS allowed — that's the
     * cycle, covered in the end-to-end test below.)
     */
    @Test
    void check5b_dispute_fromAlreadyDisputed_isBlocked() throws Exception {
        Long clientId = 1008L;
        Payment bill = newPayment(Payment.PaymentStatus.DISPUTED, clientId);

        MvcResult res = mvc.perform(post("/api/billing/{id}/dispute", bill.getId())
                .header("Authorization", bearer(clientId, "CLIENT"))   // owner — isolates the status guard
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(dispute("WRONG_AMOUNT", "disputing an already-disputed bill"))))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(400, res.getResponse().getStatus(),
            "Disputing from DISPUTED must be rejected 400. Body: " + body);
        assertTrue(body.contains("Only an approved or amended bill"),
            "Rejection should carry the guard's message. Body: " + body);
        assertEquals(Payment.PaymentStatus.DISPUTED,
            paymentRepo.findById(bill.getId()).orElseThrow().getStatus());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #3 & #4 — full Report -> Amend -> Resend -> Report-again cycle + audit
    // trail, end-to-end against the REAL database (persists the new ENUM states)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check3and4_fullDisputeAmendCycleRepeats_endToEnd_withCorrectAuditChaining() throws Exception {
        Long   clientId  = 1006L;
        User   admin     = newUser(User.Role.ADMIN, "Admin Amelia");
        String adminHdr  = bearer(admin.getId(), "ADMIN");
        String clientHdr = bearer(clientId, "CLIENT");

        // Start: an approved (FINAL) bill for LKR 5000, owned by the client.
        Payment bill = newPayment(Payment.PaymentStatus.FINAL, clientId);
        Long id = bill.getId();
        flushAndClear();

        // (a) Client disputes the FINAL bill → DISPUTED ------------------------
        mvc.perform(post("/api/billing/{id}/dispute", id)
                .header("Authorization", clientHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(dispute("WRONG_AMOUNT", "overcharged on materials"))))
            .andExpect(mvcStatus(200));
        flushAndClear();

        Payment afterDispute1 = paymentRepo.findById(id).orElseThrow();
        assertEquals(Payment.PaymentStatus.DISPUTED, afterDispute1.getStatus());
        assertEquals("WRONG_AMOUNT", afterDispute1.getDisputeCategory());
        assertEquals("overcharged on materials", afterDispute1.getDisputeDescription());
        assertNotNull(afterDispute1.getDisputedAt());

        // (b) Admin amends: chargeable 3000 + labour 1000 = 4000 --------------
        mvc.perform(patch("/api/payments/{id}/amend", id)
                .header("Authorization", adminHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(amend("3000.00", "1000.00", "Removed duplicated router line item"))))
            .andExpect(mvcStatus(200));
        flushAndClear();

        Payment afterAmend1 = paymentRepo.findById(id).orElseThrow();
        assertEquals(Payment.PaymentStatus.PENDING_CLIENT_REVIEW, afterAmend1.getStatus());
        assertEquals(0, afterAmend1.getTotalAmount().compareTo(new BigDecimal("4000")),
            "New total must be chargeable+labour = 4000, was " + afterAmend1.getTotalAmount());
        assertEquals(0, afterAmend1.getApprovedAmount().compareTo(new BigDecimal("4000")));
        assertEquals(admin.getId(), afterAmend1.getAmendedBy());
        assertEquals("Admin Amelia", afterAmend1.getAmendedByName());
        assertEquals("Removed duplicated router line item", afterAmend1.getAmendmentJustification());

        // (c) SAME client disputes the AMENDED bill again → DISPUTED ----------
        mvc.perform(post("/api/billing/{id}/dispute", id)
                .header("Authorization", clientHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(dispute("STILL_WRONG", "labour charge still too high"))))
            .andExpect(mvcStatus(200));
        flushAndClear();

        Payment afterDispute2 = paymentRepo.findById(id).orElseThrow();
        assertEquals(Payment.PaymentStatus.DISPUTED, afterDispute2.getStatus(),
            "Cycle must repeat: an amended (PENDING_CLIENT_REVIEW) bill can be disputed again");
        assertEquals("STILL_WRONG", afterDispute2.getDisputeCategory());
        assertEquals("labour charge still too high", afterDispute2.getDisputeDescription());

        // (d) Admin amends again: chargeable 2500 + labour 800 = 3300 ---------
        mvc.perform(patch("/api/payments/{id}/amend", id)
                .header("Authorization", adminHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(amend("2500.00", "800.00", "Reduced labour to quoted rate"))))
            .andExpect(mvcStatus(200));
        flushAndClear();

        Payment afterAmend2 = paymentRepo.findById(id).orElseThrow();
        assertEquals(Payment.PaymentStatus.PENDING_CLIENT_REVIEW, afterAmend2.getStatus());
        assertEquals(0, afterAmend2.getTotalAmount().compareTo(new BigDecimal("3300")),
            "Second amendment total must be 2500+800 = 3300, was " + afterAmend2.getTotalAmount());

        // ── CHECK #4: two persisted AMENDED rows with correct before/after chaining ──
        List<PaymentApproval> amendments = approvalRepo.findByPaymentIdOrderByCreatedAtDesc(id).stream()
            .filter(a -> a.getAction() == PaymentApproval.Action.AMENDED)
            .toList();
        assertEquals(2, amendments.size(), "Exactly two persisted AMENDED audit rows expected");

        // Identify each row by its (unique) result amount rather than by createdAt ordering —
        // the two rows can share a createdAt at DATETIME second-precision, so ordering is not a
        // reliable discriminator.
        PaymentApproval first  = amendments.stream()   // 1st amendment resulted in 4000
            .filter(a -> a.getAdjustedAmount().compareTo(new BigDecimal("4000")) == 0)
            .findFirst().orElseThrow(() ->
                new AssertionError("No AMENDED row with adjustedAmount 4000 found"));
        PaymentApproval second = amendments.stream()   // 2nd amendment resulted in 3300
            .filter(a -> a.getAdjustedAmount().compareTo(new BigDecimal("3300")) == 0)
            .findFirst().orElseThrow(() ->
                new AssertionError("No AMENDED row with adjustedAmount 3300 found"));

        // First amendment: 5000 -> 4000
        assertEquals(0, first.getOriginalAmount().compareTo(new BigDecimal("5000")),
            "1st amendment originalAmount must be the pre-amend 5000, was " + first.getOriginalAmount());
        assertEquals("Removed duplicated router line item", first.getReason());
        assertEquals(admin.getId(), first.getAdminId());
        assertEquals("Admin Amelia", first.getAdminName());

        // Second amendment: 4000 -> 3300. originalAmount MUST chain from the 1st amend's RESULT
        // (4000), NOT the very first pre-dispute 5000 — the correctness-across-repeated-cycles check.
        assertEquals(0, second.getOriginalAmount().compareTo(new BigDecimal("4000")),
            "2nd amendment originalAmount MUST be 4000 (amount before the 2nd amend), NOT the "
                + "original 5000 — was " + second.getOriginalAmount());
        assertEquals("Reduced labour to quoted rate", second.getReason());
        assertEquals(admin.getId(), second.getAdminId());
        assertEquals("Admin Amelia", second.getAdminName());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #6 — POST /api/billing/{id}/accept rejects a non-owner client (403)
    // (mirrors check #1; the accept endpoint reuses the same ownership guard as dispute)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check6_accept_byNonOwnerClient_isForbidden() throws Exception {
        Long ownerId    = 1101L;
        Long strangerId = 2102L; // a different, authenticated CLIENT
        Payment bill = newPayment(Payment.PaymentStatus.FINAL, ownerId);

        MvcResult res = mvc.perform(post("/api/billing/{id}/accept", bill.getId())
                .header("Authorization", bearer(strangerId, "CLIENT")))
            .andReturn();

        int st = res.getResponse().getStatus();
        String body = res.getResponse().getContentAsString();

        assertEquals(403, st,
            "Non-owner accept must be 403 Forbidden, not 500/200. Body: " + body);
        assertTrue(body.contains("You do not have access to this bill"),
            "403 should carry the ownership AccessDeniedException message. Body: " + body);

        // The stranger must not have mutated the bill nor written an audit row.
        Payment after = paymentRepo.findById(bill.getId()).orElseThrow();
        assertEquals(Payment.PaymentStatus.FINAL, after.getStatus());
        assertTrue(approvalRepo.findByPaymentIdOrderByCreatedAtDesc(bill.getId()).isEmpty(),
            "No CLIENT_ACCEPTED audit row should exist after a blocked attempt");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #7 — accept blocked from statuses other than FINAL / PENDING_CLIENT_REVIEW,
    // including an already-DISPUTED bill (accepting a disputed bill must NOT be possible)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check7_accept_fromInvalidStatuses_isBlocked() throws Exception {
        Long clientId = 1107L;
        for (Payment.PaymentStatus bad : List.of(
                Payment.PaymentStatus.DRAFT,
                Payment.PaymentStatus.NOT_APPROVED,
                Payment.PaymentStatus.CLARIFICATION_REQUESTED,
                Payment.PaymentStatus.DISPUTED)) {

            Payment bill = newPayment(bad, clientId);

            MvcResult res = mvc.perform(post("/api/billing/{id}/accept", bill.getId())
                    .header("Authorization", bearer(clientId, "CLIENT")))   // the OWNER — isolates the status guard
                .andReturn();

            String body = res.getResponse().getContentAsString();
            assertEquals(400, res.getResponse().getStatus(),
                "Accepting from " + bad + " must be rejected as 400 Bad Request. Body: " + body);
            assertTrue(body.contains("Only an approved or amended bill can be accepted"),
                "Rejection should carry the accept guard's message for status " + bad + ". Body: " + body);

            // Guard threw before any mutation — status unchanged, no audit row.
            assertEquals(bad, paymentRepo.findById(bill.getId()).orElseThrow().getStatus());
            assertTrue(approvalRepo.findByPaymentIdOrderByCreatedAtDesc(bill.getId()).isEmpty(),
                "No audit row should be written when the accept guard rejects status " + bad);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #8 — end-to-end accept of a FINAL bill persists the terminal CLIENT_ACCEPTED
    // status and writes a correct client-actor audit row, against the REAL database
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check8_acceptFinalBill_persistsTerminalStatus_andWritesAudit() throws Exception {
        Long clientId = 1108L;
        Payment bill = newPayment(Payment.PaymentStatus.FINAL, clientId);  // approvedAmount 5000
        Long id = bill.getId();
        flushAndClear();

        mvc.perform(post("/api/billing/{id}/accept", id)
                .header("Authorization", bearer(clientId, "CLIENT")))
            .andExpect(mvcStatus(200));
        flushAndClear();

        Payment after = paymentRepo.findById(id).orElseThrow();
        assertEquals(Payment.PaymentStatus.CLIENT_ACCEPTED, after.getStatus(),
            "Accepted bill must persist the terminal CLIENT_ACCEPTED status");

        // Exactly one CLIENT_ACCEPTED audit row, recorded against the CLIENT as the actor.
        List<PaymentApproval> accepts = approvalRepo.findByPaymentIdOrderByCreatedAtDesc(id).stream()
            .filter(a -> a.getAction() == PaymentApproval.Action.CLIENT_ACCEPTED)
            .toList();
        assertEquals(1, accepts.size(), "Exactly one CLIENT_ACCEPTED audit row expected");
        PaymentApproval audit = accepts.get(0);
        assertEquals(clientId, audit.getAdminId(),
            "Audit actor id must be the accepting CLIENT, not an admin");
        assertEquals("Test Client " + clientId, audit.getAdminName());
        assertEquals(0, audit.getOriginalAmount().compareTo(new BigDecimal("5000")),
            "originalAmount should record the accepted amount (5000), was " + audit.getOriginalAmount());
        assertNull(audit.getAdjustedAmount(),
            "Acceptance changes no amount, so adjustedAmount must stay null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #9 — an amended bill (PENDING_CLIENT_REVIEW) can also be accepted end-to-end
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check9_acceptAmendedBill_fromPendingClientReview_isAllowed() throws Exception {
        Long clientId = 1109L;
        Payment bill = newPayment(Payment.PaymentStatus.PENDING_CLIENT_REVIEW, clientId);
        Long id = bill.getId();
        flushAndClear();

        mvc.perform(post("/api/billing/{id}/accept", id)
                .header("Authorization", bearer(clientId, "CLIENT")))
            .andExpect(mvcStatus(200));
        flushAndClear();

        assertEquals(Payment.PaymentStatus.CLIENT_ACCEPTED,
            paymentRepo.findById(id).orElseThrow().getStatus(),
            "An amended bill awaiting review must be acceptable and reach CLIENT_ACCEPTED");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #10 — GET /api/payments/my-bills/{id} returns the CLIENT-scoped ClientBillDTO
    // shape (not the raw Payment entity) with the Part-A mapped status, for the owner
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check10_getMyBill_byOwner_returnsClientBillDtoShapeWithMappedStatus() throws Exception {
        Long clientId = 1201L;
        // Exercise all three newly-distinguished statuses map to distinct client-facing strings,
        // plus the unchanged FINAL -> APPROVED, through the actual endpoint + DTO.
        assertMappedStatus(clientId, Payment.PaymentStatus.FINAL,                 "APPROVED");
        assertMappedStatus(clientId, Payment.PaymentStatus.DISPUTED,              "DISPUTED");
        assertMappedStatus(clientId, Payment.PaymentStatus.PENDING_CLIENT_REVIEW, "PENDING_CLIENT_REVIEW");
        assertMappedStatus(clientId, Payment.PaymentStatus.CLIENT_ACCEPTED,       "ACCEPTED");
    }

    private void assertMappedStatus(Long clientId, Payment.PaymentStatus status, String expected)
            throws Exception {
        Payment bill = newPayment(status, clientId);
        mvc.perform(get("/api/payments/my-bills/{id}", bill.getId())
                .header("Authorization", bearer(clientId, "CLIENT")))
            .andExpect(mvcStatus(200))
            // DTO-shaped fields (materialsFOC, grandTotal) that the raw Payment entity does NOT expose.
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.status").value(expected))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.materialsFOC").exists())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.grandTotal").exists());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #11 — GET /api/payments/my-bills/{id} rejects a non-owner client (403)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check11_getMyBill_byNonOwnerClient_isForbidden() throws Exception {
        Payment bill = newPayment(Payment.PaymentStatus.FINAL, 1301L);

        MvcResult res = mvc.perform(get("/api/payments/my-bills/{id}", bill.getId())
                .header("Authorization", bearer(2301L, "CLIENT")))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "Non-owner single-bill fetch must be 403. Body: " + res.getResponse().getContentAsString());
        assertTrue(res.getResponse().getContentAsString().contains("You do not have access to this bill"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #12 — GET /api/payments/my-bills/{id} is CLIENT-only (non-client roles 403)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void check12_getMyBill_byNonClientRole_isForbidden() throws Exception {
        Payment bill = newPayment(Payment.PaymentStatus.FINAL, 1401L);

        for (String role : List.of("TEAM_LEAD", "ADMIN", "TECHNICIAN")) {
            assertEquals(403,
                mvc.perform(get("/api/payments/my-bills/{id}", bill.getId())
                        .header("Authorization", bearer(9001L, role)))
                    .andReturn().getResponse().getStatus(),
                "Role " + role + " must be blocked from the CLIENT-only my-bills/{id} endpoint");
        }
    }

    /** Small readability helper mirroring MockMvcResultMatchers.status().is(int). */
    private static org.springframework.test.web.servlet.ResultMatcher mvcStatus(int code) {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(code);
    }
}
