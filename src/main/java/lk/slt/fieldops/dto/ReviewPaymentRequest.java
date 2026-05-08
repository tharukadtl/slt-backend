package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * ReviewPaymentRequest — Admin approves, rejects, or adjusts a payment.
 *
 * PATCH /api/payments/{id}/review
 *
 * Approve:  { "decision": "APPROVED" }
 * Reject:   { "decision": "REJECTED", "reason": "Insufficient documentation" }
 * Adjust:   { "decision": "APPROVED", "adjustedAmount": 3500.00, "reason": "FOC only" }
 */
public class ReviewPaymentRequest {

    @NotBlank(message = "Decision is required: APPROVED or REJECTED")
    private String    decision;
    private BigDecimal adjustedAmount;
    private String    reason;

    public ReviewPaymentRequest() {}

    public String     getDecision()       { return decision; }
    public BigDecimal getAdjustedAmount() { return adjustedAmount; }
    public String     getReason()         { return reason; }

    public void setDecision(String v)           { this.decision       = v; }
    public void setAdjustedAmount(BigDecimal v) { this.adjustedAmount = v; }
    public void setReason(String v)             { this.reason         = v; }
}
