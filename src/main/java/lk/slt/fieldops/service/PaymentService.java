package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.AmendBillRequest;
import lk.slt.fieldops.dto.ReportDisputeRequest;
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
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PaymentService — full billing workflow.
 *
 *   submitPayment()      → TL submits after job completion
 *   reviewPayment()      → Admin approves / rejects / adjusts
 *   getPendingPayments() → Admin queue
 *   getByBranch()        → Branch history
 *   getByTeamLead()      → TL's submitted payments
 *   getForCustomer()     → Customer billing history
 *   getApprovalHistory() → Audit trail per payment
 */
@Service
public class PaymentService {

    private final PaymentRepository         paymentRepo;
    private final PaymentApprovalRepository approvalRepo;
    private final JobRepository             jobRepo;
    private final FaultRepository           faultRepo;
    private final WebSocketEventPublisher   webSocketEventPublisher;

    public PaymentService(PaymentRepository paymentRepo,
                          PaymentApprovalRepository approvalRepo,
                          JobRepository jobRepo,
                          FaultRepository faultRepo,
                          WebSocketEventPublisher webSocketEventPublisher) {
        this.paymentRepo  = paymentRepo;
        this.approvalRepo = approvalRepo;
        this.jobRepo      = jobRepo;
        this.faultRepo    = faultRepo;
        this.webSocketEventPublisher = webSocketEventPublisher;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. SUBMIT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Payment submitPayment(SubmitPaymentRequest req,
                                  Long teamLeadId, String teamLeadName) {
        Payment p = paymentRepo.findByJobId(req.getJobId()).orElse(null);
        if (p != null) {
            // Only a rejected or clarification-requested payment can be resubmitted —
            // a payment already in DRAFT (pending review) or FINAL (approved) cannot.
            if (p.getStatus() != Payment.PaymentStatus.NOT_APPROVED
                    && p.getStatus() != Payment.PaymentStatus.CLARIFICATION_REQUESTED) {
                throw new RuntimeException(
                    "A payment has already been submitted for Job #" + req.getJobId());
            }
            p.setRejectionReason(null);
            p.setApprovedBy(null);
            p.setApprovedByName(null);
            p.setApprovedAt(null);
            p.setApprovedAmount(null);
            p.setBillReference(null);
        } else {
            p = new Payment();
            String payNum = generatePaymentNumber();
            p.setPaymentNumber(payNum);
            p.setPaymentReference(payNum);
        }

        Job job = jobRepo.findById(req.getJobId())
            .orElseThrow(() -> new ResourceNotFoundException("Job", req.getJobId()));
        Fault fault = faultRepo.findById(job.getFaultId())
            .orElseThrow(() -> new ResourceNotFoundException("Fault", job.getFaultId()));

        BigDecimal foc        = safe(req.getMaterialsFocTotal());
        BigDecimal chargeable = safe(req.getMaterialsChargeableTotal());
        BigDecimal labour     = computeLabourCharge(req);
        BigDecimal total      = chargeable.add(labour);

        p.setJobId(req.getJobId());
        p.setJobNumber(job.getJobNumber());
        p.setFaultId(job.getFaultId());
        p.setFaultNumber(job.getFaultNumber());
        p.setBranchId(fault.getBranchId());
        p.setCustomerId(job.getCustomerId());
        p.setCustomerName(job.getCustomerName());
        p.setTechnicianId(job.getTechnicianId());
        p.setTechnicianName(job.getTechnicianName());
        p.setTeamLeadId(teamLeadId);
        p.setTeamLeadName(teamLeadName);
        p.setMaterialsFocTotal(foc);
        p.setMaterialsChargeableTotal(chargeable);
        p.setLabourCharge(labour);
        p.setLabourStartTime(req.getLabourStartTime());
        p.setLabourEndTime(req.getLabourEndTime());
        p.setHourlyRate(req.getHourlyRate());
        p.setTotalAmount(total);
        p.setCustomerSignatureUrl(req.getCustomerSignatureUrl());
        p.setJobPhotosUrls(req.getJobPhotosUrls());
        p.setMaterialJustification(req.getMaterialJustification());
        p.setWorkSummary(req.getWorkSummary());
        p.setStatus(Payment.PaymentStatus.DRAFT);

        Payment saved = paymentRepo.save(p);

        webSocketEventPublisher.sendToRole("admin",
            "New Payment Submitted",
            teamLeadName + " submitted payment " + saved.getPaymentNumber()
                + " for Job #" + job.getJobNumber() + " — LKR " + total,
            "PAYMENT_SUBMITTED");

        return saved;
    }

    /**
     * If a start/end time and hourly rate are all provided, the labour charge
     * is computed server-side from them (hours * rate) rather than trusting
     * the flat labourCharge value — otherwise falls back to the flat value.
     */
    private BigDecimal computeLabourCharge(SubmitPaymentRequest req) {
        if (req.getLabourStartTime() != null && req.getLabourEndTime() != null && req.getHourlyRate() != null) {
            if (req.getLabourEndTime().isBefore(req.getLabourStartTime())) {
                throw new RuntimeException("Labour end time cannot be before start time.");
            }
            double hours = Duration.between(req.getLabourStartTime(), req.getLabourEndTime()).toMinutes() / 60.0;
            return req.getHourlyRate().multiply(BigDecimal.valueOf(hours))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return safe(req.getLabourCharge());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. REVIEW (Admin)
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Payment reviewPayment(Long paymentId, ReviewPaymentRequest req,
                                  Long adminId, String adminName) {
        Payment payment = findOrThrow(paymentId);

        if (payment.getStatus() != Payment.PaymentStatus.DRAFT) {
            throw new RuntimeException(
                "Payment already reviewed. Current status: " + payment.getStatus());
        }

        PaymentApproval approval = new PaymentApproval();
        approval.setPaymentId(paymentId);
        approval.setAdminId(adminId);
        approval.setAdminName(adminName);
        approval.setAdminRole(currentCallerRole());
        approval.setIpAddress(lk.slt.fieldops.shared.RequestContext.getClientIp());
        approval.setOriginalAmount(payment.getTotalAmount());

        if ("REJECTED".equalsIgnoreCase(req.getDecision())) {
            if (req.getReason() == null || req.getReason().isBlank()) {
                throw new RuntimeException("A reason is required when rejecting a payment.");
            }
            payment.setStatus(Payment.PaymentStatus.NOT_APPROVED);
            payment.setRejectionReason(req.getReason());
            approval.setAction(PaymentApproval.Action.REJECTED);
            approval.setReason(req.getReason());

            notifyTeamLead(payment, "Payment Rejected",
                "Payment " + payment.getPaymentNumber() + " was rejected. Reason: " + req.getReason());

        } else if ("CLARIFICATION_REQUESTED".equalsIgnoreCase(req.getDecision())) {
            if (req.getReason() == null || req.getReason().isBlank()) {
                throw new RuntimeException("A reason is required when requesting clarification.");
            }
            payment.setStatus(Payment.PaymentStatus.CLARIFICATION_REQUESTED);
            approval.setAction(PaymentApproval.Action.CLARIFICATION_REQUESTED);
            approval.setReason(req.getReason());

            notifyTeamLead(payment, "Clarification Requested",
                "Admin requested clarification on payment " + payment.getPaymentNumber()
                    + ": " + req.getReason() + ". Please review and resubmit.");

        } else if ("APPROVED".equalsIgnoreCase(req.getDecision())) {
            payment.setApprovedBy(adminId);
            payment.setApprovedByName(adminName);
            payment.setApprovedAt(LocalDateTime.now());

            if (req.getAdjustedAmount() != null &&
                req.getAdjustedAmount().compareTo(payment.getTotalAmount()) != 0) {
                if (req.getReason() == null || req.getReason().isBlank()) {
                    throw new RuntimeException("A justification is required when adjusting the billing amount.");
                }
                payment.setApprovedAmount(req.getAdjustedAmount());
                approval.setAction(PaymentApproval.Action.ADJUSTED);
                approval.setAdjustedAmount(req.getAdjustedAmount());
            } else {
                payment.setApprovedAmount(payment.getTotalAmount());
                approval.setAction(PaymentApproval.Action.APPROVED);
            }
            approval.setReason(req.getReason());
            payment.setStatus(Payment.PaymentStatus.FINAL);
            payment.setBillReference(generateBillReference());

            if (payment.getCustomerId() != null) {
                webSocketEventPublisher.sendToUser(
                    payment.getCustomerId().toString(),
                    "Bill Ready",
                    "Your bill for Job #" + payment.getJobNumber() + " is ready. Reference: "
                        + payment.getBillReference() + ". Amount: LKR " + payment.getApprovedAmount(),
                    "PAYMENT_APPROVED");
            }

        } else {
            throw new RuntimeException(
                "Invalid decision: '" + req.getDecision() + "'. Valid: APPROVED, REJECTED, or CLARIFICATION_REQUESTED");
        }

        approvalRepo.save(approval);
        return paymentRepo.save(payment);
    }

    private void notifyTeamLead(Payment payment, String title, String message) {
        if (payment.getTeamLeadId() != null) {
            webSocketEventPublisher.sendToUser(payment.getTeamLeadId().toString(), title, message, "PAYMENT_UPDATE");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. DISPUTE (Client) — FR-31, SRS 5.2.3
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Client reports an issue on an approved (FINAL) or amended (PENDING_CLIENT_REVIEW)
     * bill. Sets status to DISPUTED so the bill/job stays in the client's active list
     * (not moved to Service History) until resolved, and notifies Admin.
     * Supports the repeatable Report → Amend → Resend → Report cycle (SRS 5.5.2.1).
     */
    @Transactional
    public Payment reportDispute(Long paymentId, ReportDisputeRequest req, Long customerId) {
        Payment payment = findOrThrow(paymentId);

        // A bill can only be disputed once it has been made available to the client:
        // either a freshly approved bill (FINAL) or an amended bill awaiting review
        // (PENDING_CLIENT_REVIEW). Disputing again from PENDING_CLIENT_REVIEW is what
        // makes the cycle repeatable.
        if (payment.getStatus() != Payment.PaymentStatus.FINAL
                && payment.getStatus() != Payment.PaymentStatus.PENDING_CLIENT_REVIEW) {
            throw new RuntimeException(
                "Only an approved or amended bill can be disputed. Current status: " + payment.getStatus());
        }

        payment.setDisputeCategory(req.getCategory());
        payment.setDisputeDescription(req.getDescription());
        payment.setDisputePhotoUrl(req.getPhotoUrl());
        payment.setDisputedAt(LocalDateTime.now());
        payment.setStatus(Payment.PaymentStatus.DISPUTED);

        Payment saved = paymentRepo.save(payment);

        // Notify Admin of the dispute (SRS 5.2.3), mirroring the submitPayment role-broadcast.
        webSocketEventPublisher.sendToRole("admin",
            "Bill Disputed",
            (payment.getCustomerName() != null ? payment.getCustomerName() : "A client")
                + " reported an issue on bill " + payment.getPaymentNumber()
                + " for Job #" + payment.getJobNumber() + " — " + req.getCategory(),
            "BILL_DISPUTED");

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. AMEND (Admin) — FR-32, SRS 5.5.2.1
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Admin amends a DISPUTED bill (adjusts the FOC / chargeable / labour component
     * totals) with a mandatory justification, sets status to PENDING_CLIENT_REVIEW,
     * re-notifies the client, and records the change in the immutable audit trail
     * (payment_approvals, action AMENDED) with before/after amounts (SRS 5.5.2.1, 7.1.4).
     */
    @Transactional
    public Payment amendBill(Long paymentId, AmendBillRequest req,
                              Long adminId, String adminName) {
        Payment payment = findOrThrow(paymentId);

        if (payment.getStatus() != Payment.PaymentStatus.DISPUTED) {
            throw new RuntimeException(
                "Only a disputed bill can be amended. Current status: " + payment.getStatus());
        }

        BigDecimal beforeAmount = payment.getApprovedAmount() != null
            ? payment.getApprovedAmount() : payment.getTotalAmount();

        // Apply only the components the admin supplied; keep current values otherwise.
        if (req.getMaterialsFocTotal() != null) {
            payment.setMaterialsFocTotal(req.getMaterialsFocTotal());
        }
        if (req.getMaterialsChargeableTotal() != null) {
            payment.setMaterialsChargeableTotal(req.getMaterialsChargeableTotal());
        }
        if (req.getLabourCharge() != null) {
            payment.setLabourCharge(req.getLabourCharge());
        }

        // Recompute the total exactly as submitPayment does: chargeable + labour
        // (FOC is free-of-charge and excluded from the billed total).
        BigDecimal newTotal = safe(payment.getMaterialsChargeableTotal())
            .add(safe(payment.getLabourCharge()));
        payment.setTotalAmount(newTotal);
        payment.setApprovedAmount(newTotal);

        payment.setAmendmentJustification(req.getJustification());
        payment.setAmendedBy(adminId);
        payment.setAmendedByName(adminName);
        payment.setAmendedAt(LocalDateTime.now());
        payment.setStatus(Payment.PaymentStatus.PENDING_CLIENT_REVIEW);

        // Immutable audit trail entry for the amendment (before/after + justification).
        PaymentApproval audit = new PaymentApproval();
        audit.setPaymentId(paymentId);
        audit.setAction(PaymentApproval.Action.AMENDED);
        audit.setAdminId(adminId);
        audit.setAdminName(adminName);
        audit.setAdminRole(currentCallerRole());
        audit.setIpAddress(lk.slt.fieldops.shared.RequestContext.getClientIp());
        audit.setOriginalAmount(beforeAmount);
        audit.setAdjustedAmount(newTotal);
        audit.setReason(req.getJustification());
        approvalRepo.save(audit);

        Payment saved = paymentRepo.save(payment);

        // Re-notify the client to review the amended bill (SRS 5.5.2.1),
        // mirroring the "Bill Ready" client notification in reviewPayment.
        if (payment.getCustomerId() != null) {
            webSocketEventPublisher.sendToUser(
                payment.getCustomerId().toString(),
                "Bill Amended",
                "Your bill for Job #" + payment.getJobNumber()
                    + " has been amended and resent for review. New amount: LKR " + newTotal,
                "BILL_AMENDED");
        }

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. ACCEPT (Client) — closes the dispute / amendment cycle (FR-31 / FR-32)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Client accepts an approved (FINAL) or amended (PENDING_CLIENT_REVIEW) bill, closing the
     * Report → Amend → Resend cycle. Moves the bill to the terminal CLIENT_ACCEPTED status so it
     * leaves the client's active list and settles into Service History, records the acceptance in
     * the immutable audit trail (payment_approvals, action CLIENT_ACCEPTED — the CLIENT is the
     * actor here, not an admin), and notifies Admin. Mirrors reportDispute's allowed-from set,
     * ownership model and admin role-broadcast.
     */
    @Transactional
    public Payment acceptBill(Long paymentId, Long customerId) {
        Payment payment = findOrThrow(paymentId);

        // Same allowed-from set as dispute: a bill can only be accepted once it has been made
        // available to the client — a freshly approved bill (FINAL) or an amended bill awaiting
        // review (PENDING_CLIENT_REVIEW). A DISPUTED bill must be amended by Admin (which moves it
        // back to PENDING_CLIENT_REVIEW) before it can be accepted.
        if (payment.getStatus() != Payment.PaymentStatus.FINAL
                && payment.getStatus() != Payment.PaymentStatus.PENDING_CLIENT_REVIEW) {
            throw new RuntimeException(
                "Only an approved or amended bill can be accepted. Current status: " + payment.getStatus());
        }

        BigDecimal acceptedAmount = payment.getApprovedAmount() != null
            ? payment.getApprovedAmount() : payment.getTotalAmount();

        payment.setStatus(Payment.PaymentStatus.CLIENT_ACCEPTED);
        Payment saved = paymentRepo.save(payment);

        // Immutable audit trail entry for the acceptance. The actor is the CLIENT, so the
        // admin-shaped columns carry the customer's id / name / role. Acceptance changes no amount,
        // so originalAmount records the accepted amount and adjustedAmount stays null — consistent
        // with how a plain (non-adjusting) APPROVED row is written in reviewPayment.
        PaymentApproval audit = new PaymentApproval();
        audit.setPaymentId(paymentId);
        audit.setAction(PaymentApproval.Action.CLIENT_ACCEPTED);
        audit.setAdminId(customerId);
        audit.setAdminName(payment.getCustomerName());
        audit.setAdminRole(currentCallerRole());
        audit.setIpAddress(lk.slt.fieldops.shared.RequestContext.getClientIp());
        audit.setOriginalAmount(acceptedAmount);
        approvalRepo.save(audit);

        // Notify Admin the client accepted, mirroring the reportDispute admin role-broadcast.
        webSocketEventPublisher.sendToRole("admin",
            "Bill Accepted",
            (payment.getCustomerName() != null ? payment.getCustomerName() : "A client")
                + " accepted bill " + payment.getPaymentNumber()
                + " for Job #" + payment.getJobNumber() + " — LKR " + acceptedAmount,
            "BILL_ACCEPTED");

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // READ METHODS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Payment getById(Long id) { return findOrThrow(id); }

    @Transactional(readOnly = true)
    public List<Payment> getPendingPayments() {
        return paymentRepo.findByStatusOrderBySubmittedAtAsc(Payment.PaymentStatus.DRAFT);
    }

    @Transactional(readOnly = true)
    public List<Payment> getAll() {
        return paymentRepo.findAll(org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "submittedAt"));
    }

    @Transactional(readOnly = true)
    public List<Payment> getByBranch(Long branchId) {
        return paymentRepo.findByBranchIdOrderBySubmittedAtDesc(branchId);
    }

    @Transactional(readOnly = true)
    public List<Payment> getByTeamLead(Long teamLeadId) {
        return paymentRepo.findByTeamLeadIdOrderBySubmittedAtDesc(teamLeadId);
    }

    @Transactional(readOnly = true)
    public List<Payment> getForCustomer(Long customerId) {
        return paymentRepo.findByCustomerIdOrderBySubmittedAtDesc(customerId);
    }

    @Transactional(readOnly = true)
    public List<PaymentApproval> getApprovalHistory(Long paymentId) {
        findOrThrow(paymentId);
        return approvalRepo.findByPaymentIdOrderByCreatedAtDesc(paymentId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Payment findOrThrow(Long id) {
        return paymentRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    }

    private BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** Role of the currently authenticated caller, for audit trail entries (NFR 7.1.4). */
    private String currentCallerRole() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        return auth.getAuthorities().stream()
            .findFirst()
            .map(a -> a.getAuthority().replace("ROLE_", ""))
            .orElse(null);
    }

    private String generatePaymentNumber() {
        int  year  = LocalDateTime.now().getYear();
        long count = paymentRepo.countByYear(year) + 1;
        return String.format("PAY-%d-%05d", year, count);
    }

    private String generateBillReference() {
        LocalDateTime now   = LocalDateTime.now();
        int           year  = now.getYear();
        int           month = now.getMonthValue();
        long          count = paymentRepo.countBilledByYearMonth(year, month) + 1;
        return String.format("BILL-%d-%02d-%05d", year, month, count);
    }
}
