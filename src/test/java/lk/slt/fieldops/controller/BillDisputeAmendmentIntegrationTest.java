package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.AmendBillRequest;
import lk.slt.fieldops.dto.ReportDisputeRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.PaymentApproval;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
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
    @Autowired private FaultRepository            faultRepo;
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
        p.setOpmcId(REAL_BRANCH_ID);
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
        // §A-4 (Cross-OPMC bill exposure) scoping added an OpmcAccessGuard.assertSameOpmc check to
        // PATCH /{id}/amend; every payment in this fixture is REAL_BRANCH_ID, so give every user here
        // that same OPMC rather than leaving opmcId null (which now fails closed with a 403).
        u.setOpmcId(REAL_BRANCH_ID);
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

    // ══════════════════════════════════════════════════════════════════════════
    // Sheet 02_FAULT_TRACKING rows FLT-019 / FLT-021 / FLT-022 (FR-31)
    //
    // These three rows map to "BillingIntegrationTest::<method>". No such class exists, and every
    // behaviour they describe is the accept/dispute cycle this class already boots, seeds and
    // guards — so they are implemented here as additional methods rather than as a parallel class
    // duplicating the fixtures. Where an existing check already covers a row's core assertion it is
    // named below rather than repeated.
    //
    // Shared finding for FLT-019 / FLT-021: "active list" and "Service History" for a client are
    // GET /api/issues and GET /api/issues/history (IssueController), and membership of each is
    // derived ONLY from the linked FAULT's status — active = anything not COMPLETED/CANCELLED,
    // history = COMPLETED or CANCELLED. Neither list endpoint reads the payment status, and the
    // mobile client splits its lists the same way (IssueHistoryScreen filters on issue.status
    // completed/cancelled). So the ONLY way a bill action can move a job between the two lists is by
    // moving the linked fault — which PaymentService.acceptBill / reportDispute now do:
    //   accept  → fault COMPLETED (terminal → Service History)
    //   dispute → fault HOLD      (open      → active list; FR-31 "Report Issue keeps it open")
    // The two tests below therefore assert CAUSATION: each starts the job on the opposite list from
    // where it must end up, so the bill action is the only thing that can have moved it.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Link an existing bill to an existing fault the way {@code PaymentService.submitPayment} does
     * ({@code p.setFaultId(job.getFaultId())}), so the accept/dispute handlers resolve THIS fixture's
     * fault rather than whatever fault the shared {@link #REAL_JOB_ID} happens to point at.
     */
    private Payment linkBillToFault(Payment bill, Fault fault) {
        bill.setFaultId(fault.getId());
        bill.setFaultNumber(fault.getFaultNumber());
        return paymentRepo.save(bill);
    }

    /** faults.customer_id is an FK to users, so a fault fixture needs the real client row. */
    private Fault newFault(User client, Fault.FaultStatus status) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-BILL-" + n);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(client.getId());
        f.setCustomerName(client.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("Bill-cycle fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(status);
        return faultRepo.save(f);
    }

    /** True when the client's list at {@code path} contains the given fault id. */
    private boolean listContainsFault(String path, Long clientId, Long faultId) throws Exception {
        MvcResult res = mvc.perform(get(path)
                .header("Authorization", bearer(clientId, "CLIENT")))
            .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
            "GET " + path + " failed: " + res.getResponse().getContentAsString());
        for (JsonNode issue : json.readTree(res.getResponse().getContentAsString())) {
            if (String.valueOf(faultId).equals(issue.path("id").asText())) return true;
        }
        return false;
    }

    /**
     * FLT-019 — a client accepting their bill closes the cycle and the job MOVES into Service
     * History as a direct result of that acceptance.
     *
     * <p>The status/audit half of this row is also covered by
     * {@link #check8_acceptFinalBill_persistsTerminalStatus_andWritesAudit}; what is new here is the
     * list-membership half (the row's steps 4 and 5) — and, crucially, its <i>causation</i>.</p>
     *
     * <p><b>Fixture rationale.</b> The job starts NOT in history: the fault sits at HOLD and the
     * bill at PENDING_CLIENT_REVIEW, which is exactly the state left behind by a Report → Amend →
     * Resend round (the dispute pulls the fault out of COMPLETED into HOLD — see
     * {@link #dispute_keepsJobActive} — and the amendment resends the bill for review). Accepting is
     * therefore the only thing in this test that can move the job, so asserting it landed in history
     * proves the accept caused it rather than observing a position it already held.</p>
     */
    @Test
    void acceptBill_movesToHistory() throws Exception {
        User client = newUser(User.Role.CLIENT, "Accepting Client");
        Long clientId = client.getId();

        Fault fault = newFault(client, Fault.FaultStatus.HOLD);
        Payment bill = linkBillToFault(
            newPayment(Payment.PaymentStatus.PENDING_CLIENT_REVIEW, clientId), fault);
        flushAndClear();

        // ── Baseline: the job is ACTIVE and NOT in history before the client accepts ─────
        assertTrue(listContainsFault("/api/issues", clientId, fault.getId()),
            "Precondition: the job must start in the client's active issues, so that any later "
                + "presence in Service History can only have been caused by the acceptance");
        assertFalse(listContainsFault("/api/issues/history", clientId, fault.getId()),
            "Precondition: the job must start OUT of Service History");

        // ── Steps 1-2: the client accepts ────────────────────────────────────────────────
        MvcResult res = mvc.perform(post("/api/billing/{id}/accept", bill.getId())
                .header("Authorization", bearer(clientId, "CLIENT")))
            .andReturn();
        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        flushAndClear();

        // ── Step 3: terminal bill status persisted ───────────────────────────────────────
        assertEquals(Payment.PaymentStatus.CLIENT_ACCEPTED,
            paymentRepo.findById(bill.getId()).orElseThrow().getStatus(),
            "Accepting must move the bill to the terminal CLIENT_ACCEPTED status");

        // ── The mechanism: acceptance drove the LINKED FAULT to its terminal state ──────
        Fault afterFault = faultRepo.findById(fault.getId()).orElseThrow();
        assertEquals(Fault.FaultStatus.COMPLETED, afterFault.getStatus(),
            "Accepting the bill must transition the linked fault HOLD -> COMPLETED — that fault "
                + "status is the only thing IssueController's two list endpoints read");
        assertNotNull(afterFault.getCompletedAt(),
            "Closing the fault on acceptance must stamp completedAt");
        assertNull(afterFault.getHoldReason(),
            "The dispute hold reason must be cleared once the client accepts");

        // ── Steps 4-5: the job MOVED out of the active list and into Service History ────
        assertAll("acceptance moved the job into Service History",
            () -> assertFalse(listContainsFault("/api/issues", clientId, fault.getId()),
                "After acceptance the job must have LEFT the client's active issues"),
            () -> assertTrue(listContainsFault("/api/issues/history", clientId, fault.getId()),
                "After acceptance the job must appear in the client's Service History")
        );
    }

    /**
     * FLT-021 — a disputed bill must keep its job visible in the client's active issues and out of
     * Service History until the dispute is resolved.
     *
     * <p>The status half is also covered by
     * {@link #check3and4_fullDisputeAmendCycleRepeats_endToEnd_withCorrectAuditChaining}; the
     * list-placement half (the row's steps 3 and 4) is what is new here, and is the part
     * {@code BillingController}'s javadoc promises.</p>
     *
     * <p><b>Causation.</b> The job starts in Service History and out of the active list (the fault
     * is COMPLETED — the state JobService leaves it in when the technician finishes, which is when a
     * FINAL bill reaches the client). The dispute is the only action in this test, so the job
     * appearing in the active list afterwards can only be its doing.</p>
     */
    @Test
    void dispute_keepsJobActive() throws Exception {
        User client = newUser(User.Role.CLIENT, "Disputing Client");
        Long clientId = client.getId();

        Fault fault = newFault(client, Fault.FaultStatus.COMPLETED);
        Payment bill = linkBillToFault(newPayment(Payment.PaymentStatus.FINAL, clientId), fault);
        flushAndClear();

        // ── Baseline: a completed job starts in history, NOT in the active list ─────────
        assertFalse(listContainsFault("/api/issues", clientId, fault.getId()),
            "Precondition: a COMPLETED job starts OUT of the client's active issues, so any later "
                + "presence there can only have been caused by the dispute");
        assertTrue(listContainsFault("/api/issues/history", clientId, fault.getId()),
            "Precondition: a COMPLETED job starts IN Service History");

        // ── Steps 1-2: the client disputes ──────────────────────────────────────────────
        MvcResult res = mvc.perform(post("/api/billing/{id}/dispute", bill.getId())
                .header("Authorization", bearer(clientId, "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(dispute("WRONG_AMOUNT", "Charged for materials not used"))))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        flushAndClear();

        Payment after = paymentRepo.findById(bill.getId()).orElseThrow();
        assertEquals(Payment.PaymentStatus.DISPUTED, after.getStatus(),
            "A reported issue must move the bill to DISPUTED");
        assertEquals("WRONG_AMOUNT", after.getDisputeCategory());

        // ── The mechanism: the dispute pulled the LINKED FAULT back out of its terminal state ──
        Fault afterFault = faultRepo.findById(fault.getId()).orElseThrow();
        assertEquals(Fault.FaultStatus.HOLD, afterFault.getStatus(),
            "Disputing the bill must transition the linked fault COMPLETED -> HOLD (the codebase's "
                + "open-but-not-being-worked bucket, per FR-31 'Report Issue keeps it open') — that "
                + "fault status is the only thing IssueController's two list endpoints read");
        assertNotNull(afterFault.getHoldReason(),
            "The dispute must be recorded as the fault's hold reason");
        assertTrue(afterFault.getHoldReason().contains("disputed"),
            "Hold reason should name the dispute, was: " + afterFault.getHoldReason());

        // ── Steps 3-4: the job MOVED back into the active list and out of history ───────
        boolean stillActive = listContainsFault("/api/issues",         clientId, fault.getId());
        boolean inHistory   = listContainsFault("/api/issues/history", clientId, fault.getId());

        assertAll("the dispute kept the job out of Service History",
            () -> assertTrue(stillActive,
                "A job with a DISPUTED bill must appear in the client's active issues — it was NOT "
                    + "there before the dispute, so the dispute is what put it there"),
            () -> assertFalse(inHistory,
                "A job with a DISPUTED bill must NOT be left archived in Service History")
        );
    }

    /**
     * FLT-022 — a bill the client has already accepted cannot then be disputed.
     *
     * <p>Complements {@link #check5_dispute_fromInvalidStatuses_isBlocked} and
     * {@link #check5b_dispute_fromAlreadyDisputed_isBlocked}, neither of which covers the
     * CLIENT_ACCEPTED terminal state (that status did not exist when they were written).</p>
     *
     * <p>The sheet marks this row's Test Type as "Unit" while its Tool and mapping are REST Assured
     * / an integration class; the guard under test is server-side status enforcement, so it is
     * written as an integration check like its siblings.</p>
     */
    @Test
    void dispute_blockedOnAcceptedBill() throws Exception {
        User client = newUser(User.Role.CLIENT, "Accepted-Then-Disputing Client");
        Long clientId = client.getId();

        Payment bill = newPayment(Payment.PaymentStatus.CLIENT_ACCEPTED, clientId);
        flushAndClear();

        // ── Steps 1-2: disputing an already-accepted bill must be rejected ─────────────
        MvcResult res = mvc.perform(post("/api/billing/{id}/dispute", bill.getId())
                .header("Authorization", bearer(clientId, "CLIENT"))   // the OWNER — isolates the status guard
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(dispute("WRONG_AMOUNT", "changed my mind after accepting"))))
            .andReturn();

        int status = res.getResponse().getStatus();
        String body = res.getResponse().getContentAsString();

        assertTrue(status == 400 || status == 409,
            "Disputing a CLIENT_ACCEPTED bill must be rejected with 400 or 409, got " + status
                + ". Body: " + body);
        assertTrue(body.contains("Only an approved or amended bill"),
            "The rejection should carry the status guard's message. Body: " + body);

        // Status unchanged — the guard threw before any mutation.
        Payment after = paymentRepo.findById(bill.getId()).orElseThrow();
        assertEquals(Payment.PaymentStatus.CLIENT_ACCEPTED, after.getStatus(),
            "A blocked dispute must leave the accepted bill untouched");
        assertNull(after.getDisputeDescription(), "No dispute fields may be written");
        assertNull(after.getDisputedAt());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Sheet 12_BILL_DISPUTE rows BDA-003 / BDA-004 (FR-31 / FR-32)
    //
    // BDA-003 maps to "PaymentServiceDisputeAmendTest::amendRoleGateEnforced". That class is a
    // Mockito unit test that news up PaymentService directly — it never boots Spring Security, so
    // @PreAuthorize is structurally unreachable from it and the row's assertion could not be made
    // there. The role gate is an integration-level concern, so the row lives here instead, keeping
    // the mapped method name. BDA-004 maps to a cross-platform "billDisputeFullCycle.e2e.spec"
    // that does not exist; its two client-facing halves already do (Cypress
    // cypress/e2e/payments/billDisputeAdmin.cy.js for the admin half, SLTMobileApp
    // __tests__/billDispute.e2e.test.tsx + acceptAndNavigate.e2e.test.tsx for the client half),
    // but neither can prove the SINGLE bill carried across both apps ends in the right server
    // state — each stubs its own HTTP boundary. That correlation is what this method adds.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * BDA-003 — the amend endpoint's role gate genuinely discriminates: three negative roles are
     * refused and both positive roles are admitted, on the same bill, in one test.
     *
     * <p>Distinct from {@link #check2_amend_byNonAdminRole_isForbidden}, which asserts only the
     * three 403s. A test with no positive control cannot tell "ADMIN-only" apart from "nobody can
     * amend at all" — a broken endpoint would satisfy it. This adds the positive half the row asks
     * for (step 3), and covers <b>SUPER_ADMIN</b>, which no existing test touches even though
     * {@code PaymentController.amend} is annotated
     * {@code @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")}.</p>
     *
     * <p><b>Fixture note.</b> The negatives run against a FINAL bill because {@code @PreAuthorize}
     * fires before {@code amendBill}'s status guard, so the role check is the only possible reason
     * for rejection. The positives need a DISPUTED bill — the only status an amendment is allowed
     * from — otherwise a 400 from the status guard would be indistinguishable from "the role got
     * through", which is exactly what must be proven.</p>
     */
    @Test
    void amendRoleGateEnforced() throws Exception {
        // ── Steps 1-2: three non-admin roles, all refused on a bill they could otherwise amend ──
        Payment blocked = newPayment(Payment.PaymentStatus.FINAL, 1501L);
        for (String role : List.of("TECHNICIAN", "TEAM_LEAD", "CLIENT")) {
            MvcResult res = mvc.perform(patch("/api/payments/{id}/amend", blocked.getId())
                    .header("Authorization", bearer(7501L, role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(amend("3000.00", "1000.00", "should be blocked"))))
                .andReturn();

            assertEquals(403, res.getResponse().getStatus(),
                "Role " + role + " must be refused by @PreAuthorize with 403. Body: "
                    + res.getResponse().getContentAsString());
        }
        assertEquals(Payment.PaymentStatus.FINAL,
            paymentRepo.findById(blocked.getId()).orElseThrow().getStatus(),
            "No non-admin attempt may have changed the bill");
        assertTrue(approvalRepo.findByPaymentIdOrderByCreatedAtDesc(blocked.getId()).isEmpty(),
            "No audit row may be written by a refused role");

        // ── Step 3 (positive control #1): ADMIN is admitted and the amendment lands ─────────
        User admin = newUser(User.Role.ADMIN, "Amend Admin");
        Payment forAdmin = newPayment(Payment.PaymentStatus.DISPUTED, 1502L);
        flushAndClear();

        MvcResult adminRes = mvc.perform(patch("/api/payments/{id}/amend", forAdmin.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(amend("3000.00", "1000.00", "Admin correction"))))
            .andReturn();
        assertEquals(200, adminRes.getResponse().getStatus(),
            "ADMIN must be admitted with 200 — otherwise the three 403s above prove nothing more "
                + "than a dead endpoint. Body: " + adminRes.getResponse().getContentAsString());
        flushAndClear();
        assertEquals(Payment.PaymentStatus.PENDING_CLIENT_REVIEW,
            paymentRepo.findById(forAdmin.getId()).orElseThrow().getStatus(),
            "The admitted amendment must actually have been applied");

        // ── Step 3 (positive control #2): SUPER_ADMIN is admitted too ──────────────────────
        // The Module/Feature line for this row is "ADMIN/SUPER_ADMIN only"; the annotation names
        // both, and nothing else in the suite exercises the SUPER_ADMIN branch.
        User superAdmin = newUser(User.Role.SUPER_ADMIN, "Super Admin");
        Payment forSuper = newPayment(Payment.PaymentStatus.DISPUTED, 1503L);
        flushAndClear();

        MvcResult superRes = mvc.perform(patch("/api/payments/{id}/amend", forSuper.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(amend("2500.00", "800.00", "Super admin correction"))))
            .andReturn();
        assertEquals(200, superRes.getResponse().getStatus(),
            "SUPER_ADMIN must also be admitted with 200. Body: "
                + superRes.getResponse().getContentAsString());
        flushAndClear();
        assertEquals(Payment.PaymentStatus.PENDING_CLIENT_REVIEW,
            paymentRepo.findById(forSuper.getId()).orElseThrow().getStatus());
    }

    /**
     * BDA-004 — the full cross-role cycle on ONE bill: the client reports a dispute, an admin
     * amends it with a justification and resends, the client sees the amended bill and accepts it.
     * Final state: CLIENT_ACCEPTED, in the client's Service History, with the complete audit trail
     * present.
     *
     * <p><b>Why this is not a duplicate.</b> Each half already exists, but no test joins them:
     * {@link #check3and4_fullDisputeAmendCycleRepeats_endToEnd_withCorrectAuditChaining} ends at
     * the second amendment and never accepts, and {@link #acceptBill_movesToHistory} starts from a
     * pre-seeded PENDING_CLIENT_REVIEW bill rather than one an actual dispute+amendment produced.
     * The row's assertion is the terminal state of a bill that has been through the whole cycle,
     * and the completeness of the audit trail spanning it — which only a joined run can show.</p>
     *
     * <p><b>Cross-app note.</b> The row is written as Cypress (admin) + Detox (client) correlated
     * by a shared billingId. Detox cannot run on this host (project decision of 2026-08-04), and
     * both existing UI specs stub their own HTTP boundary, so neither observes the other's writes.
     * This drives the same three actions through the real endpoints each app calls, in the same
     * order, with the role each app authenticates as — the correlation the row is really after.</p>
     */
    @Test
    void reportAmendResendAccept() throws Exception {
        User   client   = newUser(User.Role.CLIENT, "Cycle Client");
        User   admin    = newUser(User.Role.ADMIN,  "Cycle Admin");
        Long   clientId = client.getId();
        String clientHdr = bearer(clientId, "CLIENT");
        String adminHdr  = bearer(admin.getId(), "ADMIN");

        // The job starts finished and archived, exactly as it is when a FINAL bill reaches a client.
        Fault fault = newFault(client, Fault.FaultStatus.COMPLETED);
        Payment bill = linkBillToFault(newPayment(Payment.PaymentStatus.FINAL, clientId), fault);
        Long id = bill.getId();
        flushAndClear();

        assertTrue(listContainsFault("/api/issues/history", clientId, fault.getId()),
            "Precondition: the completed job starts in Service History");

        // ── Step 1: the CLIENT reports a dispute (mobile app) ──────────────────────────────
        mvc.perform(post("/api/billing/{id}/dispute", id)
                .header("Authorization", clientHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    dispute("WRONG_AMOUNT", "Charged twice for the same router"))))
            .andExpect(mvcStatus(200));
        flushAndClear();

        assertEquals(Payment.PaymentStatus.DISPUTED,
            paymentRepo.findById(id).orElseThrow().getStatus());

        // ── Step 2: the ADMIN sees it in the dispute queue and amends with a justification ──
        // The queue the Admin portal's Bill Disputes tab renders is GET /api/payments/all filtered
        // client-side for status === 'DISPUTED' (PaymentsPage.js DisputesTab — there is no
        // dedicated disputes endpoint), so the queue is asserted at that real seam.
        MvcResult queue = mvc.perform(get("/api/payments/all")
                .header("Authorization", adminHdr))
            .andReturn();
        assertEquals(200, queue.getResponse().getStatus(), queue.getResponse().getContentAsString());
        boolean inDisputeQueue = false;
        for (JsonNode p : json.readTree(queue.getResponse().getContentAsString())) {
            if (p.path("id").asLong() == id && "DISPUTED".equals(p.path("status").asText())) {
                inDisputeQueue = true;
                assertEquals("WRONG_AMOUNT", p.path("disputeCategory").asText(),
                    "The admin must be able to read the client's dispute category from the queue");
            }
        }
        assertTrue(inDisputeQueue,
            "The disputed bill must appear in the admin's dispute queue (GET /api/payments/all, "
                + "status DISPUTED) — that is where the portal reads it from");

        mvc.perform(patch("/api/payments/{id}/amend", id)
                .header("Authorization", adminHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    amend("2500.00", "800.00", "Removed the duplicated router line item"))))
            .andExpect(mvcStatus(200));
        flushAndClear();

        // ── Step 3: the CLIENT sees the amended bill on their own endpoint, then accepts ────
        Payment amended = paymentRepo.findById(id).orElseThrow();
        assertEquals(Payment.PaymentStatus.PENDING_CLIENT_REVIEW, amended.getStatus(),
            "Resending must leave the bill awaiting the client's review");
        assertEquals(0, amended.getTotalAmount().compareTo(new BigDecimal("3300")),
            "The resent total must be the amended 2500+800 = 3300, was " + amended.getTotalAmount());

        mvc.perform(get("/api/payments/my-bills/{id}", id)
                .header("Authorization", clientHdr))
            .andExpect(mvcStatus(200))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.status").value("PENDING_CLIENT_REVIEW"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.grandTotal").value(3300));

        mvc.perform(post("/api/billing/{id}/accept", id)
                .header("Authorization", clientHdr))
            .andExpect(mvcStatus(200));
        flushAndClear();

        // ── Step 4a: final state is the terminal CLIENT_ACCEPTED ───────────────────────────
        assertEquals(Payment.PaymentStatus.CLIENT_ACCEPTED,
            paymentRepo.findById(id).orElseThrow().getStatus(),
            "After the full cycle the bill must rest at CLIENT_ACCEPTED");

        // ── Step 4b: the job is back in the client's Service History ───────────────────────
        assertAll("the accepted bill closed the job",
            () -> assertTrue(listContainsFault("/api/issues/history", clientId, fault.getId()),
                "The job must be in Service History once the amended bill is accepted"),
            () -> assertFalse(listContainsFault("/api/issues", clientId, fault.getId()),
                "The job must have left the client's active issues")
        );
        assertEquals(Fault.FaultStatus.COMPLETED,
            faultRepo.findById(fault.getId()).orElseThrow().getStatus());

        // ── Step 4c: the full audit trail spans the whole cycle ────────────────────────────
        List<PaymentApproval> trail = approvalRepo.findByPaymentIdOrderByCreatedAtDesc(id);
        List<PaymentApproval> amendments = trail.stream()
            .filter(a -> a.getAction() == PaymentApproval.Action.AMENDED).toList();
        List<PaymentApproval> accepts = trail.stream()
            .filter(a -> a.getAction() == PaymentApproval.Action.CLIENT_ACCEPTED).toList();

        assertEquals(1, amendments.size(), "Exactly one AMENDED audit row expected, trail=" + trail);
        assertEquals(1, accepts.size(), "Exactly one CLIENT_ACCEPTED audit row expected, trail=" + trail);

        PaymentApproval amendment = amendments.get(0);
        assertEquals(admin.getId(), amendment.getAdminId(), "The amendment's actor must be the admin");
        assertEquals("Cycle Admin", amendment.getAdminName());
        assertEquals("Removed the duplicated router line item", amendment.getReason(),
            "The admin's justification must be preserved in the audit trail");
        assertEquals(0, amendment.getOriginalAmount().compareTo(new BigDecimal("5000")),
            "The amendment must record the pre-amendment 5000, was " + amendment.getOriginalAmount());
        assertEquals(0, amendment.getAdjustedAmount().compareTo(new BigDecimal("3300")),
            "The amendment must record the resent 3300, was " + amendment.getAdjustedAmount());

        PaymentApproval acceptance = accepts.get(0);
        assertEquals(clientId, acceptance.getAdminId(),
            "The acceptance's actor must be the CLIENT, not the admin — the trail must show WHO "
                + "closed the cycle");
        assertEquals(0, acceptance.getOriginalAmount().compareTo(new BigDecimal("3300")),
            "The client accepted the AMENDED amount, so the acceptance row must record 3300, was "
                + acceptance.getOriginalAmount());

        // And the dispute the client raised is still readable on the bill afterwards, so the
        // trail explains why the amount changed.
        Payment finalBill = paymentRepo.findById(id).orElseThrow();
        assertEquals("WRONG_AMOUNT", finalBill.getDisputeCategory());
        assertEquals("Removed the duplicated router line item", finalBill.getAmendmentJustification());
        assertEquals(admin.getId(), finalBill.getAmendedBy());
    }

    /** Small readability helper mirroring MockMvcResultMatchers.status().is(int). */
    private static org.springframework.test.web.servlet.ResultMatcher mvcStatus(int code) {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(code);
    }
}
