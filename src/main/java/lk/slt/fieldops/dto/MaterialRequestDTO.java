package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class MaterialRequestDTO {

    // ─── Status Constants ─────────────────────────────────
    public static final String STATUS_PENDING =
            "PENDING";
    public static final String STATUS_APPROVED =
            "APPROVED";
    public static final String STATUS_REJECTED =
            "REJECTED";
    public static final String STATUS_DELIVERED =
            "DELIVERED";
    public static final String STATUS_CANCELLED =
            "CANCELLED";

    // ─── Submit Request ───────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitRequest {

        @NotEmpty(message =
                "At least one item is required")
        private List<RequestItemDTO> items;

        private String taskId;
        private String faultId;
        private String notes;
        private String urgency;
    }

    // ─── Request Item ─────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestItemDTO {

        @NotNull(message =
                "Material ID is required")
        private Long materialId;

        @NotNull(message =
                "Quantity is required")
        @Positive(message =
                "Quantity must be positive")
        private Integer quantity;

        private String notes;
    }

    // ─── Approve Request ──────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApproveRequest {

        private String notes;
        private List<ApprovedItemDTO>
                approvedItems;
        private boolean notifyRequester;
    }

    // ─── Approved Item ────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovedItemDTO {

        @NotNull(message =
                "Material ID is required")
        private Long materialId;

        @NotNull(message =
                "Approved quantity is required")
        @Positive(message =
                "Quantity must be positive")
        private Integer approvedQuantity;
    }

    // ─── Reject Request ───────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectRequest {

        @NotBlank(message =
                "Rejection reason is required")
        private String reason;

        private String notes;
        private boolean notifyRequester;
    }

    // ─── Material Item Response ───────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialItemResponse {
        private Long materialId;
        private String materialName;
        private String sku;
        private String category;
        private Integer requestedQuantity;
        private Integer approvedQuantity;
        private String unit;
        private Double unitPrice;
        private Double subtotal;
        private boolean isFOC;
        private Integer availableStock;
        private String stockStatus;
    }

    // ─── Request Response ─────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestResponse {
        private Long id;
        private String requestNumber;

        // Requester info
        private Long requesterId;
        private String requesterName;
        private String requesterRole;
        private String requesterPhone;

        // Reviewer info
        private Long reviewerId;
        private String reviewerName;

        // Job/Fault info
        private String taskId;
        private String faultId;
        private String faultDescription;

        // Items
        private List<MaterialItemResponse> items;
        private int totalItems;
        private int totalQuantity;
        private double totalEstimatedCost;
        private double totalApprovedCost;

        // Status
        private String status;
        private String statusIcon;
        private String statusColor;
        private String urgency;

        // Notes
        private String requesterNotes;
        private String reviewerNotes;
        private String rejectionReason;

        // Timestamps
        private LocalDateTime submittedAt;
        private LocalDateTime reviewedAt;
        private LocalDateTime deliveredAt;
        private String submittedTimeAgo;
        private String reviewedTimeAgo;
    }

    // ─── Pending Summary ──────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingSummaryDTO {
        private int totalPending;
        private int urgentCount;
        private int normalCount;
        private double totalEstimatedValue;
        private List<RequestResponse> requests;
        private LocalDateTime generatedAt;
    }

    // ─── History Summary ──────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistorySummaryDTO {
        private int totalRequests;
        private int approvedCount;
        private int rejectedCount;
        private int pendingCount;
        private int deliveredCount;
        private double totalApprovedValue;
        private double approvalRate;
        private List<RequestResponse> requests;
        private LocalDateTime generatedAt;
    }
}