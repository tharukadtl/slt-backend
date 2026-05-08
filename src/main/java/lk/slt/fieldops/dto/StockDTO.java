package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class StockDTO {

    // ─── Transaction Type Constants ───────────────────────
    public static final String TYPE_ADJUSTMENT =
            "MANUAL_ADJUSTMENT";
    public static final String TYPE_RESTOCK =
            "RESTOCK";
    public static final String TYPE_USAGE =
            "USAGE";
    public static final String TYPE_DAMAGE =
            "DAMAGE";
    public static final String TYPE_RETURN =
            "RETURN";
    public static final String TYPE_REQUEST =
            "MATERIAL_REQUEST_APPROVED";
    public static final String TYPE_INITIAL =
            "INITIAL_STOCK";

    // ─── Adjust Request ───────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdjustRequest {

        @NotNull(message =
                "Material ID is required")
        private Long materialId;

        @NotNull(message =
                "Quantity is required")
        private Integer quantityChange;

        @NotBlank(message =
                "Transaction type is required")
        private String transactionType;

        @NotBlank(message =
                "Reason is required")
        private String reason;

        private String reference;
        private String notes;
        private Double unitCost;
    }

    // ─── Bulk Adjust Request ──────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAdjustRequest {
        @NotNull(message =
                "Adjustments list is required")
        private List<AdjustRequest> adjustments;
        private String batchReference;
        private String notes;
    }

    // ─── Stock Level Response ─────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockLevelDTO {
        private Long materialId;
        private String materialName;
        private String sku;
        private String category;
        private String unit;
        private Integer currentStock;
        private Integer minThreshold;
        private Integer maxThreshold;
        private Double unitPrice;
        private Double totalValue;
        private boolean isFOC;
        private String stockStatus;
        private String stockStatusColor;
        private String stockStatusIcon;
        private double stockPercentage;
        private Integer reorderQuantity;
        private LocalDateTime lastUpdated;
        private LocalDateTime lastRestocked;
    }

    // ─── Adjust Response ──────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdjustResponse {
        private Long transactionId;
        private Long materialId;
        private String materialName;
        private String sku;
        private Integer previousStock;
        private Integer quantityChange;
        private Integer newStock;
        private String transactionType;
        private String reason;
        private String reference;
        private String performedBy;
        private LocalDateTime timestamp;
        private String stockStatus;
        private boolean lowStockAlert;
        private String message;
    }

    // ─── Bulk Adjust Response ─────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAdjustResponse {
        private int totalRequested;
        private int successCount;
        private int failureCount;
        private List<AdjustResponse>
                successfulAdjustments;
        private List<String> errors;
        private LocalDateTime processedAt;
    }

    // ─── Low Stock Alert ──────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LowStockAlertDTO {
        private Long materialId;
        private String materialName;
        private String sku;
        private String category;
        private String unit;
        private Integer currentStock;
        private Integer minThreshold;
        private Integer reorderQuantity;
        private Double unitPrice;
        private Double reorderCost;
        private String alertLevel;
        private String alertColor;
        private String alertIcon;
        private LocalDateTime lastRestocked;
        private String message;
    }

    // ─── Low Stock Summary ────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LowStockSummaryDTO {
        private int totalAlerts;
        private int outOfStockCount;
        private int criticalCount;
        private int warningCount;
        private double totalReorderCost;
        private List<LowStockAlertDTO> alerts;
        private LocalDateTime generatedAt;
    }

    // ─── Transaction Response ─────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionResponse {
        private Long id;
        private Long materialId;
        private String materialName;
        private String sku;
        private String unit;
        private Integer quantityChange;
        private Integer stockBefore;
        private Integer stockAfter;
        private String transactionType;
        private String transactionTypeLabel;
        private String transactionIcon;
        private String transactionColor;
        private String reason;
        private String reference;
        private String notes;
        private Long performedById;
        private String performedByName;
        private String performedByRole;
        private Double unitCost;
        private Double totalCost;
        private LocalDateTime createdAt;
        private String timeAgo;
    }

    // ─── Transaction History ──────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionHistoryDTO {
        private Long materialId;
        private String materialName;
        private String sku;
        private Integer currentStock;
        private String unit;
        private int totalTransactions;
        private Integer totalIn;
        private Integer totalOut;
        private Double totalValue;
        private LocalDateTime lastTransaction;
        private List<TransactionResponse>
                transactions;
    }
}