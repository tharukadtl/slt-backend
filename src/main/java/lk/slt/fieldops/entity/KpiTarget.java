package lk.slt.fieldops.kpi.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * KpiTarget.java — maps to `kpi_targets` table.
 * Admin sets monthly targets per branch.
 * KpiService compares actuals vs targets to compute scores.
 */
@Entity
@Table(name = "kpi_targets")
public class KpiTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "target_year", nullable = false)
    private Integer targetYear;

    @Column(name = "target_month", nullable = false)
    private Integer targetMonth;

    @Column(name = "min_jobs_per_day", nullable = false)
    private Integer minJobsPerDay = 3;

    @Column(name = "target_sla_compliance", nullable = false, precision = 5, scale = 2)
    private BigDecimal targetSlaCompliance = new BigDecimal("95.00");

    @Column(name = "target_customer_rating", nullable = false, precision = 3, scale = 2)
    private BigDecimal targetCustomerRating = new BigDecimal("4.00");

    @Column(name = "max_avg_resolution_hours", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxAvgResolutionHours = new BigDecimal("24.00");

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public KpiTarget() {}

    public Long          getId()                       { return id; }
    public Long          getBranchId()                 { return branchId; }
    public Integer       getTargetYear()               { return targetYear; }
    public Integer       getTargetMonth()              { return targetMonth; }
    public Integer       getMinJobsPerDay()            { return minJobsPerDay; }
    public BigDecimal    getTargetSlaCompliance()      { return targetSlaCompliance; }
    public BigDecimal    getTargetCustomerRating()     { return targetCustomerRating; }
    public BigDecimal    getMaxAvgResolutionHours()    { return maxAvgResolutionHours; }
    public LocalDateTime getCreatedAt()                { return createdAt; }

    public void setId(Long v)                           { this.id                    = v; }
    public void setBranchId(Long v)                     { this.branchId              = v; }
    public void setTargetYear(Integer v)                { this.targetYear            = v; }
    public void setTargetMonth(Integer v)               { this.targetMonth           = v; }
    public void setMinJobsPerDay(Integer v)             { this.minJobsPerDay         = v; }
    public void setTargetSlaCompliance(BigDecimal v)    { this.targetSlaCompliance   = v; }
    public void setTargetCustomerRating(BigDecimal v)   { this.targetCustomerRating  = v; }
    public void setMaxAvgResolutionHours(BigDecimal v)  { this.maxAvgResolutionHours = v; }
}
