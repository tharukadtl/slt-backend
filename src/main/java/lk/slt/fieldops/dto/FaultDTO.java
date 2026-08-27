package lk.slt.fieldops.dto;

import java.time.LocalDateTime;

/**
 * FaultDTO — what we send BACK in API responses.
 * Never return the Fault entity directly from the controller.
 */
public class FaultDTO {

    private Long   id;
    private String faultNumber;
    private Long   opmcId;
    private Long   workGroupId;
    private String workGroupName;
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
    private Long   nearestExchangeId;
    private Double nearestExchangeDistanceKm;
    private Long   circuitId;
    private String circuitCode;
    private Long   causeId;
    private String causeCode;
    private String photoUrls;
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

    /**
     * H1b: true when nearestExchangeDistanceKm exceeds the confidence threshold — a real signal
     * that the true nearest Exchange is likely one of the not-yet-geocoded ones, not that this
     * fault is genuinely far from any exchange. Derived at read time from
     * {@link lk.slt.fieldops.shared.GeoUtils#NEAREST_EXCHANGE_LOW_CONFIDENCE_KM} rather than a
     * separate persisted flag, so tightening/loosening the threshold later needs no migration and
     * can never leave a stale flag behind. Null (not false) when there's no match to judge.
     */
    public Boolean getNearestExchangeLowConfidence() {
        if (nearestExchangeId == null || nearestExchangeDistanceKm == null) return null;
        return nearestExchangeDistanceKm > lk.slt.fieldops.shared.GeoUtils.NEAREST_EXCHANGE_LOW_CONFIDENCE_KM;
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
    public Long   getOpmcId()               { return opmcId; }
    public Long   getWorkGroupId()          { return workGroupId; }
    public String getWorkGroupName()        { return workGroupName; }
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
    public Long   getNearestExchangeId()          { return nearestExchangeId; }
    public Double getNearestExchangeDistanceKm()  { return nearestExchangeDistanceKm; }
    public Long   getCircuitId()                  { return circuitId; }
    public String getCircuitCode()                { return circuitCode; }
    public Long   getCauseId()                    { return causeId; }
    public String getCauseCode()                  { return causeCode; }
    public String getPhotoUrls()            { return photoUrls; }
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
    public void setOpmcId(Long v)                   { this.opmcId               = v; }
    public void setWorkGroupId(Long v)              { this.workGroupId          = v; }
    public void setWorkGroupName(String v)          { this.workGroupName        = v; }
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
    public void setNearestExchangeId(Long v)             { this.nearestExchangeId         = v; }
    public void setNearestExchangeDistanceKm(Double v)   { this.nearestExchangeDistanceKm = v; }
    public void setCircuitId(Long v)                     { this.circuitId                 = v; }
    public void setCircuitCode(String v)                 { this.circuitCode               = v; }
    public void setCauseId(Long v)                       { this.causeId                   = v; }
    public void setCauseCode(String v)                   { this.causeCode                 = v; }
    public void setPhotoUrls(String v)              { this.photoUrls            = v; }
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
