package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class KpiDTO {

    // ─── Period Constants ─────────────────────────────────
    public static final String PERIOD_DAILY =
            "DAILY";
    public static final String PERIOD_WEEKLY =
            "WEEKLY";
    public static final String PERIOD_MONTHLY =
            "MONTHLY";

    // ─── Category Constants ───────────────────────────────
    public static final String CAT_JOBS = "JOBS";
    public static final String CAT_TIME = "TIME";
    public static final String CAT_SATISFACTION =
            "SATISFACTION";
    public static final String CAT_REVENUE =
            "REVENUE";
    public static final String CAT_ATTENDANCE =
            "ATTENDANCE";

    // ─── Status Constants ─────────────────────────────────
    public static final String STATUS_ACHIEVED =
            "ACHIEVED";
    public static final String STATUS_ON_TRACK =
            "ON_TRACK";
    public static final String STATUS_AT_RISK =
            "AT_RISK";
    public static final String STATUS_BEHIND =
            "BEHIND";

    // ─── Assign Target Request ────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignTargetRequest {

        @NotNull(message =
                "Technician ID is required")
        private Long technicianId;

        @NotBlank(message =
                "Title is required")
        private String title;

        private String description;

        @NotNull(message =
                "Target value is required")
        @Positive(message =
                "Target value must be positive")
        private Double targetValue;

        @NotBlank(message =
                "Unit is required")
        private String unit;

        @NotBlank(message =
                "Period is required")
        private String period;

        @NotBlank(message =
                "Category is required")
        private String category;

        @NotNull(message =
                "Due date is required")
        private LocalDate dueDate;

        private boolean isGroupTarget;
        private Long branchId;
    }

    // ─── Target Response ──────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TargetResponseDTO {
        private Long id;
        private Long technicianId;
        private String technicianName;
        private String title;
        private String description;
        private Double targetValue;
        private Double currentValue;
        private String unit;
        private String period;
        private String category;
        private String categoryIcon;
        private LocalDate dueDate;
        private String status;
        private String statusIcon;
        private Double progressPercent;
        private String assignedBy;
        private LocalDateTime assignedAt;
        private boolean isGroupTarget;
        private Long branchId;
        private String branchName;
    }

    // ─── Personal KPI Score ───────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PersonalKpiDTO {
        private Long technicianId;
        private String technicianName;
        private String phone;
        private String avatarInitial;
        private String period;
        private String startDate;
        private String endDate;

        // Core metrics
        private long totalJobs;
        private long completedJobs;
        private long inProgressJobs;
        private long cancelledJobs;
        private double completionRate;
        private double avgJobDurationHours;
        private double avgResponseTimeMinutes;
        private double customerSatisfactionScore;
        private double onTimeCompletionRate;
        private double totalRevenue;

        // Attendance
        private int presentDays;
        private double avgWorkingHours;
        private double attendanceRate;

        // Overall score (0-100)
        private double overallScore;
        private String performanceLevel;
        private String performanceColor;

        // Star rating (1-5)
        private double starRating;

        // Targets
        private int totalTargets;
        private int achievedTargets;
        private int onTrackTargets;
        private int atRiskTargets;
        private int behindTargets;

        private List<TargetResponseDTO> targets;
        private LocalDateTime generatedAt;
    }

    // ─── Team KPI ─────────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamKpiDTO {
        private Long branchId;
        private String branchName;
        private String period;
        private String startDate;
        private String endDate;

        // Team aggregate metrics
        private int totalMembers;
        private int activeMembers;
        private long totalJobsAssigned;
        private long totalJobsCompleted;
        private double teamCompletionRate;
        private double avgJobDurationHours;
        private double avgResponseTimeMinutes;
        private double teamSatisfactionScore;
        private double teamOnTimeRate;
        private double totalTeamRevenue;
        private double teamAttendanceRate;
        private double teamOverallScore;

        // Individual breakdowns
        private List<PersonalKpiDTO>
                memberKpis;
        private LocalDateTime generatedAt;
    }

    // ─── Leaderboard Entry ────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeaderboardEntryDTO {
        private int rank;
        private Long technicianId;
        private String technicianName;
        private String phone;
        private String avatarInitial;
        private String branchName;
        private double overallScore;
        private double completionRate;
        private double satisfactionScore;
        private long completedJobs;
        private String performanceLevel;
        private String performanceColor;
        private String badge;
        private boolean isCurrentUser;
        private String trend;
        private double starRating;
    }

    // ─── KPI Metric ───────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KpiMetricDTO {
        private String label;
        private String value;
        private String unit;
        private double numericValue;
        private double targetValue;
        private double progressPercent;
        private String trend;
        private String color;
        private String icon;
    }
}