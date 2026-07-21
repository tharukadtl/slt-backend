package lk.slt.fieldops.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * AmendBillRequest — Admin amends a disputed bill and resends it (FR-32, SRS 5.5.2.1).
 *
 * PATCH /api/payments/{id}/amend
 * {
 *   "materialsFocTotal":        0.00,       // optional — omit to keep current value
 *   "materialsChargeableTotal": 3500.00,    // optional
 *   "labourCharge":             1000.00,    // optional
 *   "justification":            "Removed the duplicated router line item the client flagged."
 * }
 *
 * Per SRS 5.5.2.1 the Admin adjusts line items / amounts / classification with a
 * MANDATORY justification note. The Payment models its "line items" as the three
 * component totals (FOC, chargeable, labour); any field omitted keeps its current
 * value. The new total is recomputed the same way submitPayment computes it
 * (chargeable + labour; FOC is free-of-charge and excluded from the total).
 */
public class AmendBillRequest {

    @DecimalMin(value = "0.0", message = "Materials FOC total must be >= 0")
    private BigDecimal materialsFocTotal;

    @DecimalMin(value = "0.0", message = "Materials chargeable total must be >= 0")
    private BigDecimal materialsChargeableTotal;

    @DecimalMin(value = "0.0", message = "Labour charge must be >= 0")
    private BigDecimal labourCharge;

    @NotBlank(message = "A justification is required when amending a bill")
    private String justification;

    public AmendBillRequest() {}

    public BigDecimal getMaterialsFocTotal()        { return materialsFocTotal; }
    public BigDecimal getMaterialsChargeableTotal() { return materialsChargeableTotal; }
    public BigDecimal getLabourCharge()             { return labourCharge; }
    public String     getJustification()            { return justification; }

    public void setMaterialsFocTotal(BigDecimal v)        { this.materialsFocTotal        = v; }
    public void setMaterialsChargeableTotal(BigDecimal v) { this.materialsChargeableTotal = v; }
    public void setLabourCharge(BigDecimal v)             { this.labourCharge             = v; }
    public void setJustification(String v)                { this.justification            = v; }
}
