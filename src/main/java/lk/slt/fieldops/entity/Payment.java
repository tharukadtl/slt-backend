package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment.java — maps to `payments` table.
 *
 * Workflow:
 *   Team Lead submits  → DRAFT
 *   Admin approves     → FINAL  (generates bill reference e.g. BILL-2026-02-00001)
 *   Admin rejects      → NOT_APPROVED (with reason)
 */
@Entity
@Table(name = "payments")
public class Payment {

    public enum PaymentStatus { DRAFT, FINAL, NOT_APPROVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_number", nullable = false, unique = true, length = 30)
    private String paymentNumber;

    @Column(name = "payment_reference", nullable = false, length = 50)
    private String paymentReference;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "job_number", length = 30)
    private String jobNumber;

    @Column(name = "fault_id")
    private Long faultId;

    @Column(name = "fault_number", length = 30)
    private String faultNumber;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "team_lead_id", nullable = false)
    private Long teamLeadId;

    @Column(name = "team_lead_name", length = 150)
    private String teamLeadName;

    @Column(name = "technician_id")
    private Long technicianId;

    @Column(name = "technician_name", length = 150)
    private String technicianName;

    @Column(name = "materials_foc_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal materialsFocTotal = BigDecimal.ZERO;

    @Column(name = "materials_chargeable_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal materialsChargeableTotal = BigDecimal.ZERO;

    @Column(name = "labour_charge", nullable = false, precision = 12, scale = 2)
    private BigDecimal labourCharge = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "approved_amount", precision = 12, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "customer_signature_url", columnDefinition = "TEXT")
    private String customerSignatureUrl;

    @Column(name = "job_photos_urls", columnDefinition = "TEXT")
    private String jobPhotosUrls;

    @Column(name = "material_justification", columnDefinition = "TEXT")
    private String materialJustification;

    @Column(name = "work_summary", columnDefinition = "TEXT")
    private String workSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentStatus status = PaymentStatus.DRAFT;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_by_name", length = 150)
    private String approvedByName;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "bill_reference", length = 50)
    private String billReference;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt   = LocalDateTime.now();
        this.updatedAt   = LocalDateTime.now();
        this.submittedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public Payment() {}

    public Long          getId()                      { return id; }
    public String        getPaymentNumber()           { return paymentNumber; }
    public String        getPaymentReference()        { return paymentReference; }
    public Long          getJobId()                   { return jobId; }
    public String        getJobNumber()               { return jobNumber; }
    public Long          getFaultId()                 { return faultId; }
    public String        getFaultNumber()             { return faultNumber; }
    public Long          getBranchId()                { return branchId; }
    public Long          getCustomerId()              { return customerId; }
    public String        getCustomerName()            { return customerName; }
    public Long          getTeamLeadId()              { return teamLeadId; }
    public String        getTeamLeadName()            { return teamLeadName; }
    public Long          getTechnicianId()            { return technicianId; }
    public String        getTechnicianName()          { return technicianName; }
    public BigDecimal    getMaterialsFocTotal()       { return materialsFocTotal; }
    public BigDecimal    getMaterialsChargeableTotal(){ return materialsChargeableTotal; }
    public BigDecimal    getLabourCharge()            { return labourCharge; }
    public BigDecimal    getTotalAmount()             { return totalAmount; }
    public BigDecimal    getApprovedAmount()          { return approvedAmount; }
    public String        getCustomerSignatureUrl()    { return customerSignatureUrl; }
    public String        getJobPhotosUrls()           { return jobPhotosUrls; }
    public String        getMaterialJustification()   { return materialJustification; }
    public String        getWorkSummary()             { return workSummary; }
    public PaymentStatus getStatus()                  { return status; }
    public Long          getApprovedBy()              { return approvedBy; }
    public String        getApprovedByName()          { return approvedByName; }
    public LocalDateTime getApprovedAt()              { return approvedAt; }
    public String        getRejectionReason()         { return rejectionReason; }
    public String        getBillReference()           { return billReference; }
    public LocalDateTime getSubmittedAt()             { return submittedAt; }
    public LocalDateTime getCreatedAt()               { return createdAt; }
    public LocalDateTime getUpdatedAt()               { return updatedAt; }

    public void setId(Long v)                           { this.id                      = v; }
    public void setPaymentNumber(String v)              { this.paymentNumber           = v; }
    public void setPaymentReference(String v)           { this.paymentReference        = v; }
    public void setJobId(Long v)                        { this.jobId                   = v; }
    public void setJobNumber(String v)                  { this.jobNumber               = v; }
    public void setFaultId(Long v)                      { this.faultId                 = v; }
    public void setFaultNumber(String v)                { this.faultNumber             = v; }
    public void setBranchId(Long v)                     { this.branchId                = v; }
    public void setCustomerId(Long v)                   { this.customerId              = v; }
    public void setCustomerName(String v)               { this.customerName            = v; }
    public void setTeamLeadId(Long v)                   { this.teamLeadId              = v; }
    public void setTeamLeadName(String v)               { this.teamLeadName            = v; }
    public void setTechnicianId(Long v)                 { this.technicianId            = v; }
    public void setTechnicianName(String v)             { this.technicianName          = v; }
    public void setMaterialsFocTotal(BigDecimal v)      { this.materialsFocTotal       = v; }
    public void setMaterialsChargeableTotal(BigDecimal v){ this.materialsChargeableTotal = v; }
    public void setLabourCharge(BigDecimal v)           { this.labourCharge            = v; }
    public void setTotalAmount(BigDecimal v)            { this.totalAmount             = v; }
    public void setApprovedAmount(BigDecimal v)         { this.approvedAmount          = v; }
    public void setCustomerSignatureUrl(String v)       { this.customerSignatureUrl    = v; }
    public void setJobPhotosUrls(String v)              { this.jobPhotosUrls           = v; }
    public void setMaterialJustification(String v)      { this.materialJustification   = v; }
    public void setWorkSummary(String v)                { this.workSummary             = v; }
    public void setStatus(PaymentStatus v)              { this.status                  = v; }
    public void setApprovedBy(Long v)                   { this.approvedBy              = v; }
    public void setApprovedByName(String v)             { this.approvedByName          = v; }
    public void setApprovedAt(LocalDateTime v)          { this.approvedAt              = v; }
    public void setRejectionReason(String v)            { this.rejectionReason         = v; }
    public void setBillReference(String v)              { this.billReference           = v; }
}
