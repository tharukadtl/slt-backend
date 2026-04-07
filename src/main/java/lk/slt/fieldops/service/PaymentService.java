package lk.slt.fieldops.payment.service;

import lk.slt.fieldops.payment.dto.ReviewPaymentRequest;
import lk.slt.fieldops.payment.dto.SubmitPaymentRequest;
import lk.slt.fieldops.payment.entity.Payment;
import lk.slt.fieldops.payment.entity.PaymentApproval;
import lk.slt.fieldops.payment.repository.PaymentApprovalRepository;
import lk.slt.fieldops.payment.repository.PaymentRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    public PaymentService(PaymentRepository paymentRepo,
                          PaymentApprovalRepository approvalRepo) {
        this.paymentRepo  = paymentRepo;
        this.approvalRepo = approvalRepo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. SUBMIT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Payment submitPayment(SubmitPaymentRequest req,
                                  Long teamLeadId, String teamLeadName) {
        if (paymentRepo.findByJobId(req.getJobId()).isPresent()) {
            throw new RuntimeException(
                "A payment has already been submitted for Job #" + req.getJobId());
        }

        BigDecimal foc        = safe(req.getMaterialsFocTotal());
        BigDecimal chargeable = safe(req.getMaterialsChargeableTotal());
        BigDecimal labour     = safe(req.getLabourCharge());
        BigDecimal total      = chargeable.add(labour);

        Payment p = new Payment();
        p.setPaymentNumber(generatePaymentNumber());
        p.setJobId(req.getJobId());
        p.setBranchId(0L);
        p.setCustomerId(0L);
        p.setTeamLeadId(teamLeadId);
        p.setTeamLeadName(teamLeadName);
        p.setMaterialsFocTotal(foc);
        p.setMaterialsChargeableTotal(chargeable);
        p.setLabourCharge(labour);
        p.setTotalAmount(total);
        p.setCustomerSignatureUrl(req.getCustomerSignatureUrl());
        p.setJobPhotosUrls(req.getJobPhotosUrls());
        p.setMaterialJustification(req.getMaterialJustification());
        p.setWorkSummary(req.getWorkSummary());
        p.setStatus(Payment.PaymentStatus.PENDING);

        return paymentRepo.save(p);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. REVIEW (Admin)
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Payment reviewPayment(Long paymentId, ReviewPaymentRequest req,
                                  Long adminId, String adminName) {
        Payment payment = findOrThrow(paymentId);

        if (payment.getStatus() != Payment.PaymentStatus.PENDING) {
            throw new RuntimeException(
                "Payment already reviewed. Current status: " + payment.getStatus());
        }

        PaymentApproval approval = new PaymentApproval();
        approval.setPaymentId(paymentId);
        approval.setAdminId(adminId);
        approval.setAdminName(adminName);
        approval.setOriginalAmount(payment.getTotalAmount());

        if ("REJECTED".equalsIgnoreCase(req.getDecision())) {
            if (req.getReason() == null || req.getReason().isBlank()) {
                throw new RuntimeException("A reason is required when rejecting a payment.");
            }
            payment.setStatus(Payment.PaymentStatus.REJECTED);
            payment.setRejectionReason(req.getReason());
            approval.setAction(PaymentApproval.Action.REJECTED);
            approval.setReason(req.getReason());

        } else if ("APPROVED".equalsIgnoreCase(req.getDecision())) {
            payment.setApprovedBy(adminId);
            payment.setApprovedByName(adminName);
            payment.setApprovedAt(LocalDateTime.now());

            if (req.getAdjustedAmount() != null &&
                req.getAdjustedAmount().compareTo(payment.getTotalAmount()) != 0) {
                payment.setApprovedAmount(req.getAdjustedAmount());
                approval.setAction(PaymentApproval.Action.ADJUSTED);
                approval.setAdjustedAmount(req.getAdjustedAmount());
            } else {
                payment.setApprovedAmount(payment.getTotalAmount());
                approval.setAction(PaymentApproval.Action.APPROVED);
            }
            approval.setReason(req.getReason());
            payment.setStatus(Payment.PaymentStatus.BILLED);
            payment.setBillReference(generateBillReference());

        } else {
            throw new RuntimeException(
                "Invalid decision: '" + req.getDecision() + "'. Valid: APPROVED or REJECTED");
        }

        approvalRepo.save(approval);
        return paymentRepo.save(payment);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // READ METHODS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Payment getById(Long id) { return findOrThrow(id); }

    @Transactional(readOnly = true)
    public List<Payment> getPendingPayments() {
        return paymentRepo.findByStatusOrderBySubmittedAtAsc(Payment.PaymentStatus.PENDING);
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
