package lk.slt.fieldops.kpi.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * KpiScore.java — maps to `kpi_scores` table.
 * One row per technician per day — written by nightly scheduler at 01:00 AM.
 */
@Entity
@Table(name = "kpi_scores")
public class KpiScore {

    public enum ScoredFor { TECHNICIAN, TEAM_LEAD, BRANCH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "score_date", nullable = false)
    private LocalDate scoreDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "scored_for", nullable = false, length = 12)
    private ScoredFor scoredFor = ScoredFor.TECHNICIAN;

    @Column(name = "technician_id")
    private Long technicianId;

    @Column(name = "technician_name", length = 150)
    private String technicianName;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "jobs_assigned")
    private Integer jobsAssigned = 0;

    @Column(name = "jobs_completed")
    private Integer jobsCompleted = 0;

    @Column(name = "jobs_on_hold")
    private Integer jobsOnHold = 0;

    @Column(name = "jobs_cancelled")
    private Integer jobsCancelled = 0;

    @Column(name = "avg_resolution_hours", precision = 8, scale = 2)
    private BigDecimal avgResolutionHours;

    @Column(name = "sla_compliance_percent", precision = 5, scale = 2)
    private BigDecimal slaCompliancePercent;

    @Column(name = "avg_customer_rating", precision = 3, scale = 2)
    private BigDecimal avgCustomerRating;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public KpiScore() {}

    public Long          getId()                    { return id; }
    public LocalDate     getScoreDate()             { return scoreDate; }
    public ScoredFor     getScoredFor()             { return scoredFor; }
    public Long          getTechnicianId()          { return technicianId; }
    public String        getTechnicianName()        { return technicianName; }
    public Long          getBranchId()              { return branchId; }
    public Integer       getJobsAssigned()          { return jobsAssigned; }
    public Integer       getJobsCompleted()         { return jobsCompleted; }
    public Integer       getJobsOnHold()            { return jobsOnHold; }
    public Integer       getJobsCancelled()         { return jobsCancelled; }
    public BigDecimal    getAvgResolutionHours()    { return avgResolutionHours; }
    public BigDecimal    getSlaCompliancePercent()  { return slaCompliancePercent; }
    public BigDecimal    getAvgCustomerRating()     { return avgCustomerRating; }
    public BigDecimal    getOverallScore()          { return overallScore; }
    public LocalDateTime getCreatedAt()             { return createdAt; }

    public void setId(Long v)                        { this.id                   = v; }
    public void setScoreDate(LocalDate v)            { this.scoreDate            = v; }
    public void setScoredFor(ScoredFor v)            { this.scoredFor            = v; }
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
}
