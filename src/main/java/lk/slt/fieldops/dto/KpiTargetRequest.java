package lk.slt.fieldops.kpi.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * KpiTargetRequest — Admin sets monthly KPI targets for a branch.
 *
 * POST /api/kpi/targets
 * {
 *   "branchId":              1,
 *   "targetYear":            2026,
 *   "targetMonth":           2,
 *   "minJobsPerDay":         4,
 *   "targetSlaCompliance":   95.00,
 *   "targetCustomerRating":  4.50,
 *   "maxAvgResolutionHours": 24.00
 * }
 */
public class KpiTargetRequest {

    private Long branchId;

    @NotNull(message = "Target year is required")
    private Integer targetYear;

    @NotNull(message = "Target month is required")
    @Min(value = 1, message = "Month must be between 1 and 12")
    @Max(value = 12, message = "Month must be between 1 and 12")
    private Integer targetMonth;

    private Integer   minJobsPerDay           = 3;
    private BigDecimal targetSlaCompliance    = new BigDecimal("95.00");
    private BigDecimal targetCustomerRating   = new BigDecimal("4.00");
    private BigDecimal maxAvgResolutionHours  = new BigDecimal("24.00");

    public KpiTargetRequest() {}

    public Long       getBranchId()              { return branchId; }
    public Integer    getTargetYear()            { return targetYear; }
    public Integer    getTargetMonth()           { return targetMonth; }
    public Integer    getMinJobsPerDay()         { return minJobsPerDay; }
    public BigDecimal getTargetSlaCompliance()   { return targetSlaCompliance; }
    public BigDecimal getTargetCustomerRating()  { return targetCustomerRating; }
    public BigDecimal getMaxAvgResolutionHours() { return maxAvgResolutionHours; }

    public void setBranchId(Long v)                  { this.branchId              = v; }
    public void setTargetYear(Integer v)             { this.targetYear            = v; }
    public void setTargetMonth(Integer v)            { this.targetMonth           = v; }
    public void setMinJobsPerDay(Integer v)          { this.minJobsPerDay         = v; }
    public void setTargetSlaCompliance(BigDecimal v) { this.targetSlaCompliance   = v; }
    public void setTargetCustomerRating(BigDecimal v){ this.targetCustomerRating  = v; }
    public void setMaxAvgResolutionHours(BigDecimal v){ this.maxAvgResolutionHours = v; }
}
