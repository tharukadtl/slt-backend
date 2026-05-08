package lk.slt.fieldops.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class DashboardDTO {

    // ─── KPI Summary ──────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KpiSummaryDTO {
        private long totalFaults;
        private long openFaults;
        private long inProgressFaults;
        private long completedToday;
        private long completedThisMonth;
        private long cancelledFaults;
        private long pendingPayments;
        private long approvedPayments;
        private long totalTechnicians;
        private long activeTechnicians;
        private long totalUsers;
        private double avgResolutionTimeHours;
        private double customerSatisfactionScore;
        private double completionRate;
        private double onTimeCompletionRate;
        private double totalRevenueThisMonth;
        private LocalDateTime generatedAt;

        // Trend indicators vs previous period
        private String faultsTrend;
        private String completionTrend;
        private String revenueTrend;
        private String satisfactionTrend;
    }

    // ─── Fault Distribution ───────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FaultDistributionDTO {
        private long open;
        private long inProgress;
        private long completed;
        private long cancelled;
        private long pending;
        private long total;
        private double openPercent;
        private double inProgressPercent;
        private double completedPercent;
        private double cancelledPercent;

        // By category
        private List<CategoryCountDTO>
                byCategory;

        // By priority
        private List<PriorityCountDTO>
                byPriority;

        // By branch
        private List<BranchCountDTO>
                byBranch;
    }

    // ─── Category Count ───────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryCountDTO {
        private String category;
        private long count;
        private double percentage;
        private String color;
    }

    // ─── Priority Count ───────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriorityCountDTO {
        private String priority;
        private long count;
        private double percentage;
        private String color;
    }

    // ─── Branch Count ─────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BranchCountDTO {
        private String branchName;
        private long count;
        private double percentage;
    }

    // ─── Fault Trend Point ────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FaultTrendPointDTO {
        private String date;
        private String dayOfWeek;
        private long total;
        private long opened;
        private long completed;
        private long cancelled;
        private double avgResolutionHours;
    }

    // ─── Technician Performance ───────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TechnicianPerformanceDTO {
        private Long technicianId;
        private String name;
        private String phone;
        private String avatarInitial;
        private long totalJobs;
        private long completedJobs;
        private long activeJobs;
        private double completionRate;
        private double avgDurationHours;
        private double satisfactionScore;
        private double onTimeRate;
        private String performanceLevel;
        private boolean isOnline;
        private String currentStatus;
    }

    // ─── Recent Activity ──────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityItemDTO {
        private Long id;
        private String type;
        private String icon;
        private String title;
        private String description;
        private String actorName;
        private String actorRole;
        private String entityId;
        private String entityType;
        private LocalDateTime timestamp;
        private String timeAgo;
        private String color;
    }

    // ─── Geographic Data ──────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeographicDataDTO {
        private List<GeoPointDTO> faultHeatMap;
        private List<GeoPointDTO>
                technicianLocations;
        private List<GeoBoundaryDTO> regions;
    }

    // ─── Geo Point ────────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoPointDTO {
        private Double latitude;
        private Double longitude;
        private String label;
        private String type;
        private long count;
        private double intensity;
        private String status;
        private String technicianName;
        private String faultId;
    }

    // ─── Geo Boundary ─────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoBoundaryDTO {
        private String regionName;
        private long faultCount;
        private double density;
        private String riskLevel;
        private List<double[]> coordinates;
    }
}