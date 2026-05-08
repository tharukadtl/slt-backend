package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Fault.java — maps to the `faults` table.
 *
 * Status flow:
 *   REPORTED → ASSIGNED → IN_PROGRESS → COMPLETED
 *                                     → HOLD → (back to ASSIGNED)
 *              CANCELLED (Admin or Client at any point)
 */
@Entity
@Table(name = "faults")
public class Fault {

    // ── Enums ────────────────────────────────────────────────────────────────
    public enum FaultStatus {
        REPORTED, ASSIGNED, IN_PROGRESS, HOLD, COMPLETED, CANCELLED
    }

    public enum FaultCategory {
        INTERNET, PHONE, TV, OTHER
    }

    public enum FaultPriority {
        HIGH, MEDIUM, LOW
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fault_number", nullable = false, unique = true, length = 30)
    private String faultNumber;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "customer_phone", length = 15)
    private String customerPhone;

    @Column(name = "subscription_number", length = 100)
    private String subscriptionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FaultCategory category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "location_address", length = 500)
    private String locationAddress;

    @Column(name = "location_city", length = 100)
    private String locationCity;

    @Column(name = "location_district", length = 100)
    private String locationDistrict;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FaultPriority priority = FaultPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FaultStatus status = FaultStatus.REPORTED;

    @Column(name = "assigned_team_lead_id")
    private Long assignedTeamLeadId;

    @Column(name = "assigned_team_lead_name", length = 150)
    private String assignedTeamLeadName;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Column(name = "is_overdue", nullable = false)
    private Boolean isOverdue = false;

    @Column(name = "sla_breached", nullable = false)
    private Boolean slaBreached = false;

    @Column(name = "is_escalated", nullable = false)
    private Boolean isEscalated = false;

    @Column(name = "escalation_reason", length = 500)
    private String escalationReason;

    @Column(name = "hold_reason", length = 500)
    private String holdReason;

    @Column(name = "cause_of_fault", length = 500)
    private String causeOfFault;

    @Column(name = "completion_remarks", columnDefinition = "TEXT")
    private String completionRemarks;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "customer_rating")
    private Integer customerRating;

    @Column(name = "customer_feedback", columnDefinition = "TEXT")
    private String customerFeedback;

    @Column(name = "reopen_count", nullable = false)
    private Integer reopenCount = 0;

    @Column(name = "reported_at", nullable = false, updatable = false)
    private LocalDateTime reportedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        this.createdAt  = LocalDateTime.now();
        this.updatedAt  = LocalDateTime.now();
        this.reportedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── No-arg constructor ────────────────────────────────────────────────────
    public Fault() {}

    // ── Getters ───────────────────────────────────────────────────────────────
    public Long          getId()                   { return id; }
    public String        getFaultNumber()          { return faultNumber; }
    public Long          getBranchId()             { return branchId; }
    public Long          getCustomerId()           { return customerId; }
    public String        getCustomerName()         { return customerName; }
    public String        getCustomerPhone()        { return customerPhone; }
    public String        getSubscriptionNumber()   { return subscriptionNumber; }
    public FaultCategory getCategory()             { return category; }
    public String        getDescription()          { return description; }
    public String        getLocationAddress()      { return locationAddress; }
    public String        getLocationCity()         { return locationCity; }
    public String        getLocationDistrict()     { return locationDistrict; }
    public Double        getLatitude()             { return latitude; }
    public Double        getLongitude()            { return longitude; }
    public FaultPriority getPriority()             { return priority; }
    public FaultStatus   getStatus()               { return status; }
    public Long          getAssignedTeamLeadId()   { return assignedTeamLeadId; }
    public String        getAssignedTeamLeadName() { return assignedTeamLeadName; }
    public LocalDateTime getAssignedAt()           { return assignedAt; }
    public LocalDateTime getDueDate()              { return dueDate; }
    public Boolean       getIsOverdue()            { return isOverdue; }
    public Boolean       getSlaBreached()          { return slaBreached; }
    public Boolean       getIsEscalated()          { return isEscalated; }
    public String        getEscalationReason()     { return escalationReason; }
    public String        getHoldReason()           { return holdReason; }
    public String        getCauseOfFault()         { return causeOfFault; }
    public String        getCompletionRemarks()    { return completionRemarks; }
    public LocalDateTime getStartedAt()            { return startedAt; }
    public LocalDateTime getCompletedAt()          { return completedAt; }
    public Integer       getCustomerRating()       { return customerRating; }
    public String        getCustomerFeedback()     { return customerFeedback; }
    public Integer       getReopenCount()          { return reopenCount; }
    public LocalDateTime getReportedAt()           { return reportedAt; }
    public LocalDateTime getCreatedAt()            { return createdAt; }
    public LocalDateTime getUpdatedAt()            { return updatedAt; }
    public Long          getUpdatedBy()            { return updatedBy; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setId(Long v)                       { this.id                   = v; }
    public void setFaultNumber(String v)            { this.faultNumber          = v; }
    public void setBranchId(Long v)                 { this.branchId             = v; }
    public void setCustomerId(Long v)               { this.customerId           = v; }
    public void setCustomerName(String v)           { this.customerName         = v; }
    public void setCustomerPhone(String v)          { this.customerPhone        = v; }
    public void setSubscriptionNumber(String v)     { this.subscriptionNumber   = v; }
    public void setCategory(FaultCategory v)        { this.category             = v; }
    public void setDescription(String v)            { this.description          = v; }
    public void setLocationAddress(String v)        { this.locationAddress      = v; }
    public void setLocationCity(String v)           { this.locationCity         = v; }
    public void setLocationDistrict(String v)       { this.locationDistrict     = v; }
    public void setLatitude(Double v)               { this.latitude             = v; }
    public void setLongitude(Double v)              { this.longitude            = v; }
    public void setPriority(FaultPriority v)        { this.priority             = v; }
    public void setStatus(FaultStatus v)            { this.status               = v; }
    public void setAssignedTeamLeadId(Long v)       { this.assignedTeamLeadId   = v; }
    public void setAssignedTeamLeadName(String v)   { this.assignedTeamLeadName = v; }
    public void setAssignedAt(LocalDateTime v)      { this.assignedAt           = v; }
    public void setDueDate(LocalDateTime v)         { this.dueDate              = v; }
    public void setIsOverdue(Boolean v)             { this.isOverdue            = v; }
    public void setSlaBreached(Boolean v)           { this.slaBreached          = v; }
    public void setIsEscalated(Boolean v)           { this.isEscalated          = v; }
    public void setEscalationReason(String v)       { this.escalationReason     = v; }
    public void setHoldReason(String v)             { this.holdReason           = v; }
    public void setCauseOfFault(String v)           { this.causeOfFault         = v; }
    public void setCompletionRemarks(String v)      { this.completionRemarks    = v; }
    public void setStartedAt(LocalDateTime v)       { this.startedAt            = v; }
    public void setCompletedAt(LocalDateTime v)     { this.completedAt          = v; }
    public void setCustomerRating(Integer v)        { this.customerRating       = v; }
    public void setCustomerFeedback(String v)       { this.customerFeedback     = v; }
    public void setReopenCount(Integer v)           { this.reopenCount          = v; }
    public void setUpdatedBy(Long v)                { this.updatedBy            = v; }
}
