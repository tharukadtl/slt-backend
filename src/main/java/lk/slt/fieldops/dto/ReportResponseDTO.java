package lk.slt.fieldops.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportResponseDTO {

    // ─── Fault Trends ────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FaultTrendDTO {
        private String date;
        private long totalFaults;
        private long openFaults;
        private long inProgressFaults;
        private long completedFaults;
        private long cancelledFaults;
        private double avgResolutionTimeHours;
    }

    // ─── Technician Performance ───────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TechnicianPerformanceDTO {
        private String technicianId;
        private String technicianName;
        private String phone;
        private String opmcName;
        private long totalJobsAssigned;
        private long jobsCompleted;
        private long jobsInProgress;
        private long jobsCancelled;
        private double completionRate;
        private double avgJobDurationHours;
        private double avgResponseTimeMinutes;
        private double customerSatisfactionScore;
        private long onTimeCompletions;
        private double onTimeCompletionRate;
        private long totalWorkingDays;
        private double avgJobsPerDay;
    }

    // ─── Financial Summary ────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinancialSummaryDTO {
        private String period;
        private double totalRevenue;
        private double totalFOCAmount;
        private double totalChargeableAmount;
        private double totalMaterialCost;
        private double totalLaborCost;
        private long totalPaymentsSubmitted;
        private long totalPaymentsApproved;
        private long totalPaymentsRejected;
        private long totalPaymentsPending;
        private double approvalRate;
        private List<CategoryBreakdownDTO> categoryBreakdown;
        private List<CategoryBreakdownDTO> technicianBreakdown;
        private List<CategoryBreakdownDTO> opmcBreakdown;
        private List<RejectedPaymentDTO> rejectedPayments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectedPaymentDTO {
        private String paymentNumber;
        private String technicianName;
        private double amount;
        private String reason;
        private LocalDateTime rejectedAt;
    }

    // ─── Category Breakdown ───────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryBreakdownDTO {
        private String category;
        private long count;
        private double amount;
        private double percentage;
    }

    // ─── Customer Satisfaction ────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomerSatisfactionDTO {
        private String period;
        private double overallScore;
        private long totalResponses;
        private long rating5Count;
        private long rating4Count;
        private long rating3Count;
        private long rating2Count;
        private long rating1Count;
        private double avgResolutionTimeHours;
        private double onTimeDeliveryRate;
        private List<TechnicianSatisfactionDTO>
                technicianScores;
    }

    // ─── Technician Satisfaction ──────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TechnicianSatisfactionDTO {
        private String technicianName;
        private double avgScore;
        private long totalRatings;
    }

    // ─── REP-01: Daily Fault Summary ──────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyFaultSummaryDTO {
        private long totalFaults;
        private List<CategoryBreakdownDTO> byCategory;
        private List<CategoryBreakdownDTO> byStatus;
        private double avgResolutionTimeHours;
        private List<WorkloadDTO> technicianWorkload;
        private List<GeoDistributionDTO> geographicDistribution;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkloadDTO {
        private String technicianId;
        private String technicianName;
        private long assignedFaults;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoDistributionDTO {
        private String region;
        private long faultCount;
    }

    // ─── REP-03: Fault Aging ───────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FaultAgingItemDTO {
        private Long faultId;
        private String faultNumber;
        private String category;
        private String status;
        private String priority;
        private double ageHours;
        private String ageBucket;          // 0-24H | 24-48H | 48-72H | 72H+
        private String assignedTechnician;
        private LocalDateTime lastStatusUpdate;
        private boolean escalated;
    }

    // ─── REP-05: Material Cost ─────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialCostReportDTO {
        private double totalFocCost;
        private double totalChargeableCost;
        private List<MaterialUsageSummaryDTO> mostUsedMaterials;
        private List<MaterialUsageSummaryDTO> wastageAndDamage;
        private List<ReorderEstimateDTO> reorderEstimates;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialUsageSummaryDTO {
        private Long materialId;
        private String materialName;
        private double totalQuantity;
        private double totalCost;
        private long usageCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReorderEstimateDTO {
        private Long materialId;
        private String materialName;
        private double currentStock;
        private double minimumThreshold;
        private int reorderQuantity;
        private double estimatedCost;
    }

    // ─── REP-06: AI Forecast ────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForecastPointDTO {
        private String date;
        private double predictedFaults;
        private double lowerBound;
        private double upperBound;
        private boolean isForecast;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiForecastReportDTO {
        private List<ForecastPointDTO> historical;
        private List<ForecastPointDTO> forecast;
        private double accuracy;
        private double mae;
        private double rmse;
        private String dataSource;
    }

    // ─── REP-07: Geographic Demand ───────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoClusterDTO {
        private int clusterId;
        private String regionName;
        private long faultCount;
        private String riskLevel;
        private Double centroidLat;
        private Double centroidLng;
        private String topCategory;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeographicDemandReportDTO {
        private List<GeoClusterDTO> clusters;
        private long totalFaults;
        private String dataSource;
    }

    // ─── REP-08: KPI Performance ────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KpiReportEntryDTO {
        private String technicianId;
        private String technicianName;
        private double overallScore;
        private double starRating;
        private int rank;
        private String badge;              // GOLD | SILVER | BRONZE | null
        private int targetsAchieved;
        private int totalTargets;
    }

    // ─── REP-09: Attendance ─────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttendanceReportItemDTO {
        private String userId;
        private String userName;
        private String date;
        private LocalDateTime checkInTime;
        private Double checkInLatitude;
        private Double checkInLongitude;
        private LocalDateTime checkOutTime;
        private Double checkOutLatitude;
        private Double checkOutLongitude;
        private Double workingHours;
        private boolean absent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttendanceSummaryDTO {
        private String userId;
        private String userName;
        private long presentDays;
        private long absentDays;
        private double attendanceRate;
        private double avgWorkingHours;
    }

    // ─── REP-10: Audit Trail ───────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditTrailItemDTO {
        private String entityType;         // FAULT | PAYMENT | USER | STOCK
        private String entityId;
        private String action;
        private String actorName;
        private String actorRole;
        private String ipAddress;
        private String previousValue;
        private String newValue;
        private String details;
        private LocalDateTime timestamp;
    }

    // ─── Summary Stats ────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryStatsDTO {
        private String label;
        private String value;
        private String change;
        private String trend;
    }

    // ─── Report Metadata ──────────────────────────────────
    private String reportType;
    private String reportTitle;
    private String period;
    private String startDate;
    private String endDate;
    private LocalDateTime generatedAt;
    private String generatedBy;
    private long totalRecords;
    private List<SummaryStatsDTO> summaryStats;
    private Object data;
}