package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "kpi_targets",
        indexes = {
                @Index(
                        name = "idx_kpi_target_user",
                        columnList = "user_id"),
                @Index(
                        name = "idx_kpi_target_period",
                        columnList = "period"),
                @Index(
                        name = "idx_kpi_target_branch",
                        columnList = "branch_id")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpiTarget {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Target Owner ─────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // ─── Assigned By ──────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_id")
    private User assignedBy;

    // ─── Branch (for group targets) ───────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    // ─── Target Details ───────────────────────────────────
    @Column(name = "title",
            nullable = false,
            length = 200)
    private String title;

    @Column(name = "description",
            length = 500)
    private String description;

    @Column(name = "target_value",
            nullable = false)
    private Double targetValue;

    @Column(name = "current_value")
    @Builder.Default
    private Double currentValue = 0.0;

    @Column(name = "unit",
            length = 50)
    private String unit;

    // ─── Classification ───────────────────────────────────
    @Column(name = "period",
            nullable = false,
            length = 20)
    private String period;

    @Column(name = "category",
            length = 30)
    private String category;

    // ─── Dates ────────────────────────────────────────────
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "start_date")
    private LocalDate startDate;

    // ─── Status ───────────────────────────────────────────
    @Column(name = "status",
            length = 30)
    @Builder.Default
    private String status = "ON_TRACK";

    @Column(name = "is_group_target")
    @Builder.Default
    private Boolean isGroupTarget = false;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    // ─── Branch-level monthly target fields ───────────────
    @Column(name = "kpi_branch_id")
    private Long branchId;

    @Column(name = "target_year")
    private Integer targetYear;

    @Column(name = "target_month")
    private Integer targetMonth;

    @Column(name = "min_jobs_per_day")
    private Integer minJobsPerDay;

    @Column(name = "target_sla_compliance", precision = 5, scale = 2)
    private BigDecimal targetSlaCompliance;

    @Column(name = "target_customer_rating", precision = 3, scale = 2)
    private BigDecimal targetCustomerRating;

    @Column(name = "max_avg_resolution_hours", precision = 6, scale = 2)
    private BigDecimal maxAvgResolutionHours;

    // ─── Timestamps ───────────────────────────────────────
    @Column(name = "created_at",
            updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}