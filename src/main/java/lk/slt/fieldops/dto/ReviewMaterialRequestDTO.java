package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * ReviewMaterialRequestDTO — Admin approves or rejects a stock request.
 * PATCH /api/inventory/requests/{id}/review
 * Approve: { "decision": "APPROVED" }
 * Reject:  { "decision": "REJECTED", "rejectionReason": "Stock not available" }
 */
public class ReviewMaterialRequestDTO {

    @NotBlank(message = "Decision is required: APPROVED or REJECTED")
    private String decision;         // APPROVED or REJECTED

    private String rejectionReason;  // required if REJECTED

    public ReviewMaterialRequestDTO() {}

    public String getDecision()        { return decision; }
    public String getRejectionReason() { return rejectionReason; }

    public void setDecision(String v)        { this.decision        = v; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }
}
