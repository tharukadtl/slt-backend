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
 *   PENDING → ACCEPTED → IN_PROGRESS → COMPLETED
 *                                    → HOLD → (back to PENDING)
 *             CANCELLED (at any point)
 */
@Entity
@Table(name = "jobs")
public class Job {

    public enum JobStatus { PENDING, ACCEPTED, IN_PROGRESS, HOLD, COMPLETED, CANCELLED, REJECTED }
    public enum JobPriority { HIGH, MEDIUM, LOW }

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

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

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
    public LocalDate   getScheduledDate()   { return scheduledDate; }
    public LocalDateTime getAcceptedAt()    { return acceptedAt; }
    public LocalDateTime getStartedAt()     { return startedAt; }
    public LocalDateTime getHoldAt()        { return holdAt; }
    public LocalDateTime getCompletedAt()   { return completedAt; }
    public Boolean     getIsBillable()      { return isBillable; }
    public BigDecimal  getLaborHours()      { return laborHours; }
    public LocalDateTime getCreatedAt()     { return createdAt; }
    public LocalDateTime getUpdatedAt()     { return updatedAt; }
    public String      getCompletionSignature() { return completionSignature; }
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
    public void setScheduledDate(LocalDate v)    { this.scheduledDate   = v; }
    public void setAcceptedAt(LocalDateTime v)   { this.acceptedAt      = v; }
    public void setStartedAt(LocalDateTime v)    { this.startedAt       = v; }
    public void setHoldAt(LocalDateTime v)       { this.holdAt          = v; }
    public void setCompletedAt(LocalDateTime v)  { this.completedAt     = v; }
    public void setIsBillable(Boolean v)         { this.isBillable      = v; }
    public void setLaborHours(BigDecimal v)      { this.laborHours      = v; }
    public void setCompletionSignature(String v) { this.completionSignature = v; }
    public void setCreatedBy(Long v)             { this.createdBy       = v; }
    public void setUpdatedBy(Long v)             { this.updatedBy       = v; }
}
