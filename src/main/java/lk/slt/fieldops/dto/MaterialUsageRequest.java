package lk.slt.fieldops.job.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * MaterialUsageRequest — Technician logs a material used in a job.
 *
 * POST /api/jobs/{id}/materials
 * {
 *   "materialId":    5,
 *   "quantityUsed":  10.5,
 *   "chargeType":    "FOC",
 *   "justification": "Replaced damaged section"
 * }
 */
public class MaterialUsageRequest {

    @NotNull(message = "Material ID is required")
    private Long materialId;

    @NotNull(message = "Quantity is required")
    private BigDecimal quantityUsed;

    private String chargeType = "FOC";   // FOC or CHARGEABLE
    private String justification;

    public MaterialUsageRequest() {}

    public Long       getMaterialId()   { return materialId; }
    public BigDecimal getQuantityUsed() { return quantityUsed; }
    public String     getChargeType()   { return chargeType; }
    public String     getJustification(){ return justification; }

    public void setMaterialId(Long v)         { this.materialId   = v; }
    public void setQuantityUsed(BigDecimal v) { this.quantityUsed = v; }
    public void setChargeType(String v)       { this.chargeType   = v; }
    public void setJustification(String v)    { this.justification= v; }
}
