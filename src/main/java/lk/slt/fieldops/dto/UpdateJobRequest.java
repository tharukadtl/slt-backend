package lk.slt.fieldops.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;

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
 * Complete, client declined/unavailable to sign (SRS 5.3.1.3, FR-9):
 *             { "newStatus": "COMPLETED", "causeOfFault": "...", "completionRemarks": "...",
 *               "signatureDeclineReason": "Client unavailable, left before work finished" }
 *             — routed through this same completion request rather than the separate
 *               /signature endpoint, which is simply never called on this path.
 *
 * Reject (SRS 5.3.1.2) — three shapes, all still require "reason":
 *   Issue mismatch:  { "newStatus": "REJECTED", "reason": "...",
 *                       "rejectionCategory": "ISSUE_MISMATCH", "observedIssueType": "PHONE" }
 *   Material delay:  { "newStatus": "REJECTED", "reason": "...",
 *                       "rejectionCategory": "MATERIAL_DELAY", "linkedMaterialRequestId": 42 }
 *   Other/legacy:    { "newStatus": "REJECTED", "reason": "..." }  (rejectionCategory omitted)
 */
public class UpdateJobRequest {

    @NotBlank(message = "New status is required")
    @JsonAlias("status")
    private String newStatus;

    private String reason;               // required when status = HOLD or REJECTED
    private String causeOfFault;         // what caused the fault
    private String completionRemarks;    // summary of work done
    private String workNotes;            // ongoing notes
    private String completionPhotoUrls;  // required when status = COMPLETED (comma-separated)
    private String signatureDeclineReason; // optional; only when status = COMPLETED and the
                                            // client was unavailable/declined to sign (FR-9)

    // Optional — only meaningful when newStatus = REJECTED. Omitted entirely
    // by any caller not yet updated, which keeps today's plain-reason behavior.
    private Job.RejectionCategory rejectionCategory;
    private Fault.FaultCategory   observedIssueType;      // required when rejectionCategory = ISSUE_MISMATCH
    private Long                  linkedMaterialRequestId; // required when rejectionCategory = MATERIAL_DELAY and one is available

    public UpdateJobRequest() {}

    public String getNewStatus()           { return newStatus; }
    public String getReason()              { return reason; }
    public String getCauseOfFault()        { return causeOfFault; }
    public String getCompletionRemarks()   { return completionRemarks; }
    public String getWorkNotes()           { return workNotes; }
    public String getCompletionPhotoUrls() { return completionPhotoUrls; }
    public String getSignatureDeclineReason() { return signatureDeclineReason; }
    public Job.RejectionCategory getRejectionCategory()      { return rejectionCategory; }
    public Fault.FaultCategory   getObservedIssueType()      { return observedIssueType; }
    public Long                  getLinkedMaterialRequestId(){ return linkedMaterialRequestId; }

    public void setNewStatus(String v)             { this.newStatus             = v; }
    public void setReason(String v)                { this.reason                = v; }
    public void setCauseOfFault(String v)          { this.causeOfFault          = v; }
    public void setCompletionRemarks(String v)     { this.completionRemarks     = v; }
    public void setWorkNotes(String v)             { this.workNotes             = v; }
    public void setCompletionPhotoUrls(String v)   { this.completionPhotoUrls   = v; }
    public void setSignatureDeclineReason(String v) { this.signatureDeclineReason = v; }
    public void setRejectionCategory(Job.RejectionCategory v)   { this.rejectionCategory      = v; }
    public void setObservedIssueType(Fault.FaultCategory v)     { this.observedIssueType      = v; }
    public void setLinkedMaterialRequestId(Long v)               { this.linkedMaterialRequestId = v; }
}
