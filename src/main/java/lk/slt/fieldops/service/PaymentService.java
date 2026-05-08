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
    private final JobRepository             jobRepo;
    private final FaultRepository           faultRepo;

    public PaymentService(PaymentRepository paymentRepo,
                          PaymentApprovalRepository approvalRepo,
                          JobRepository jobRepo,
                          FaultRepository faultRepo) {
        this.paymentRepo  = paymentRepo;
        this.approvalRepo = approvalRepo;
        this.jobRepo      = jobRepo;
        this.faultRepo    = faultRepo;
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

        Job job = jobRepo.findById(req.getJobId())
            .orElseThrow(() -> new ResourceNotFoundException("Job", req.getJobId()));
        Fault fault = faultRepo.findById(job.getFaultId())
            .orElseThrow(() -> new ResourceNotFoundException("Fault", job.getFaultId()));

        BigDecimal foc        = safe(req.getMaterialsFocTotal());
        BigDecimal chargeable = safe(req.getMaterialsChargeableTotal());
        BigDecimal labour     = safe(req.getLabourCharge());
        BigDecimal total      = chargeable.add(labour);

        Payment p = new Payment();
        String payNum = generatePaymentNumber();
        p.setPaymentNumber(payNum);
        p.setPaymentReference(payNum);
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
        p.setTotalAmount(total);
        p.setCustomerSignatureUrl(req.getCustomerSignatureUrl());
        p.setJobPhotosUrls(req.getJobPhotosUrls());
        p.setMaterialJustification(req.getMaterialJustification());
        p.setWorkSummary(req.getWorkSummary());
        p.setStatus(Payment.PaymentStatus.DRAFT);

        return paymentRepo.save(p);
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
        approval.setOriginalAmount(payment.getTotalAmount());

        if ("REJECTED".equalsIgnoreCase(req.getDecision())) {
            if (req.getReason() == null || req.getReason().isBlank()) {
                throw new RuntimeException("A reason is required when rejecting a payment.");
            }
            payment.setStatus(Payment.PaymentStatus.NOT_APPROVED);
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
            payment.setStatus(Payment.PaymentStatus.FINAL);
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
