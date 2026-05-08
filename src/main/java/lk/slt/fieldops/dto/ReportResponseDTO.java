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
        private String branchName;
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