package lk.slt.fieldops.dto;

import lk.slt.fieldops.entity.Payment;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ClientBillDTO — shapes a Payment for the mobile client's billing screen.
 * Field names match the client app's Bill TypeScript interface exactly.
 */
public class ClientBillDTO {

    private String        id;
    private String        issueId;
    private String        issueTitle;
    private String        technicianName;
    private LocalDateTime completedAt;
    private String        status;
    private BigDecimal    materialsFOC;
    private BigDecimal    materialsChargeable;
    private BigDecimal    laborCharges;
    private BigDecimal    totalFOC;
    private BigDecimal    totalChargeable;
    private BigDecimal    grandTotal;
    private String        billReference;

    public static ClientBillDTO from(Payment p) {
        ClientBillDTO d = new ClientBillDTO();
        d.id                  = p.getId().toString();
        d.issueId             = p.getJobId() != null ? p.getJobId().toString() : null;
        d.issueTitle          = p.getWorkSummary() != null ? p.getWorkSummary()
                                : (p.getFaultNumber() != null ? "Fault " + p.getFaultNumber() : "Job #" + p.getJobId());
        d.technicianName      = p.getTechnicianName();
        d.completedAt         = p.getApprovedAt() != null ? p.getApprovedAt() : p.getSubmittedAt();
        d.status              = mapStatus(p.getStatus());
        d.materialsFOC        = safe(p.getMaterialsFocTotal());
        d.materialsChargeable = safe(p.getMaterialsChargeableTotal());
        d.laborCharges        = safe(p.getLabourCharge());
        d.totalFOC            = d.materialsFOC;
        d.totalChargeable     = d.materialsChargeable.add(d.laborCharges);
        d.grandTotal          = p.getApprovedAmount() != null ? p.getApprovedAmount() : p.getTotalAmount();
        d.billReference       = p.getBillReference();
        return d;
    }

    private static String mapStatus(Payment.PaymentStatus s) {
        if (s == null) return "PENDING";
        return switch (s) {
            case FINAL        -> "APPROVED";
            case NOT_APPROVED -> "REJECTED";
            default           -> "PENDING";
        };
    }

    private static BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    public String        getId()                  { return id; }
    public String        getIssueId()             { return issueId; }
    public String        getIssueTitle()          { return issueTitle; }
    public String        getTechnicianName()      { return technicianName; }
    public LocalDateTime getCompletedAt()         { return completedAt; }
    public String        getStatus()              { return status; }
    public BigDecimal    getMaterialsFOC()        { return materialsFOC; }
    public BigDecimal    getMaterialsChargeable() { return materialsChargeable; }
    public BigDecimal    getLaborCharges()        { return laborCharges; }
    public BigDecimal    getTotalFOC()            { return totalFOC; }
    public BigDecimal    getTotalChargeable()     { return totalChargeable; }
    public BigDecimal    getGrandTotal()          { return grandTotal; }
    public String        getBillReference()       { return billReference; }
}
