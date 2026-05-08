package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

// Fields split into two groups:
// - "legacy" fields used by KpiCalculationService (user, totalJobs, completionRate, etc.)
// - "new" fields used by KpiService scheduler (technicianId, jobsAssigned, slaCompliancePercent, etc.)

@Entity
@Table(name = "kpi_scores",
        indexes = {
                @Index(name = "idx_kpi_score_tech",    columnList = "technician_id"),
                @Index(name = "idx_kpi_score_period",  columnList = "period"),
                @Index(name = "idx_kpi_score_date",    columnList = "score_date"),
                @Index(name = "idx_kpi_score_branch",  columnList = "branch_id")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpiScore {

    public enum ScoredFor {
        TECHNICIAN, TEAM_LEAD, BRANCH
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Who this score is for ────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "scored_for", length = 20)
    @Builder.Default
    private ScoredFor scoredFor = ScoredFor.TECHNICIAN;

    @Column(name = "technician_id")
    private Long technicianId;

    @Column(name = "technician_name", length = 150)
    private String technicianName;

    @Column(name = "branch_id")
    private Long branchId;

    // ─── Period ───────────────────────────────────────────
    @Column(name = "period", nullable = false, length = 20)
    private String period;

    @Column(name = "score_date")
    private LocalDate scoreDate;

    // ─── Job Metrics ──────────────────────────────────────
    @Column(name = "jobs_assigned")
    @Builder.Default
    private Integer jobsAssigned = 0;

    @Column(name = "jobs_completed")
    @Builder.Default
    private Integer jobsCompleted = 0;

    @Column(name = "jobs_on_hold")
    @Builder.Default
    private Integer jobsOnHold = 0;

    @Column(name = "jobs_cancelled")
    @Builder.Default
    private Integer jobsCancelled = 0;

    @Column(name = "sla_compliance_percent", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal slaCompliancePercent = BigDecimal.ZERO;

    @Column(name = "avg_resolution_hours", precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal avgResolutionHours = BigDecimal.ZERO;

    @Column(name = "avg_customer_rating", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal avgCustomerRating = BigDecimal.ZERO;

    // ─── Legacy fields (KpiCalculationService) ────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "total_jobs")
    @Builder.Default
    private Long totalJobs = 0L;

    @Column(name = "completed_jobs")
    @Builder.Default
    private Long completedJobs = 0L;

    @Column(name = "completion_rate")
    @Builder.Default
    private Double completionRate = 0.0;

    @Column(name = "avg_job_duration_hours")
    @Builder.Default
    private Double avgJobDurationHours = 0.0;

    @Column(name = "avg_response_time_minutes")
    @Builder.Default
    private Double avgResponseTimeMinutes = 0.0;

    @Column(name = "customer_satisfaction_score")
    @Builder.Default
    private Double customerSatisfactionScore = 0.0;

    @Column(name = "on_time_completion_rate")
    @Builder.Default
    private Double onTimeCompletionRate = 0.0;

    @Column(name = "total_revenue")
    @Builder.Default
    private Double totalRevenue = 0.0;

    @Column(name = "attendance_rate")
    @Builder.Default
    private Double attendanceRate = 0.0;

    @Column(name = "star_rating")
    @Builder.Default
    private Double starRating = 0.0;

    // ─── Overall Score (Double for KpiCalculationService) ─
    @Column(name = "overall_score")
    @Builder.Default
    private Double overallScore = 0.0;

    @Column(name = "performance_level", length = 30)
    private String performanceLevel;

    // ─── Timestamps ───────────────────────────────────────
    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        calculatedAt = LocalDateTime.now();
    }
}
