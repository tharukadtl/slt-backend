package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.AmendBillRequest;
import lk.slt.fieldops.dto.ReportDisputeRequest;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.PaymentApproval;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.PaymentApprovalRepository;
import lk.slt.fieldops.repository.PaymentRepository;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Stage-1 backend verification for the Report → Amend → Resend → Report cycle and its audit
 * trail (QA_Compliance_Consolidated_Report §2.1, FR-31 / FR-32, SRS §5.2.3 & §5.5.2.1),
 * checks #3 and #4.
 *
 * <p>This is the fast, repo-mocked twin of the end-to-end coverage in
 * {@code BillDisputeAmendmentIntegrationTest.check3and4_...}, which persists the real
 * {@code DISPUTED} / {@code PENDING_CLIENT_REVIEW} / {@code AMENDED} rows against MySQL (runnable
 * once the status/action ENUM columns were widened by
 * {@code migrations/manual/add_dispute_amendment_enum_values.sql}). Both are kept: this one
 * isolates and pins the workflow LOGIC — status transitions, total recomputation, and (critically)
 * the before/after audit chaining across TWO amendments — without the ~15s Spring-context/DB boot,
 * and captures the {@code PaymentApproval} args directly via {@link ArgumentCaptor}. Repository
 * mocking is appropriate here because #3/#4 are workflow-logic checks, not security-enforcement
 * checks (unlike #1/#2, which must run through the real filter chain).</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceDisputeAmendTest {

    @Mock private PaymentRepository         paymentRepo;
    @Mock private PaymentApprovalRepository approvalRepo;
    @Mock private JobRepository             jobRepo;
    @Mock private FaultRepository           faultRepo;
    @Mock private WebSocketEventPublisher   webSocketEventPublisher;

    @InjectMocks private PaymentService paymentService;

    @Captor private ArgumentCaptor<PaymentApproval> approvalCaptor;

    private static final Long PAYMENT_ID = 42L;
    private static final Long CLIENT_ID  = 6L;
    private static final Long ADMIN_ID   = 99L;
    private static final String ADMIN_NAME = "Admin Amelia";

    /** A FINAL (approved) bill for LKR 5000, owned by the client. */
    private Payment finalBill() {
        Payment p = new Payment();
        p.setId(PAYMENT_ID);
        p.setPaymentNumber("PAY-2026-00042");
        p.setJobNumber("JOB-2026-00042");
        p.setCustomerId(CLIENT_ID);
        p.setCustomerName("Test Client");
        p.setMaterialsFocTotal(new BigDecimal("100.00"));
        p.setMaterialsChargeableTotal(new BigDecimal("4000.00"));
        p.setLabourCharge(new BigDecimal("1000.00"));
        p.setTotalAmount(new BigDecimal("5000.00"));
        p.setApprovedAmount(new BigDecimal("5000.00"));
        p.setStatus(Payment.PaymentStatus.FINAL);
        return p;
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

    // ══════════════════════════════════════════════════════════════════════════
    // CHECK #3 & #4 — full repeatable cycle + audit chaining across TWO amendments
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void fullDisputeAmendCycleRepeats_withCorrectAuditChaining() {
        // A single mutable Payment instance threads through the whole cycle, exactly as the real
        // persistence context would return the same managed row across calls.
        Payment bill = finalBill();
        when(paymentRepo.findById(PAYMENT_ID)).thenReturn(Optional.of(bill));
        when(paymentRepo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        // (a) Client disputes the FINAL bill → DISPUTED -----------------------
        Payment afterDispute1 = paymentService.reportDispute(
            PAYMENT_ID, dispute("WRONG_AMOUNT", "overcharged on materials"), CLIENT_ID);
        assertEquals(Payment.PaymentStatus.DISPUTED, afterDispute1.getStatus());
        assertEquals("WRONG_AMOUNT", afterDispute1.getDisputeCategory());
        assertEquals("overcharged on materials", afterDispute1.getDisputeDescription());
        assertNotNull(afterDispute1.getDisputedAt());

        // (b) Admin amends: chargeable 3000 + labour 1000 = 4000 --------------
        Payment afterAmend1 = paymentService.amendBill(
            PAYMENT_ID, amend("3000.00", "1000.00", "Removed duplicated router line item"),
            ADMIN_ID, ADMIN_NAME);
        assertEquals(Payment.PaymentStatus.PENDING_CLIENT_REVIEW, afterAmend1.getStatus());
        assertEquals(0, afterAmend1.getTotalAmount().compareTo(new BigDecimal("4000")),
            "New total must be chargeable+labour = 4000, was " + afterAmend1.getTotalAmount());
        assertEquals(0, afterAmend1.getApprovedAmount().compareTo(new BigDecimal("4000")));
        assertEquals(ADMIN_ID, afterAmend1.getAmendedBy());
        assertEquals(ADMIN_NAME, afterAmend1.getAmendedByName());
        assertEquals("Removed duplicated router line item", afterAmend1.getAmendmentJustification());

        // (c) SAME client disputes the AMENDED bill again → DISPUTED ----------
        Payment afterDispute2 = paymentService.reportDispute(
            PAYMENT_ID, dispute("STILL_WRONG", "labour charge still too high"), CLIENT_ID);
        assertEquals(Payment.PaymentStatus.DISPUTED, afterDispute2.getStatus(),
            "Cycle must repeat: an amended (PENDING_CLIENT_REVIEW) bill can be disputed again");
        assertEquals("STILL_WRONG", afterDispute2.getDisputeCategory());
        assertEquals("labour charge still too high", afterDispute2.getDisputeDescription());

        // (d) Admin amends again: chargeable 2500 + labour 800 = 3300 ---------
        Payment afterAmend2 = paymentService.amendBill(
            PAYMENT_ID, amend("2500.00", "800.00", "Reduced labour to quoted rate"),
            ADMIN_ID, ADMIN_NAME);
        assertEquals(Payment.PaymentStatus.PENDING_CLIENT_REVIEW, afterAmend2.getStatus());
        assertEquals(0, afterAmend2.getTotalAmount().compareTo(new BigDecimal("3300")),
            "Second amendment total must be 2500+800 = 3300, was " + afterAmend2.getTotalAmount());

        // ── CHECK #4: two AMENDED audit rows with correct before/after chaining ──
        verify(approvalRepo, times(2)).save(approvalCaptor.capture());
        List<PaymentApproval> audits = approvalCaptor.getAllValues();

        PaymentApproval first  = audits.get(0);  // 1st amendment: 5000 -> 4000
        PaymentApproval second = audits.get(1);  // 2nd amendment: 4000 -> 3300

        assertEquals(PaymentApproval.Action.AMENDED, first.getAction());
        assertEquals(PAYMENT_ID, first.getPaymentId());
        assertEquals(0, first.getOriginalAmount().compareTo(new BigDecimal("5000")),
            "1st amendment originalAmount must be the pre-amend 5000, was " + first.getOriginalAmount());
        assertEquals(0, first.getAdjustedAmount().compareTo(new BigDecimal("4000")),
            "1st amendment adjustedAmount must be the new 4000");
        assertEquals("Removed duplicated router line item", first.getReason());
        assertEquals(ADMIN_ID, first.getAdminId());
        assertEquals(ADMIN_NAME, first.getAdminName());

        // The chaining check: the 2nd amendment's "before" MUST be the 1st amendment's result
        // (4000), NOT the very first original (5000). This is what proves before/after is correct
        // on repeated cycles, not just the first amendment.
        assertEquals(PaymentApproval.Action.AMENDED, second.getAction());
        assertEquals(0, second.getOriginalAmount().compareTo(new BigDecimal("4000")),
            "2nd amendment originalAmount MUST be 4000 (amount before the 2nd amend), NOT the "
                + "original 5000 — was " + second.getOriginalAmount());
        assertEquals(0, second.getAdjustedAmount().compareTo(new BigDecimal("3300")),
            "2nd amendment adjustedAmount must be the new 3300");
        assertEquals("Reduced labour to quoted rate", second.getReason());
        assertEquals(ADMIN_ID, second.getAdminId());
        assertEquals(ADMIN_NAME, second.getAdminName());

        // The client was re-notified on each amendment (SRS 5.5.2.1 resend step).
        verify(webSocketEventPublisher, times(2))
            .sendToUser(eq(CLIENT_ID.toString()), any(), any(), eq("BILL_AMENDED"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACCEPT (Client) — terminal CLIENT_ACCEPTED + client-actor audit row
    // ══════════════════════════════════════════════════════════════════════════

    /** Happy path: accepting a FINAL bill sets CLIENT_ACCEPTED, writes a client-actor audit row,
        and notifies Admin. */
    @Test
    void acceptFinalBill_setsTerminalStatus_writesClientAuditRow_notifiesAdmin() {
        Payment bill = finalBill();
        when(paymentRepo.findById(PAYMENT_ID)).thenReturn(Optional.of(bill));
        when(paymentRepo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment accepted = paymentService.acceptBill(PAYMENT_ID, CLIENT_ID);

        assertEquals(Payment.PaymentStatus.CLIENT_ACCEPTED, accepted.getStatus());

        verify(approvalRepo).save(approvalCaptor.capture());
        PaymentApproval audit = approvalCaptor.getValue();
        assertEquals(PaymentApproval.Action.CLIENT_ACCEPTED, audit.getAction());
        assertEquals(PAYMENT_ID, audit.getPaymentId());
        // The actor is the CLIENT, not an admin.
        assertEquals(CLIENT_ID, audit.getAdminId());
        assertEquals("Test Client", audit.getAdminName());
        // No amount change: originalAmount = accepted amount (5000), adjustedAmount stays null.
        assertEquals(0, audit.getOriginalAmount().compareTo(new BigDecimal("5000")),
            "originalAmount should record the accepted amount, was " + audit.getOriginalAmount());
        assertNull(audit.getAdjustedAmount(), "Acceptance changes no amount; adjustedAmount must be null");

        // Admin is notified of the acceptance (mirrors the dispute role-broadcast).
        verify(webSocketEventPublisher).sendToRole(eq("admin"), any(), any(), eq("BILL_ACCEPTED"));
    }

    /** Guard: accept is only allowed from FINAL / PENDING_CLIENT_REVIEW — a DISPUTED bill cannot be
        accepted (it must be amended first). No status write, no audit row. */
    @Test
    void accept_fromDisputedStatus_isRejected() {
        Payment bill = finalBill();
        bill.setStatus(Payment.PaymentStatus.DISPUTED);
        when(paymentRepo.findById(PAYMENT_ID)).thenReturn(Optional.of(bill));

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            paymentService.acceptBill(PAYMENT_ID, CLIENT_ID));
        assertTrue(ex.getMessage().contains("Only an approved or amended bill can be accepted"),
            ex.getMessage());

        verify(paymentRepo, never()).save(any());
        verify(approvalRepo, never()).save(any());
    }

    /** Guard: an amendment is only allowed from DISPUTED (mirrors the FINAL-state rejection). */
    @Test
    void amend_fromNonDisputedStatus_isRejected() {
        Payment bill = finalBill(); // FINAL, not DISPUTED
        when(paymentRepo.findById(PAYMENT_ID)).thenReturn(Optional.of(bill));

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            paymentService.amendBill(PAYMENT_ID, amend("3000.00", "1000.00", "n/a"), ADMIN_ID, ADMIN_NAME));
        assertTrue(ex.getMessage().contains("Only a disputed bill can be amended"), ex.getMessage());

        verify(paymentRepo, never()).save(any());
        verify(approvalRepo, never()).save(any());
    }
}
