package lk.slt.fieldops.fault.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * UpdateFaultRequest — Technician updates job status (IN_PROGRESS, HOLD, COMPLETED).
 * PATCH /api/faults/{id}/status
 *
 * Example body for HOLD:
 * { "newStatus": "HOLD", "reason": "Missing cable, will return tomorrow" }
 *
 * Example body for COMPLETED:
 * { "newStatus": "COMPLETED", "causeOfFault": "Damaged cable",
 *   "completionRemarks": "Replaced 10m cable, tested OK" }
 */
public class UpdateFaultRequest {

    @NotBlank(message = "New status is required")
    private String newStatus;      // IN_PROGRESS / HOLD / COMPLETED / CANCELLED

    private String reason;         // Required when HOLD or CANCELLED
    private String causeOfFault;   // What caused the fault
    private String completionRemarks; // Summary of work done

    public UpdateFaultRequest() {}

    public String getNewStatus()          { return newStatus; }
    public String getReason()             { return reason; }
    public String getCauseOfFault()       { return causeOfFault; }
    public String getCompletionRemarks()  { return completionRemarks; }

    public void setNewStatus(String v)         { this.newStatus         = v; }
    public void setReason(String v)            { this.reason            = v; }
    public void setCauseOfFault(String v)      { this.causeOfFault      = v; }
    public void setCompletionRemarks(String v) { this.completionRemarks = v; }
}
