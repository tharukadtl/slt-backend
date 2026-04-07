package lk.slt.fieldops.job.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * UpdateJobRequest — Technician updates job status.
 *
 * PATCH /api/jobs/{id}/status
 *
 * Accept:     { "newStatus": "ACCEPTED" }
 * Start:      { "newStatus": "IN_PROGRESS" }
 * Hold:       { "newStatus": "HOLD", "reason": "Missing cable, will return tomorrow" }
 * Complete:   { "newStatus": "COMPLETED", "causeOfFault": "Damaged cable",
 *               "completionRemarks": "Replaced 15m cable, tested OK" }
 */
public class UpdateJobRequest {

    @NotBlank(message = "New status is required")
    private String newStatus;

    private String reason;             // required when status = HOLD
    private String causeOfFault;       // what caused the fault
    private String completionRemarks;  // summary of work done
    private String workNotes;          // ongoing notes

    public UpdateJobRequest() {}

    public String getNewStatus()         { return newStatus; }
    public String getReason()            { return reason; }
    public String getCauseOfFault()      { return causeOfFault; }
    public String getCompletionRemarks() { return completionRemarks; }
    public String getWorkNotes()         { return workNotes; }

    public void setNewStatus(String v)         { this.newStatus         = v; }
    public void setReason(String v)            { this.reason            = v; }
    public void setCauseOfFault(String v)      { this.causeOfFault      = v; }
    public void setCompletionRemarks(String v) { this.completionRemarks = v; }
    public void setWorkNotes(String v)         { this.workNotes         = v; }
}
