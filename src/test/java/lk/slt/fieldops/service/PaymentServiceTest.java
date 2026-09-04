package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.ReviewPaymentRequest;
import lk.slt.fieldops.dto.SubmitPaymentRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.PaymentApproval;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.PaymentApprovalRepository;
import lk.slt.fieldops.repository.PaymentRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sheet {@code 04_PAYMENT_FLOW} — the Team Lead payment-submission and Admin review rules
 * (FR-10 / FR-11 / FR-12), covered at the {@link PaymentService} seam with Mockito.
 *
 * <p>Rows implemented here (one test method per row, named exactly as the row's
 * {@code Automation Mapping} column specifies):</p>
 * <ul>
 *   <li>PAY-002 {@link #calculateTotals_focVsChargeable()}</li>
 *   <li>PAY-003 {@link #labourCharge_autoCalculated()}</li>
 *   <li>PAY-004 {@link #justification_mandatoryAbove5000()}</li>
 *   <li>PAY-005 {@link #captureSignature_oneToOneEnforced()}</li>
 *   <li>PAY-006 {@link #approvePayment_statusApproved()}</li>
 *   <li>PAY-007 {@link #rejectPayment_reasonMandatory()}</li>
 *   <li>PAY-008 {@link #adjustPayment_adjustedAmountStored()}</li>
 *   <li>PAY-011 {@link #focItemsExcludedFromBilling()}</li>
 *   <li>PAY-012 {@link #focOverride_requiresJustification()}</li>
 * </ul>
 *
 * <p><b>Domain-vocabulary note (applies to PAY-001/006/008).</b> The sheet was written against a
 * status vocabulary this codebase does not use. {@code Payment.PaymentStatus} is
 * {@code DRAFT, FINAL, NOT_APPROVED, CLARIFICATION_REQUESTED, DISPUTED, PENDING_CLIENT_REVIEW,
 * CLIENT_ACCEPTED} — there is no {@code PENDING}, {@code APPROVED}, {@code REJECTED} or
 * {@code ADJUSTED} status. The real equivalents are DRAFT (= awaiting admin review, i.e. the
 * sheet's "PENDING"), FINAL (= approved) and NOT_APPROVED (= rejected); "adjusted" is not a status
 * at all but a {@link PaymentApproval.Action} recorded on an otherwise-FINAL payment. Assertions
 * below use the real constants and say so, rather than asserting names that cannot exist.</p>
 *
 * <p><b>Data-model note.</b> The sheet assumes per-item {@code payment_materials} rows carrying an
 * {@code isFoc} flag and a separate {@code payment_signatures} table. Neither exists: a Payment
 * stores three component <i>totals</i> ({@code materialsFocTotal}, {@code materialsChargeableTotal},
 * {@code labourCharge}) and a single {@code customerSignatureUrl} column. The FOC/chargeable split
 * therefore lands as a totals split, which is what the rows' arithmetic actually checks. See
 * {@code PaymentMaterialTest} (PAY-014) for the schema-shape row itself.</p>
 *
 * <p>Repository-mocked rather than {@code @SpringBootTest} because every row here is arithmetic /
 * workflow logic, not security enforcement — the same rationale
 * {@link PaymentServiceDisputeAmendTest} documents for the dispute/amend half of this feature.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock private PaymentRepository         paymentRepo;
    @Mock private PaymentApprovalRepository approvalRepo;
    @Mock private JobRepository             jobRepo;
    @Mock private FaultRepository           faultRepo;
    @Mock private WebSocketEventPublisher   webSocketEventPublisher;
    @Mock private UserRepository            userRepository;
    @Mock private NotificationService       notificationService;

    @InjectMocks private PaymentService paymentService;

    @Captor private ArgumentCaptor<PaymentApproval> approvalCaptor;
    @Captor private ArgumentCaptor<Payment>         paymentCaptor;

    private static final Long JOB_ID      = 42L;
    private static final Long PAYMENT_ID  = 15L;
    private static final Long TL_ID       = 500L;
    private static final Long ADMIN_ID    = 2L;
    private static final Long CUSTOMER_ID = 6L;
    private static final String TL_NAME    = "TL Tharindu";
    private static final String ADMIN_NAME = "Admin Amelia";

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** The Job + Fault rows submitPayment dereferences, wired into the repo mocks. */
    private void givenJobAndFaultExist() {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setJobNumber("JOB-2026-00042");
        job.setFaultId(77L);
        job.setFaultNumber("FLT-2026-00077");
        job.setCustomerId(CUSTOMER_ID);
        job.setCustomerName("Test Client");

        Fault fault = new Fault();
        fault.setId(77L);
        fault.setOpmcId(1L);

        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(faultRepo.findById(77L)).thenReturn(Optional.of(fault));
        when(paymentRepo.findByJobId(JOB_ID)).thenReturn(Optional.empty());
        when(paymentRepo.countByYear(anyInt())).thenReturn(0L);
        when(paymentRepo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SubmitPaymentRequest submission(String foc, String chargeable, String labour) {
        SubmitPaymentRequest r = new SubmitPaymentRequest();
        r.setJobId(JOB_ID);
        r.setMaterialsFocTotal(new BigDecimal(foc));
        r.setMaterialsChargeableTotal(new BigDecimal(chargeable));
        r.setLabourCharge(new BigDecimal(labour));
        return r;
    }

    /** A DRAFT payment awaiting admin review, i.e. the sheet's "PENDING". */
    private Payment draftPayment(String total) {
        Payment p = new Payment();
        p.setId(PAYMENT_ID);
        p.setPaymentNumber("PAY-2026-00015");
        p.setJobId(JOB_ID);
        p.setJobNumber("JOB-2026-00042");
        p.setCustomerId(CUSTOMER_ID);
        p.setCustomerName("Test Client");
        p.setTeamLeadId(TL_ID);
        p.setTeamLeadName(TL_NAME);
        p.setMaterialsFocTotal(new BigDecimal("1000.00"));
        p.setMaterialsChargeableTotal(new BigDecimal("800.00"));
        p.setLabourCharge(new BigDecimal("3000.00"));
        p.setTotalAmount(new BigDecimal(total));
        p.setStatus(Payment.PaymentStatus.DRAFT);
        return p;
    }

    private void givenDraftPaymentExists(Payment p) {
        when(paymentRepo.findById(PAYMENT_ID)).thenReturn(Optional.of(p));
        when(paymentRepo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepo.countBilledByYearMonth(anyInt(), anyInt())).thenReturn(0L);
    }

    private ReviewPaymentRequest review(String decision, String adjusted, String reason) {
        ReviewPaymentRequest r = new ReviewPaymentRequest();
        r.setDecision(decision);
        if (adjusted != null) r.setAdjustedAmount(new BigDecimal(adjusted));
        r.setReason(reason);
        return r;
    }

    /** True when PaymentService declares a public method with this name (any signature). */
    private static Method findMethod(String name) {
        for (Method m : PaymentService.class.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-002 — FOC vs chargeable total calculation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The row's line items are mat1 2x500 FOC (= 1000 FOC), mat2 1x800 chargeable (= 800) and
     * 2h x 1500 labour (= 3000), expecting totalFoc 1000, totalChargeable 3800 and a grand total of
     * 3800 with the FOC portion never billed. Line items are supplied as the component totals the
     * schema actually stores (see the class javadoc) — the arithmetic under test, chargeable +
     * labour with FOC excluded, is identical.
     */
    @Test
    void calculateTotals_focVsChargeable() {
        givenJobAndFaultExist();

        Payment saved = paymentService.submitPayment(
            submission("1000.00", "800.00", "3000.00"), TL_ID, TL_NAME);

        assertEquals(0, saved.getMaterialsFocTotal().compareTo(new BigDecimal("1000.00")),
            "totalFoc must be 1000 (2 x 500), was " + saved.getMaterialsFocTotal());

        BigDecimal totalChargeable = saved.getMaterialsChargeableTotal().add(saved.getLabourCharge());
        assertEquals(0, totalChargeable.compareTo(new BigDecimal("3800.00")),
            "totalChargeable must be 800 (mat2) + 3000 (labour) = 3800, was " + totalChargeable);

        assertEquals(0, saved.getTotalAmount().compareTo(new BigDecimal("3800.00")),
            "grandTotal must be 3800 — the FOC 1000 must NOT be billed. Was " + saved.getTotalAmount());

        // The FOC component is recorded (for reporting) but excluded from the billed total.
        assertEquals(0, saved.getTotalAmount()
                .compareTo(saved.getMaterialsChargeableTotal().add(saved.getLabourCharge())),
            "Billed total must be exactly chargeable + labour, with FOC excluded");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-003 — Labour charge auto-calculated from hours
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The row calls a {@code calculateLabourCharge(hours)} helper that does not exist as a public
     * API; the real auto-calculation is {@code PaymentService.computeLabourCharge}, which derives
     * hours from {@code labourStartTime}/{@code labourEndTime} and multiplies by {@code hourlyRate}
     * whenever all three are supplied (otherwise the flat {@code labourCharge} is trusted). It is
     * therefore driven through {@code submitPayment} at the rate the row's own data implies
     * (2.5h -&gt; 3750 =&gt; LKR 1500/h). Each of the row's four cases is checked.
     */
    @Test
    void labourCharge_autoCalculated() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 6, 9, 0);
        BigDecimal    rate  = new BigDecimal("1500.00");

        // 2.5 h x 1500 = 3750.00
        assertEquals(0, labourChargeFor(start, start.plusMinutes(150), rate)
                .compareTo(new BigDecimal("3750.00")),
            "2.5h at LKR 1500/h must auto-calculate to 3750.00");

        // 0 h -> 0.00
        assertEquals(0, labourChargeFor(start, start, rate).compareTo(BigDecimal.ZERO),
            "A zero-length labour window must calculate to 0.00");

        // 0.25 h x 1500 = 375.00
        assertEquals(0, labourChargeFor(start, start.plusMinutes(15), rate)
                .compareTo(new BigDecimal("375.00")),
            "0.25h at LKR 1500/h must auto-calculate to 375.00");

        // Negative duration (end before start) must be rejected, not silently billed as a credit.
        givenJobAndFaultExist();
        SubmitPaymentRequest negative = submission("0.00", "0.00", "0.00");
        negative.setLabourStartTime(start);
        negative.setLabourEndTime(start.minusHours(1));
        negative.setHourlyRate(rate);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> paymentService.submitPayment(negative, TL_ID, TL_NAME),
            "A negative labour duration must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("end time"),
            "Rejection should name the time window, was: " + ex.getMessage());
    }

    /** Submit with a labour window + rate and return the server-computed labour charge. */
    private BigDecimal labourChargeFor(LocalDateTime start, LocalDateTime end, BigDecimal rate) {
        reset(paymentRepo, jobRepo, faultRepo);
        givenJobAndFaultExist();
        SubmitPaymentRequest req = submission("0.00", "0.00", "999999.00"); // flat value must be ignored
        req.setLabourStartTime(start);
        req.setLabourEndTime(end);
        req.setHourlyRate(rate);
        return paymentService.submitPayment(req, TL_ID, TL_NAME).getLabourCharge();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-004 — Justification mandatory above LKR 5000
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A payment whose billed total exceeds LKR 5000 must not be accepted without a justification;
     * a total under the threshold, and any total accompanied by a justification, must be accepted.
     *
     * <p>All three of the row's cases are evaluated and reported together so the verdict names
     * exactly which one is wrong rather than aborting on the first.</p>
     */
    @Test
    void justification_mandatoryAbove5000() {
        // Case 2 — LKR 4999 with NO justification must be accepted.
        givenJobAndFaultExist();
        SubmitPaymentRequest under = submission("0.00", "4999.00", "0.00");
        under.setMaterialJustification(null);
        Payment underSaved = paymentService.submitPayment(under, TL_ID, TL_NAME);
        assertEquals(0, underSaved.getTotalAmount().compareTo(new BigDecimal("4999.00")));

        // Case 3 — LKR 6000 WITH a justification must be accepted.
        reset(paymentRepo, jobRepo, faultRepo);
        givenJobAndFaultExist();
        SubmitPaymentRequest overWithJust = submission("0.00", "6000.00", "0.00");
        overWithJust.setMaterialJustification("Cable damaged beyond the demarcation point");
        Payment overSaved = paymentService.submitPayment(overWithJust, TL_ID, TL_NAME);
        assertEquals("Cable damaged beyond the demarcation point", overSaved.getMaterialJustification());

        // Case 1 — LKR 6000 with NO justification must be REJECTED.
        reset(paymentRepo, jobRepo, faultRepo);
        givenJobAndFaultExist();
        SubmitPaymentRequest overNoJust = submission("0.00", "6000.00", "0.00");
        overNoJust.setMaterialJustification(null);

        assertThrows(RuntimeException.class,
            () -> paymentService.submitPayment(overNoJust, TL_ID, TL_NAME),
            "A LKR 6000 payment submitted with NO justification must be rejected. "
                + "No such threshold exists anywhere in the backend today: "
                + "SubmitPaymentRequest.materialJustification carries only @Size(max = 500) and is "
                + "never required, and PaymentService.submitPayment never inspects it. The mobile "
                + "wizard enforces a DIFFERENT rule client-side (PaymentSubmissionScreen.handleNext: "
                + "justification >= 50 characters whenever totalChargeable > 0), which a direct API "
                + "call bypasses entirely — so an unjustified high-value bill can be submitted today.");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-005 — Customer digital signature captured 1:1
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * One payment carries exactly one customer signature, and re-submitting the same job cannot
     * add a second.
     *
     * <p>The row calls {@code payService.captureSignature(id, sig)} and counts rows in a
     * {@code payment_signatures} table. Neither exists — the signature is the single
     * {@code payments.customer_signature_url} column set by {@code submitPayment}, so 1:1 is a
     * schema invariant rather than an enforced count. The row's real question ("submit again ->
     * still 1") is answered by the resubmission guard, which is what is asserted.</p>
     */
    @Test
    void captureSignature_oneToOneEnforced() {
        final String sig = "data:image/png;base64,REALSIGDATA";

        givenJobAndFaultExist();
        SubmitPaymentRequest req = submission("0.00", "800.00", "0.00");
        req.setCustomerSignatureUrl(sig);

        Payment first = paymentService.submitPayment(req, TL_ID, TL_NAME);
        assertEquals(sig, first.getCustomerSignatureUrl(),
            "The submitted signature must be stored on the payment");
        assertEquals(Payment.PaymentStatus.DRAFT, first.getStatus(),
            "A freshly submitted payment awaits admin review (the sheet's 'PENDING')");

        // Exactly one payment row was written for this job — one payment, one signature.
        verify(paymentRepo, times(1)).save(paymentCaptor.capture());
        assertEquals(sig, paymentCaptor.getValue().getCustomerSignatureUrl());

        // Submitting the SAME job again must not create a second payment (and therefore cannot
        // create a second signature): the job already has a DRAFT payment awaiting review.
        first.setId(PAYMENT_ID);
        when(paymentRepo.findByJobId(JOB_ID)).thenReturn(Optional.of(first));

        SubmitPaymentRequest again = submission("0.00", "800.00", "0.00");
        again.setCustomerSignatureUrl("data:image/png;base64,ADIFFERENTSIG");

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> paymentService.submitPayment(again, TL_ID, TL_NAME),
            "A second submission for the same job must be rejected, keeping the signature 1:1");
        assertTrue(ex.getMessage().contains("already been submitted"), ex.getMessage());

        // Still exactly one save — no second signature row/column write happened.
        verify(paymentRepo, times(1)).save(any(Payment.class));
        assertEquals(sig, first.getCustomerSignatureUrl(),
            "The original signature must survive the rejected resubmission");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-006 — Admin approves payment
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Approving stamps the reviewer, the approved amount and the review timestamp, moves the
     * payment to its approved status, and notifies the submitting Team Lead.
     *
     * <p>The row's {@code APPROVED} status is this codebase's {@code FINAL} (see the class
     * javadoc); {@code reviewedBy}/{@code reviewedAt} are {@code approvedBy}/{@code approvedAt}.</p>
     */
    @Test
    void approvePayment_statusApproved() {
        Payment payment = draftPayment("4750.00");
        givenDraftPaymentExists(payment);

        Payment approved = paymentService.reviewPayment(
            PAYMENT_ID, review("APPROVED", null, null), ADMIN_ID, ADMIN_NAME);

        assertEquals(Payment.PaymentStatus.FINAL, approved.getStatus(),
            "An approved payment must reach FINAL (this codebase's 'APPROVED')");
        assertEquals(ADMIN_ID, approved.getApprovedBy(), "reviewedBy must be the acting admin");
        assertEquals(ADMIN_NAME, approved.getApprovedByName());
        assertEquals(0, approved.getApprovedAmount().compareTo(new BigDecimal("4750.00")),
            "approvedAmount must be the full submitted total 4750, was " + approved.getApprovedAmount());
        assertNotNull(approved.getApprovedAt(), "reviewedAt must be stamped");
        assertNotNull(approved.getBillReference(), "An approved payment must get a bill reference");

        // The audit trail records the approval.
        verify(approvalRepo).save(approvalCaptor.capture());
        assertEquals(PaymentApproval.Action.APPROVED, approvalCaptor.getValue().getAction());

        // The submitting Team Lead must learn their payment was approved.
        assertTeamLeadWasNotified("approved");
    }

    /**
     * Assert the submitting Team Lead received a notification, reporting who actually got one when
     * they did not. Extracted so PAY-006 and PAY-008 both give a diagnostic rather than a raw
     * Mockito argument diff.
     */
    private void assertTeamLeadWasNotified(String event) {
        ArgumentCaptor<String> recipients = ArgumentCaptor.forClass(String.class);
        verify(webSocketEventPublisher, atLeast(0))
            .sendToUser(recipients.capture(), any(), any(), any());

        assertTrue(recipients.getAllValues().contains(TL_ID.toString()),
            "The Team Lead who submitted the payment (id " + TL_ID + ") was NOT notified that it "
                + "was " + event + ". Notifications actually sent to: " + recipients.getAllValues()
                + " (customer id is " + CUSTOMER_ID + "). PaymentService.reviewPayment calls its "
                + "notifyTeamLead(...) helper ONLY on the REJECTED and CLARIFICATION_REQUESTED "
                + "branches; the APPROVED branch notifies the CUSTOMER ('Bill Ready') and nobody "
                + "else, so a Team Lead never learns their submission cleared — or that an admin "
                + "changed the amount. The helper already exists and is used two branches above; "
                + "the APPROVED branch needs the same call.");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-007 — Admin rejects payment, reason mandatory
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void rejectPayment_reasonMandatory() {
        // (1) A null reason must be rejected outright.
        Payment forNullReason = draftPayment("5450.00");
        givenDraftPaymentExists(forNullReason);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                paymentService.reviewPayment(PAYMENT_ID, review("REJECTED", null, null),
                    ADMIN_ID, ADMIN_NAME),
            "Rejecting with a null reason must throw");
        assertTrue(ex.getMessage().toLowerCase().contains("reason is required"), ex.getMessage());
        assertEquals(Payment.PaymentStatus.DRAFT, forNullReason.getStatus(),
            "A rejected-without-reason attempt must leave the payment untouched");
        verify(approvalRepo, never()).save(any());

        // (2) A real reason rejects the payment, stores the reason and notifies the Team Lead.
        reset(paymentRepo, approvalRepo, webSocketEventPublisher);
        Payment payment = draftPayment("5450.00");
        givenDraftPaymentExists(payment);

        Payment rejected = paymentService.reviewPayment(
            PAYMENT_ID, review("REJECTED", null, "Insufficient justification"),
            ADMIN_ID, ADMIN_NAME);

        assertEquals(Payment.PaymentStatus.NOT_APPROVED, rejected.getStatus(),
            "A rejected payment must reach NOT_APPROVED (this codebase's 'REJECTED')");
        assertEquals("Insufficient justification", rejected.getRejectionReason(),
            "The rejection reason must be stored on the payment");

        verify(approvalRepo).save(approvalCaptor.capture());
        PaymentApproval audit = approvalCaptor.getValue();
        assertEquals(PaymentApproval.Action.REJECTED, audit.getAction());
        assertEquals("Insufficient justification", audit.getReason(),
            "The audit trail must carry the rejection reason");

        // The Team Lead is notified WITH the reason.
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(webSocketEventPublisher)
            .sendToUser(eq(TL_ID.toString()), any(), body.capture(), any());
        assertTrue(body.getValue().contains("Insufficient justification"),
            "The Team Lead notification must carry the rejection reason, was: " + body.getValue());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-008 — Admin adjusts the payment amount
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Approving with a different amount records an ADJUSTED audit action, stores the adjusted
     * amount and the admin's note, and notifies the Team Lead.
     *
     * <p>The row expects a status of {@code ADJUSTED}; there is no such status — an adjustment is
     * an approval at a different figure, so the payment reaches FINAL and the adjustment is
     * carried by {@link PaymentApproval.Action#ADJUSTED}. The row's {@code adminNotes} is the
     * approval's {@code reason} (the payment entity has no notes column).</p>
     */
    @Test
    void adjustPayment_adjustedAmountStored() {
        Payment payment = draftPayment("5450.00");   // original 5450
        givenDraftPaymentExists(payment);

        Payment adjusted = paymentService.reviewPayment(
            PAYMENT_ID, review("APPROVED", "4000.00", "Labour adjusted"), ADMIN_ID, ADMIN_NAME);

        assertEquals(0, adjusted.getApprovedAmount().compareTo(new BigDecimal("4000.00")),
            "approvedAmount must be the adjusted 4000, was " + adjusted.getApprovedAmount());
        assertEquals(Payment.PaymentStatus.FINAL, adjusted.getStatus(),
            "An adjusted-and-approved payment reaches FINAL — 'ADJUSTED' is an audit action here, "
                + "not a PaymentStatus");

        verify(approvalRepo).save(approvalCaptor.capture());
        PaymentApproval audit = approvalCaptor.getValue();
        assertEquals(PaymentApproval.Action.ADJUSTED, audit.getAction(),
            "The audit row must record the adjustment as ADJUSTED");
        assertEquals(0, audit.getOriginalAmount().compareTo(new BigDecimal("5450.00")),
            "The audit row must record the pre-adjustment 5450 as originalAmount");
        assertEquals(0, audit.getAdjustedAmount().compareTo(new BigDecimal("4000.00")),
            "The audit row must record the post-adjustment 4000");
        assertEquals("Labour adjusted", audit.getReason(),
            "adminNotes ('Labour adjusted') must be stored as the audit reason");

        // An adjustment changes what the Team Lead gets paid, so the Team Lead must be notified.
        assertTeamLeadWasNotified("adjusted from LKR 5450 to LKR 4000");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-011 — FOC materials excluded from billing
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The row verifies a {@code billingService.addCharge()} call carrying the chargeable-only
     * figure. There is no separate billing service in this system: approval IS the billing step —
     * {@code reviewPayment} sets {@code approvedAmount} and mints a {@code billReference}, then
     * pushes the amount to the client as the "Bill Ready" notification. So the row's real question,
     * "is the client charged 3800 and not 4800?", is asserted at those three places.
     */
    @Test
    void focItemsExcludedFromBilling() {
        // Submit: FOC 1000, chargeable 800, labour 3000.
        givenJobAndFaultExist();
        Payment submitted = paymentService.submitPayment(
            submission("1000.00", "800.00", "3000.00"), TL_ID, TL_NAME);

        assertEquals(0, submitted.getTotalAmount().compareTo(new BigDecimal("3800.00")),
            "The billable total must exclude the LKR 1000 FOC, was " + submitted.getTotalAmount());

        // Approve it and confirm the amount that reaches billing is the chargeable-only figure.
        reset(paymentRepo, approvalRepo, webSocketEventPublisher);
        submitted.setId(PAYMENT_ID);
        submitted.setStatus(Payment.PaymentStatus.DRAFT);
        givenDraftPaymentExists(submitted);

        Payment billed = paymentService.reviewPayment(
            PAYMENT_ID, review("APPROVED", null, null), ADMIN_ID, ADMIN_NAME);

        assertEquals(0, billed.getApprovedAmount().compareTo(new BigDecimal("3800.00")),
            "The billed (approved) amount must be 3800 — the FOC 1000 must not be added. Was "
                + billed.getApprovedAmount());
        assertNotNull(billed.getBillReference(), "Approval must mint the bill reference");

        // The client-facing bill notification quotes the chargeable-only amount.
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(webSocketEventPublisher).sendToUser(
            eq(CUSTOMER_ID.toString()), any(), body.capture(), eq("PAYMENT_APPROVED"));
        assertTrue(body.getValue().contains("3800"),
            "The client's bill notification must quote 3800, was: " + body.getValue());
        assertFalse(body.getValue().contains("4800"),
            "The client must never be quoted the FOC-inclusive 4800, was: " + body.getValue());

        // And the FOC figure is still recorded, just not billed.
        assertEquals(0, billed.getMaterialsFocTotal().compareTo(new BigDecimal("1000.00")),
            "The FOC total must still be recorded for reporting");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAY-012 — FOC override requires a justification
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Re-classifying a chargeable item as FOC must require a justification, and that justification
     * must be stored against the override.
     *
     * <p>The API the row names ({@code payService.overrideFoc(matId, justification)}) may not
     * exist. It is therefore resolved <b>reflectively</b> rather than called directly, so this test
     * always executes and returns a real verdict instead of failing to compile — the same shape
     * used for JOB-009 / JOB-016 on sheet {@code 03_JOB_LIFECYCLE}. It deliberately does not assert
     * the feature's absence: once an override API lands, this test starts exercising it.</p>
     */
    @Test
    void focOverride_requiresJustification() throws Exception {
        Method override = findMethod("overrideFoc");
        if (override == null) override = findMethod("overrideFocClassification");

        assertNotNull(override,
            "PaymentService exposes no FOC-override API. The FOC/chargeable split is fixed at "
                + "submission time as two opaque component totals (materialsFocTotal / "
                + "materialsChargeableTotal) with no per-item rows, so there is nothing to "
                + "re-classify and nowhere to record an override justification: Payment has no "
                + "overrideFocJustification column, and the only justification fields are "
                + "materialJustification (submission) and amendmentJustification (dispute amend). "
                + "Reclassifying an item as FOC today means an Admin silently editing the two "
                + "totals through PATCH /api/payments/{id}/amend — which is only reachable on a "
                + "DISPUTED bill and records one blanket justification, not a per-item one. "
                + "Needs a payment_materials table with a per-row is_foc flag plus an override "
                + "justification column.");

        // Once the API exists: no justification must be refused, a justification must be stored.
        final Method api = override;
        assertThrows(Exception.class,
            () -> api.invoke(paymentService, 1L, null),
            "Marking a chargeable item as FOC with no justification must be refused");
    }
}
