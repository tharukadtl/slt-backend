package lk.slt.fieldops.inventory.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MaterialRequest.java — maps to `material_requests` table.
 *
 * Workflow:
 *   Team Lead submits request (PENDING)
 *   Admin approves (APPROVED) → stock deducted automatically
 *   Admin rejects (REJECTED)
 *   On approval, stock is deducted and a STOCK_OUT transaction is logged.
 */
@Entity
@Table(name = "material_requests")
public class MaterialRequest {

    public enum RequestStatus { PENDING, APPROVED, REJECTED, FULFILLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_by_name", length = 150)
    private String requestedByName;

    @Column(name = "quantity_requested", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityRequested;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "job_id")
    private Long jobId;               // optional: linked to a specific job

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_by_name", length = 150)
    private String reviewedByName;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public MaterialRequest() {}

    // Getters
    public Long          getId()               { return id; }
    public Long          getMaterialId()       { return materialId; }
    public String        getMaterialName()     { return materialName; }
    public Long          getBranchId()         { return branchId; }
    public Long          getRequestedBy()      { return requestedBy; }
    public String        getRequestedByName()  { return requestedByName; }
    public BigDecimal    getQuantityRequested(){ return quantityRequested; }
    public String        getReason()           { return reason; }
    public Long          getJobId()            { return jobId; }
    public RequestStatus getStatus()           { return status; }
    public Long          getReviewedBy()       { return reviewedBy; }
    public String        getReviewedByName()   { return reviewedByName; }
    public LocalDateTime getReviewedAt()       { return reviewedAt; }
    public String        getRejectionReason()  { return rejectionReason; }
    public LocalDateTime getCreatedAt()        { return createdAt; }
    public LocalDateTime getUpdatedAt()        { return updatedAt; }

    // Setters
    public void setId(Long v)                     { this.id               = v; }
    public void setMaterialId(Long v)             { this.materialId       = v; }
    public void setMaterialName(String v)         { this.materialName     = v; }
    public void setBranchId(Long v)               { this.branchId         = v; }
    public void setRequestedBy(Long v)            { this.requestedBy      = v; }
    public void setRequestedByName(String v)      { this.requestedByName  = v; }
    public void setQuantityRequested(BigDecimal v){ this.quantityRequested = v; }
    public void setReason(String v)               { this.reason           = v; }
    public void setJobId(Long v)                  { this.jobId            = v; }
    public void setStatus(RequestStatus v)        { this.status           = v; }
    public void setReviewedBy(Long v)             { this.reviewedBy       = v; }
    public void setReviewedByName(String v)       { this.reviewedByName   = v; }
    public void setReviewedAt(LocalDateTime v)    { this.reviewedAt       = v; }
    public void setRejectionReason(String v)      { this.rejectionReason  = v; }
}
