package lk.slt.fieldops.kpi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * KpiScoreDTO — returned in API responses for KPI scores.
 *
 * Used by:
 *   GET /api/kpi/leaderboard
 *   GET /api/kpi/my
 *   GET /api/kpi/technician/{id}
 *   GET /api/kpi/branch/{id}
 */
public class KpiScoreDTO {

    private Long       id;
    private LocalDate  scoreDate;
    private String     scoredFor;
    private Long       technicianId;
    private String     technicianName;
    private Long       branchId;
    private Integer    jobsAssigned;
    private Integer    jobsCompleted;
    private Integer    jobsOnHold;
    private Integer    jobsCancelled;
    private BigDecimal avgResolutionHours;
    private BigDecimal slaCompliancePercent;
    private BigDecimal avgCustomerRating;
    private BigDecimal overallScore;
    private String     performanceLabel;   // EXCELLENT / GOOD / AVERAGE / POOR

    public KpiScoreDTO() {}

    public Long       getId()                    { return id; }
    public LocalDate  getScoreDate()             { return scoreDate; }
    public String     getScoredFor()             { return scoredFor; }
    public Long       getTechnicianId()          { return technicianId; }
    public String     getTechnicianName()        { return technicianName; }
    public Long       getBranchId()              { return branchId; }
    public Integer    getJobsAssigned()          { return jobsAssigned; }
    public Integer    getJobsCompleted()         { return jobsCompleted; }
    public Integer    getJobsOnHold()            { return jobsOnHold; }
    public Integer    getJobsCancelled()         { return jobsCancelled; }
    public BigDecimal getAvgResolutionHours()    { return avgResolutionHours; }
    public BigDecimal getSlaCompliancePercent()  { return slaCompliancePercent; }
    public BigDecimal getAvgCustomerRating()     { return avgCustomerRating; }
    public BigDecimal getOverallScore()          { return overallScore; }
    public String     getPerformanceLabel()      { return performanceLabel; }

    public void setId(Long v)                        { this.id                   = v; }
    public void setScoreDate(LocalDate v)            { this.scoreDate            = v; }
    public void setScoredFor(String v)               { this.scoredFor            = v; }
    public void setTechnicianId(Long v)              { this.technicianId         = v; }
    public void setTechnicianName(String v)          { this.technicianName       = v; }
    public void setBranchId(Long v)                  { this.branchId             = v; }
    public void setJobsAssigned(Integer v)           { this.jobsAssigned         = v; }
    public void setJobsCompleted(Integer v)          { this.jobsCompleted        = v; }
    public void setJobsOnHold(Integer v)             { this.jobsOnHold           = v; }
    public void setJobsCancelled(Integer v)          { this.jobsCancelled        = v; }
    public void setAvgResolutionHours(BigDecimal v)  { this.avgResolutionHours   = v; }
    public void setSlaCompliancePercent(BigDecimal v){ this.slaCompliancePercent = v; }
    public void setAvgCustomerRating(BigDecimal v)   { this.avgCustomerRating    = v; }
    public void setOverallScore(BigDecimal v)        { this.overallScore         = v; }
    public void setPerformanceLabel(String v)        { this.performanceLabel     = v; }
}
