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
        INTERNET, PHONE, FIBER, TV, OTHER
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

    @Column(name = "opmc_id", nullable = false)
    private Long opmcId;

    // Stage D (SRS 5.5.1): the Work Group a fault is currently routed to. Admin
    // assigns to a Work Group, not a specific person — the Work Group's Team Lead
    // then self-assigns or dispatches to a Technician. Null means unassigned
    // (in the Admin queue), including after a transfer-to-Admin.
    @Column(name = "work_group_id")
    private Long workGroupId;

    @Column(name = "work_group_name", length = 150)
    private String workGroupName;

    // Stage C: the specific Exchange+CAB+DP attach point this fault is against.
    // Nullable — existing faults predate this concept and won't have one.
    @Column(name = "circuit_id")
    private Long circuitId;

    // H1c: set together with circuitId by FaultService.attachCircuit — denormalized display
    // string, same pattern as workGroupId/workGroupName above, so FaultDTO can show what's
    // attached without a join/lookup on every fault read.
    @Column(name = "circuit_code", length = 30)
    private String circuitCode;

    // H1b: auto-derived at creation, only when circuitId is absent and GPS is present —
    // the nearest Exchange among those with geocoded coordinates (see
    // fieldops/scripts/geocode_master_data.py; not every Exchange has coordinates yet).
    // nearestExchangeDistanceKm is the actual computed distance, kept alongside the id rather
    // than a separate persisted "low confidence" boolean, so the confidence threshold can be
    // changed later (or tightened per-caller) without a schema migration or stale flag —
    // low-confidence is derived from this distance at read time (see FaultDTO.getNearestExchangeLowConfidence
    // and GeoUtils.NEAREST_EXCHANGE_LOW_CONFIDENCE_KM).
    @Column(name = "nearest_exchange_id")
    private Long nearestExchangeId;

    @Column(name = "nearest_exchange_distance_km")
    private Double nearestExchangeDistanceKm;

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

    // Comma-separated before-service evidence photo URLs (max 5, JPEG/PNG).
    @Column(name = "photo_urls", columnDefinition = "TEXT")
    private String photoUrls;

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

    // Cause/Material hierarchy import (2026-08-21): additive nullable FK into the new
    // cause_of_fault reference table, same pattern as circuitId/nearestExchangeId above —
    // sits alongside the existing free-text causeOfFault field above, does not replace it.
    @Column(name = "cause_id")
    private Long causeId;

    // Stage 2 (QA_Compliance_Consolidated_Report.md) — denormalized display value, same pattern
    // as circuitCode alongside circuitId (H1c). Written by FaultService.attachCause; avoids a
    // join against cause_of_fault on every fault list/detail read.
    @Column(name = "cause_code", length = 10)
    private String causeCode;

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
    public Long          getOpmcId()               { return opmcId; }
    public Long          getWorkGroupId()          { return workGroupId; }
    public String        getWorkGroupName()        { return workGroupName; }
    public Long          getCircuitId()            { return circuitId; }
    public String        getCircuitCode()          { return circuitCode; }
    public Long          getNearestExchangeId()          { return nearestExchangeId; }
    public Double         getNearestExchangeDistanceKm() { return nearestExchangeDistanceKm; }
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
    public String        getPhotoUrls()            { return photoUrls; }
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
    public Long          getCauseId()              { return causeId; }
    public String        getCauseCode()            { return causeCode; }
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
    public void setOpmcId(Long v)                   { this.opmcId               = v; }
    public void setWorkGroupId(Long v)              { this.workGroupId          = v; }
    public void setWorkGroupName(String v)          { this.workGroupName        = v; }
    public void setCircuitId(Long v)                { this.circuitId            = v; }
    public void setCircuitCode(String v)            { this.circuitCode          = v; }
    public void setNearestExchangeId(Long v)              { this.nearestExchangeId          = v; }
    public void setNearestExchangeDistanceKm(Double v)    { this.nearestExchangeDistanceKm  = v; }
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
    public void setPhotoUrls(String v)              { this.photoUrls            = v; }
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
    public void setCauseId(Long v)                  { this.causeId              = v; }
    public void setCauseCode(String v)              { this.causeCode            = v; }
    public void setCompletionRemarks(String v)      { this.completionRemarks    = v; }
    public void setStartedAt(LocalDateTime v)       { this.startedAt            = v; }
    public void setCompletedAt(LocalDateTime v)     { this.completedAt          = v; }
    public void setCustomerRating(Integer v)        { this.customerRating       = v; }
    public void setCustomerFeedback(String v)       { this.customerFeedback     = v; }
    public void setReopenCount(Integer v)           { this.reopenCount          = v; }
    public void setUpdatedBy(Long v)                { this.updatedBy            = v; }
}
