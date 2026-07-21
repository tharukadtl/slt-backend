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
 *
 * Dispute / amendment cycle (FR-31 / FR-32, SRS 5.2.3 & 5.5.2.1):
 *   Client reports issue on an approved bill  → DISPUTED
 *   Admin amends line items + justification    → PENDING_CLIENT_REVIEW
 *   Client can dispute the amended bill again  → DISPUTED  (cycle repeats until accepted)
 */
@Entity
@Table(name = "payments")
public class Payment {

    public enum PaymentStatus {
        DRAFT, FINAL, NOT_APPROVED, CLARIFICATION_REQUESTED, DISPUTED, PENDING_CLIENT_REVIEW, CLIENT_ACCEPTED
    }

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

    @Column(name = "labour_start_time")
    private LocalDateTime labourStartTime;

    @Column(name = "labour_end_time")
    private LocalDateTime labourEndTime;

    @Column(name = "hourly_rate", precision = 10, scale = 2)
    private BigDecimal hourlyRate;

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
    @Column(nullable = false, length = 30)
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

    // ─── Dispute (FR-31, SRS 5.2.3) — set when the Client reports an issue ───
    @Column(name = "dispute_category", length = 100)
    private String disputeCategory;

    @Column(name = "dispute_description", columnDefinition = "TEXT")
    private String disputeDescription;

    @Column(name = "dispute_photo_url", columnDefinition = "TEXT")
    private String disputePhotoUrl;

    @Column(name = "disputed_at")
    private LocalDateTime disputedAt;

    // ─── Amendment (FR-32, SRS 5.5.2.1) — set when the Admin amends & resends ───
    @Column(name = "amendment_justification", columnDefinition = "TEXT")
    private String amendmentJustification;

    @Column(name = "amended_by")
    private Long amendedBy;

    @Column(name = "amended_by_name", length = 150)
    private String amendedByName;

    @Column(name = "amended_at")
    private LocalDateTime amendedAt;

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
    public LocalDateTime getLabourStartTime()         { return labourStartTime; }
    public LocalDateTime getLabourEndTime()           { return labourEndTime; }
    public BigDecimal    getHourlyRate()              { return hourlyRate; }
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
    public String        getDisputeCategory()         { return disputeCategory; }
    public String        getDisputeDescription()      { return disputeDescription; }
    public String        getDisputePhotoUrl()         { return disputePhotoUrl; }
    public LocalDateTime getDisputedAt()              { return disputedAt; }
    public String        getAmendmentJustification()  { return amendmentJustification; }
    public Long          getAmendedBy()               { return amendedBy; }
    public String        getAmendedByName()           { return amendedByName; }
    public LocalDateTime getAmendedAt()               { return amendedAt; }
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
    public void setLabourStartTime(LocalDateTime v)     { this.labourStartTime         = v; }
    public void setLabourEndTime(LocalDateTime v)       { this.labourEndTime           = v; }
    public void setHourlyRate(BigDecimal v)             { this.hourlyRate              = v; }
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
    public void setDisputeCategory(String v)            { this.disputeCategory         = v; }
    public void setDisputeDescription(String v)         { this.disputeDescription      = v; }
    public void setDisputePhotoUrl(String v)            { this.disputePhotoUrl         = v; }
    public void setDisputedAt(LocalDateTime v)          { this.disputedAt              = v; }
    public void setAmendmentJustification(String v)     { this.amendmentJustification  = v; }
    public void setAmendedBy(Long v)                    { this.amendedBy               = v; }
    public void setAmendedByName(String v)              { this.amendedByName           = v; }
    public void setAmendedAt(LocalDateTime v)           { this.amendedAt               = v; }
}
