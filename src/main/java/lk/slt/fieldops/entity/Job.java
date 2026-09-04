package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Job.java — maps to `jobs` table.
 *
 * A Job is created when a Team Lead takes a Fault from their list
 * and assigns it to a Technician.
 *
 * Status flow:
 *   PENDING → ACCEPTED → TRAVELLING → IN_PROGRESS → COMPLETED
 *                                                  → HOLD → (back to PENDING)
 *             CANCELLED (at any point)
 */
@Entity
@Table(name = "jobs")
public class Job {

    public enum JobStatus { PENDING, ACCEPTED, TRAVELLING, IN_PROGRESS, HOLD, COMPLETED, CANCELLED, REJECTED }
    public enum JobPriority { HIGH, MEDIUM, LOW }
    /** SRS 5.3.1.2 — why a job was rejected, distinct from the free-text rejectionReason. */
    public enum RejectionCategory { ISSUE_MISMATCH, MATERIAL_DELAY, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_number", nullable = false, unique = true, length = 30)
    private String jobNumber;

    @Column(name = "fault_id", nullable = false)
    private Long faultId;

    @Column(name = "fault_number", length = 30)
    private String faultNumber;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "team_lead_id", nullable = false)
    private Long teamLeadId;

    @Column(name = "team_lead_name", length = 150)
    private String teamLeadName;

    @Column(name = "technician_id")
    private Long technicianId;

    @Column(name = "technician_name", length = 150)
    private String technicianName;

    @Column(name = "workgroup_id")
    private Long workgroupId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "customer_phone", length = 15)
    private String customerPhone;

    @Column(name = "location_address", length = 500)
    private String locationAddress;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private JobStatus status = JobStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private JobPriority priority = JobPriority.MEDIUM;

    @Column(name = "cause_of_fault", length = 500)
    private String causeOfFault;

    @Column(name = "work_notes", columnDefinition = "TEXT")
    private String workNotes;

    @Column(name = "completion_remarks", columnDefinition = "TEXT")
    private String completionRemarks;

    @Column(name = "hold_reason", length = 500)
    private String holdReason;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "rejected_by_role", length = 30)
    private String rejectedByRole;

    // ── Rejection categorization (SRS 5.3.1.2) ───────────────────────────────
    // A REJECTED job is one of: an on-site issue mismatch (observedIssueType
    // set), a material-delay (linkedMaterialRequest* set), or OTHER (neither).
    // Nullable/optional so pre-existing REJECTED jobs and any caller that
    // doesn't yet send a category keep working unchanged.
    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_category", length = 20)
    private RejectionCategory rejectionCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "observed_issue_type", length = 20)
    private Fault.FaultCategory observedIssueType;

    @Column(name = "linked_material_request_id")
    private Long linkedMaterialRequestId;

    @Column(name = "linked_material_request_number", length = 20)
    private String linkedMaterialRequestNumber;

    // ── EOD pending-task handover (SRS 5.3.1.4) ──────────────────────────────
    // Set when a Technician's shift ends with this job still open (status
    // reset to PENDING, unassigned) — the mandatory per-job explanation, not
    // one blanket reason for every open job. Cleared once anyone accepts the
    // job again, since a stale explanation from a prior shift no longer
    // applies to whoever's tackling it now.
    @Column(name = "eod_handover_reason", length = 500)
    private String eodHandoverReason;

    @Column(name = "eod_handover_at")
    private LocalDateTime eodHandoverAt;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "travel_started_at")
    private LocalDateTime travelStartedAt;

    @Column(name = "arrived_at")
    private LocalDateTime arrivedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "hold_at")
    private LocalDateTime holdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "is_billable", nullable = false)
    private Boolean isBillable = false;

    @Column(name = "labor_hours", precision = 5, scale = 2)
    private BigDecimal laborHours;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completion_signature", columnDefinition = "TEXT")
    private String completionSignature;

    // ── Client-declined-signature review flag (SRS 5.3.1.3, FR-9) ────────────
    // If the client is unavailable or declines to sign at completion, the
    // Technician records a reason instead — the job still reaches COMPLETED
    // (JobService enforces no signature requirement at the entity level), but
    // is flagged here for the Team Lead to review before submitting payment.
    // No separate audit record: the flag + its own timestamp is sufficient,
    // mirroring holdReason/holdAt's reason+timestamp pairing convention.
    @Column(name = "signature_decline_reason", length = 500)
    private String signatureDeclineReason;

    @Column(name = "needs_team_lead_review", nullable = false)
    private Boolean needsTeamLeadReview = false;

    @Column(name = "signature_flagged_at")
    private LocalDateTime signatureFlaggedAt;

    // Comma-separated after-service photo URLs, required to complete a job.
    @Column(name = "completion_photo_urls", columnDefinition = "TEXT")
    private String completionPhotoUrls;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        this.createdAt     = LocalDateTime.now();
        this.updatedAt     = LocalDateTime.now();
        this.scheduledDate = LocalDate.now();
    }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public Job() {}

    // Getters
    public Long        getId()              { return id; }
    public String      getJobNumber()       { return jobNumber; }
    public Long        getFaultId()         { return faultId; }
    public String      getFaultNumber()     { return faultNumber; }
    public Long        getSessionId()       { return sessionId; }
    public Long        getTeamLeadId()      { return teamLeadId; }
    public String      getTeamLeadName()    { return teamLeadName; }
    public Long        getTechnicianId()    { return technicianId; }
    public String      getTechnicianName()  { return technicianName; }
    public Long        getWorkgroupId()     { return workgroupId; }
    public Long        getCustomerId()      { return customerId; }
    public String      getCustomerName()    { return customerName; }
    public String      getCustomerPhone()   { return customerPhone; }
    public String      getLocationAddress() { return locationAddress; }
    public Double      getLatitude()        { return latitude; }
    public Double      getLongitude()       { return longitude; }
    public String      getDescription()     { return description; }
    public JobStatus   getStatus()          { return status; }
    public JobPriority getPriority()        { return priority; }
    public String      getCauseOfFault()    { return causeOfFault; }
    public String      getWorkNotes()       { return workNotes; }
    public String      getCompletionRemarks(){ return completionRemarks; }
    public String      getHoldReason()       { return holdReason; }
    public String      getRejectionReason() { return rejectionReason; }
    public String      getRejectedByRole()  { return rejectedByRole; }
    public RejectionCategory getRejectionCategory()          { return rejectionCategory; }
    public Fault.FaultCategory getObservedIssueType()        { return observedIssueType; }
    public Long        getLinkedMaterialRequestId()          { return linkedMaterialRequestId; }
    public String       getLinkedMaterialRequestNumber()     { return linkedMaterialRequestNumber; }
    public String        getEodHandoverReason()              { return eodHandoverReason; }
    public LocalDateTime getEodHandoverAt()                  { return eodHandoverAt; }
    public LocalDate   getScheduledDate()   { return scheduledDate; }
    public LocalDateTime getAcceptedAt()    { return acceptedAt; }
    public LocalDateTime getTravelStartedAt() { return travelStartedAt; }
    public LocalDateTime getArrivedAt()     { return arrivedAt; }
    public LocalDateTime getStartedAt()     { return startedAt; }
    public LocalDateTime getHoldAt()        { return holdAt; }
    public LocalDateTime getCompletedAt()   { return completedAt; }
    public Boolean     getIsBillable()      { return isBillable; }
    public BigDecimal  getLaborHours()      { return laborHours; }
    public LocalDateTime getCreatedAt()     { return createdAt; }
    public LocalDateTime getUpdatedAt()     { return updatedAt; }
    public String      getCompletionSignature() { return completionSignature; }
    public String        getSignatureDeclineReason() { return signatureDeclineReason; }
    public Boolean        getNeedsTeamLeadReview()   { return needsTeamLeadReview; }
    public LocalDateTime  getSignatureFlaggedAt()     { return signatureFlaggedAt; }
    public String      getCompletionPhotoUrls() { return completionPhotoUrls; }
    public Long        getCreatedBy()       { return createdBy; }
    public Long        getUpdatedBy()       { return updatedBy; }

    // Setters
    public void setId(Long v)                    { this.id              = v; }
    public void setJobNumber(String v)           { this.jobNumber       = v; }
    public void setFaultId(Long v)               { this.faultId         = v; }
    public void setFaultNumber(String v)         { this.faultNumber     = v; }
    public void setSessionId(Long v)             { this.sessionId       = v; }
    public void setTeamLeadId(Long v)            { this.teamLeadId      = v; }
    public void setTeamLeadName(String v)        { this.teamLeadName    = v; }
    public void setTechnicianId(Long v)          { this.technicianId    = v; }
    public void setTechnicianName(String v)      { this.technicianName  = v; }
    public void setWorkgroupId(Long v)           { this.workgroupId     = v; }
    public void setCustomerId(Long v)            { this.customerId      = v; }
    public void setCustomerName(String v)        { this.customerName    = v; }
    public void setCustomerPhone(String v)       { this.customerPhone   = v; }
    public void setLocationAddress(String v)     { this.locationAddress = v; }
    public void setLatitude(Double v)            { this.latitude        = v; }
    public void setLongitude(Double v)           { this.longitude       = v; }
    public void setDescription(String v)         { this.description     = v; }
    public void setStatus(JobStatus v)           { this.status          = v; }
    public void setPriority(JobPriority v)       { this.priority        = v; }
    public void setCauseOfFault(String v)        { this.causeOfFault    = v; }
    public void setWorkNotes(String v)           { this.workNotes       = v; }
    public void setCompletionRemarks(String v)   { this.completionRemarks = v; }
    public void setHoldReason(String v)           { this.holdReason       = v; }
    public void setRejectionReason(String v)      { this.rejectionReason  = v; }
    public void setRejectedByRole(String v)       { this.rejectedByRole   = v; }
    public void setRejectionCategory(RejectionCategory v)      { this.rejectionCategory          = v; }
    public void setObservedIssueType(Fault.FaultCategory v)    { this.observedIssueType          = v; }
    public void setLinkedMaterialRequestId(Long v)              { this.linkedMaterialRequestId    = v; }
    public void setLinkedMaterialRequestNumber(String v)        { this.linkedMaterialRequestNumber = v; }
    public void setEodHandoverReason(String v)                  { this.eodHandoverReason           = v; }
    public void setEodHandoverAt(LocalDateTime v)               { this.eodHandoverAt               = v; }
    public void setScheduledDate(LocalDate v)    { this.scheduledDate   = v; }
    public void setAcceptedAt(LocalDateTime v)   { this.acceptedAt      = v; }
    public void setTravelStartedAt(LocalDateTime v) { this.travelStartedAt = v; }
    public void setArrivedAt(LocalDateTime v)    { this.arrivedAt       = v; }
    public void setStartedAt(LocalDateTime v)    { this.startedAt       = v; }
    public void setHoldAt(LocalDateTime v)       { this.holdAt          = v; }
    public void setCompletedAt(LocalDateTime v)  { this.completedAt     = v; }
    public void setIsBillable(Boolean v)         { this.isBillable      = v; }
    public void setLaborHours(BigDecimal v)      { this.laborHours      = v; }
    public void setCompletionSignature(String v) { this.completionSignature = v; }
    public void setSignatureDeclineReason(String v)      { this.signatureDeclineReason = v; }
    public void setNeedsTeamLeadReview(Boolean v)         { this.needsTeamLeadReview    = v; }
    public void setSignatureFlaggedAt(LocalDateTime v)    { this.signatureFlaggedAt     = v; }
    public void setCompletionPhotoUrls(String v) { this.completionPhotoUrls = v; }
    public void setCreatedBy(Long v)             { this.createdBy       = v; }
    public void setUpdatedBy(Long v)             { this.updatedBy       = v; }
}
