package lk.slt.fieldops.inventory.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * SubmitMaterialRequestDTO — Team Lead requests stock.
 * POST /api/inventory/requests
 * {
 *   "materialId":        5,
 *   "branchId":          1,
 *   "quantityRequested": 50,
 *   "reason":            "Running low on Cat6 cable for Colombo jobs",
 *   "jobId":             12   (optional)
 * }
 */
public class SubmitMaterialRequestDTO {

    @NotNull(message = "Material ID is required")
    private Long materialId;

    @NotNull(message = "Branch ID is required")
    private Long branchId;

    @NotNull(message = "Quantity is required")
    private BigDecimal quantityRequested;

    @NotNull(message = "Reason is required")
    private String reason;

    private Long jobId;   // optional — link to a specific job

    public SubmitMaterialRequestDTO() {}

    public Long       getMaterialId()        { return materialId; }
    public Long       getBranchId()          { return branchId; }
    public BigDecimal getQuantityRequested() { return quantityRequested; }
    public String     getReason()            { return reason; }
    public Long       getJobId()             { return jobId; }

    public void setMaterialId(Long v)             { this.materialId        = v; }
    public void setBranchId(Long v)               { this.branchId          = v; }
    public void setQuantityRequested(BigDecimal v){ this.quantityRequested  = v; }
    public void setReason(String v)               { this.reason            = v; }
    public void setJobId(Long v)                  { this.jobId             = v; }
}
