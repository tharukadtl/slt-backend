package lk.slt.fieldops.kpi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * KpiSummaryDTO — aggregated KPI summary for the dashboard.
 *
 * Returned by:
 *   GET /api/kpi/summary?branchId=1
 *   GET /api/kpi/summary/technician/{id}
 */
public class KpiSummaryDTO {

    private Long          technicianId;
    private String        technicianName;
    private Long          branchId;
    private LocalDate     periodFrom;
    private LocalDate     periodTo;

    private Integer       totalJobsAssigned;
    private Integer       totalJobsCompleted;
    private Integer       totalJobsOnHold;
    private Integer       totalJobsCancelled;
    private BigDecimal    completionRate;        // jobsCompleted / jobsAssigned * 100
    private BigDecimal    avgOverallScore;
    private BigDecimal    avgSlaCompliance;
    private BigDecimal    avgCustomerRating;
    private String        performanceLabel;       // EXCELLENT / GOOD / AVERAGE / POOR
    private List<KpiScoreDTO> dailyBreakdown;

    public KpiSummaryDTO() {}

    public Long            getTechnicianId()      { return technicianId; }
    public String          getTechnicianName()    { return technicianName; }
    public Long            getBranchId()          { return branchId; }
    public LocalDate       getPeriodFrom()        { return periodFrom; }
    public LocalDate       getPeriodTo()          { return periodTo; }
    public Integer         getTotalJobsAssigned() { return totalJobsAssigned; }
    public Integer         getTotalJobsCompleted(){ return totalJobsCompleted; }
    public Integer         getTotalJobsOnHold()   { return totalJobsOnHold; }
    public Integer         getTotalJobsCancelled(){ return totalJobsCancelled; }
    public BigDecimal      getCompletionRate()    { return completionRate; }
    public BigDecimal      getAvgOverallScore()   { return avgOverallScore; }
    public BigDecimal      getAvgSlaCompliance()  { return avgSlaCompliance; }
    public BigDecimal      getAvgCustomerRating() { return avgCustomerRating; }
    public String          getPerformanceLabel()  { return performanceLabel; }
    public List<KpiScoreDTO> getDailyBreakdown()  { return dailyBreakdown; }

    public void setTechnicianId(Long v)              { this.technicianId      = v; }
    public void setTechnicianName(String v)          { this.technicianName    = v; }
    public void setBranchId(Long v)                  { this.branchId          = v; }
    public void setPeriodFrom(LocalDate v)           { this.periodFrom        = v; }
    public void setPeriodTo(LocalDate v)             { this.periodTo          = v; }
    public void setTotalJobsAssigned(Integer v)      { this.totalJobsAssigned = v; }
    public void setTotalJobsCompleted(Integer v)     { this.totalJobsCompleted= v; }
    public void setTotalJobsOnHold(Integer v)        { this.totalJobsOnHold   = v; }
    public void setTotalJobsCancelled(Integer v)     { this.totalJobsCancelled= v; }
    public void setCompletionRate(BigDecimal v)      { this.completionRate    = v; }
    public void setAvgOverallScore(BigDecimal v)     { this.avgOverallScore   = v; }
    public void setAvgSlaCompliance(BigDecimal v)    { this.avgSlaCompliance  = v; }
    public void setAvgCustomerRating(BigDecimal v)   { this.avgCustomerRating = v; }
    public void setPerformanceLabel(String v)        { this.performanceLabel  = v; }
    public void setDailyBreakdown(List<KpiScoreDTO> v){ this.dailyBreakdown   = v; }
}
