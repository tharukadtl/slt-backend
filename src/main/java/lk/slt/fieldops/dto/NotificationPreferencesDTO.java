package lk.slt.fieldops.dto;

/**
 * NotificationPreferencesDTO — shared shape for both the profile-update
 * request and the UserDTO response, so what a client sends is exactly what
 * it gets back. Field names match SLTMobileApp's NotificationPreferences
 * type verbatim (statusUpdates/technicianAssigned/jobCompleted/billing/
 * promotions) — no remapping on either side.
 */
public class NotificationPreferencesDTO {

    private boolean statusUpdates = true;
    private boolean technicianAssigned = true;
    private boolean jobCompleted = true;
    private boolean billing = true;
    private boolean promotions = false;

    public NotificationPreferencesDTO() {}

    public boolean isStatusUpdates()        { return statusUpdates; }
    public boolean isTechnicianAssigned()   { return technicianAssigned; }
    public boolean isJobCompleted()         { return jobCompleted; }
    public boolean isBilling()              { return billing; }
    public boolean isPromotions()           { return promotions; }

    public void setStatusUpdates(boolean v)      { this.statusUpdates      = v; }
    public void setTechnicianAssigned(boolean v) { this.technicianAssigned = v; }
    public void setJobCompleted(boolean v)       { this.jobCompleted       = v; }
    public void setBilling(boolean v)            { this.billing            = v; }
    public void setPromotions(boolean v)         { this.promotions         = v; }
}
