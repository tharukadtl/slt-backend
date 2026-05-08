package lk.slt.fieldops.dto;

import java.time.LocalDateTime;

/**
 * FaultDTO — what we send BACK in API responses.
 * Never return the Fault entity directly from the controller.
 */
public class FaultDTO {

    private Long   id;
    private String faultNumber;
    private Long   branchId;
    private Long   customerId;
    private String customerName;
    private String customerPhone;
    private String subscriptionNumber;
    private String category;
    private String description;
    private String locationAddress;
    private String locationCity;
    private String locationDistrict;
    private Double latitude;
    private Double longitude;
    private String priority;
    private String status;
    private Long   assignedTeamLeadId;
    private String assignedTeamLeadName;
    private LocalDateTime assignedAt;
    private LocalDateTime dueDate;
    private Boolean isOverdue;
    private Boolean slaBreached;
    private String  holdReason;
    private String  causeOfFault;
    private String  completionRemarks;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer customerRating;
    private String  customerFeedback;
    private LocalDateTime reportedAt;
    private LocalDateTime updatedAt;
    private long    ageHours;   // how long since reported

    public FaultDTO() {}

    // ── Admin portal compatibility aliases ────────────────────────────────────

    /** Maps to locationAddress — used by admin portal as f.address */
    public String getAddress() { return locationAddress; }

    /** Maps to reportedAt as ISO string — used by admin portal as f.createdAt */
    public String getCreatedAt() {
        return reportedAt != null ? reportedAt.toString() : null;
    }

    /** Maps REPORTED → OPEN so admin portal status chips work correctly */
    public String getStatusDisplay() {
        return "REPORTED".equals(status) ? "OPEN" : status;
    }

    /** Nested reporter object for admin portal: f.reportedBy.fullName / .phone */
    public ReportedBy getReportedBy() {
        if (customerName == null && customerPhone == null) return null;
        return new ReportedBy(customerName, customerPhone);
    }

    /** Nested assignee object for admin portal: f.assignedTo.id / .fullName */
    public AssignedTo getAssignedTo() {
        if (assignedTeamLeadId == null) return null;
        return new AssignedTo(assignedTeamLeadId, assignedTeamLeadName);
    }

    public static class ReportedBy {
        private final String fullName;
        private final String phone;
        ReportedBy(String fullName, String phone) { this.fullName = fullName; this.phone = phone; }
        public String getFullName() { return fullName; }
        public String getPhone()    { return phone; }
    }

    public static class AssignedTo {
        private final Long   id;
        private final String fullName;
        AssignedTo(Long id, String fullName) { this.id = id; this.fullName = fullName; }
        public Long   getId()       { return id; }
        public String getFullName() { return fullName; }
    }

    // Getters
    public Long   getId()                   { return id; }
    public String getFaultNumber()          { return faultNumber; }
    public Long   getBranchId()             { return branchId; }
    public Long   getCustomerId()           { return customerId; }
    public String getCustomerName()         { return customerName; }
    public String getCustomerPhone()        { return customerPhone; }
    public String getSubscriptionNumber()   { return subscriptionNumber; }
    public String getCategory()             { return category; }
    public String getDescription()          { return description; }
    public String getLocationAddress()      { return locationAddress; }
    public String getLocationCity()         { return locationCity; }
    public String getLocationDistrict()     { return locationDistrict; }
    public Double getLatitude()             { return latitude; }
    public Double getLongitude()            { return longitude; }
    public String getPriority()             { return priority; }
    public String getStatus()               { return status; }
    public Long   getAssignedTeamLeadId()   { return assignedTeamLeadId; }
    public String getAssignedTeamLeadName() { return assignedTeamLeadName; }
    public LocalDateTime getAssignedAt()    { return assignedAt; }
    public LocalDateTime getDueDate()       { return dueDate; }
    public Boolean getIsOverdue()           { return isOverdue; }
    public Boolean getSlaBreached()         { return slaBreached; }
    public String  getHoldReason()          { return holdReason; }
    public String  getCauseOfFault()        { return causeOfFault; }
    public String  getCompletionRemarks()   { return completionRemarks; }
    public LocalDateTime getStartedAt()     { return startedAt; }
    public LocalDateTime getCompletedAt()   { return completedAt; }
    public Integer getCustomerRating()      { return customerRating; }
    public String  getCustomerFeedback()    { return customerFeedback; }
    public LocalDateTime getReportedAt()    { return reportedAt; }
    public LocalDateTime getUpdatedAt()     { return updatedAt; }
    public long    getAgeHours()            { return ageHours; }

    // Setters
    public void setId(Long v)                       { this.id                   = v; }
    public void setFaultNumber(String v)            { this.faultNumber          = v; }
    public void setBranchId(Long v)                 { this.branchId             = v; }
    public void setCustomerId(Long v)               { this.customerId           = v; }
    public void setCustomerName(String v)           { this.customerName         = v; }
    public void setCustomerPhone(String v)          { this.customerPhone        = v; }
    public void setSubscriptionNumber(String v)     { this.subscriptionNumber   = v; }
    public void setCategory(String v)               { this.category             = v; }
    public void setDescription(String v)            { this.description          = v; }
    public void setLocationAddress(String v)        { this.locationAddress      = v; }
    public void setLocationCity(String v)           { this.locationCity         = v; }
    public void setLocationDistrict(String v)       { this.locationDistrict     = v; }
    public void setLatitude(Double v)               { this.latitude             = v; }
    public void setLongitude(Double v)              { this.longitude            = v; }
    public void setPriority(String v)               { this.priority             = v; }
    public void setStatus(String v)                 { this.status               = v; }
    public void setAssignedTeamLeadId(Long v)       { this.assignedTeamLeadId   = v; }
    public void setAssignedTeamLeadName(String v)   { this.assignedTeamLeadName = v; }
    public void setAssignedAt(LocalDateTime v)      { this.assignedAt           = v; }
    public void setDueDate(LocalDateTime v)         { this.dueDate              = v; }
    public void setIsOverdue(Boolean v)             { this.isOverdue            = v; }
    public void setSlaBreached(Boolean v)           { this.slaBreached          = v; }
    public void setHoldReason(String v)             { this.holdReason           = v; }
    public void setCauseOfFault(String v)           { this.causeOfFault         = v; }
    public void setCompletionRemarks(String v)      { this.completionRemarks    = v; }
    public void setStartedAt(LocalDateTime v)       { this.startedAt            = v; }
    public void setCompletedAt(LocalDateTime v)     { this.completedAt          = v; }
    public void setCustomerRating(Integer v)        { this.customerRating       = v; }
    public void setCustomerFeedback(String v)       { this.customerFeedback     = v; }
    public void setReportedAt(LocalDateTime v)      { this.reportedAt           = v; }
    public void setUpdatedAt(LocalDateTime v)       { this.updatedAt            = v; }
    public void setAgeHours(long v)                 { this.ageHours             = v; }
}
