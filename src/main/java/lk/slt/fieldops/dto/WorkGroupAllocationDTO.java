package lk.slt.fieldops.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class WorkGroupAllocationDTO {

    // ─── Allocate Request (Admin: OPMC pool -> Work Group) ────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocateRequest {

        @NotNull(message = "Work Group ID is required")
        private Long workGroupId;

        @NotNull(message = "Material ID is required")
        private Long materialId;

        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.001", message = "Quantity must be greater than zero")
        private BigDecimal quantity;
    }

    // ─── Allocation Response ───────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationResponse {
        private Long workGroupId;
        private String workGroupName;
        private Long materialId;
        private String materialName;
        private BigDecimal allocatedQuantity;
        private BigDecimal opmcRemainingStock;
        private LocalDateTime updatedAt;
    }
}
