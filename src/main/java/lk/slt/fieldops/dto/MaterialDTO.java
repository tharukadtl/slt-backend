package lk.slt.fieldops.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * MaterialDTO — Admin Material CRUD (POST/PUT /api/inventory/materials).
 *
 * Replaces the previous raw Map&lt;String,Object&gt; body: field names/types below
 * match exactly what InventoryPage.js's material form already sends and what
 * StockManagementService.applyMaterialBody already reads (name, sku, unit,
 * unitPrice, stockQuantity, minThreshold, maxThreshold, reorderQuantity,
 * categoryId, isFoc, isActive) — no field was added or renamed.
 */
public class MaterialDTO {

    // ─── Create Request ───────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {

        @NotBlank(message = "Material name is required")
        @Size(max = 200, message = "Name must not exceed 200 characters")
        private String name;

        @Size(max = 50, message = "SKU must not exceed 50 characters")
        private String sku;

        @Size(max = 20, message = "Unit must not exceed 20 characters")
        private String unit;

        @DecimalMin(value = "0", message = "Unit price cannot be negative")
        private BigDecimal unitPrice;

        @DecimalMin(value = "0", message = "Stock quantity cannot be negative")
        private BigDecimal stockQuantity;

        @DecimalMin(value = "0", message = "Minimum threshold cannot be negative")
        private BigDecimal minThreshold;

        @Min(value = 0, message = "Maximum threshold cannot be negative")
        private Integer maxThreshold;

        @Min(value = 0, message = "Reorder quantity cannot be negative")
        private Integer reorderQuantity;

        private Long categoryId;
        private Boolean isFoc;
        private Boolean isActive;
    }

    // ─── Update Request ────────────────────────────────────
    /**
     * Partial-update semantics (matches the pre-existing service behavior):
     * every field is optional — whatever is present is applied, everything
     * else is left untouched — so name is not required here as it is on create.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {

        @Size(max = 200, message = "Name must not exceed 200 characters")
        private String name;

        @Size(max = 50, message = "SKU must not exceed 50 characters")
        private String sku;

        @Size(max = 20, message = "Unit must not exceed 20 characters")
        private String unit;

        @DecimalMin(value = "0", message = "Unit price cannot be negative")
        private BigDecimal unitPrice;

        @DecimalMin(value = "0", message = "Stock quantity cannot be negative")
        private BigDecimal stockQuantity;

        @DecimalMin(value = "0", message = "Minimum threshold cannot be negative")
        private BigDecimal minThreshold;

        @Min(value = 0, message = "Maximum threshold cannot be negative")
        private Integer maxThreshold;

        @Min(value = 0, message = "Reorder quantity cannot be negative")
        private Integer reorderQuantity;

        private Long categoryId;
        private Boolean isFoc;
        private Boolean isActive;
    }
}
