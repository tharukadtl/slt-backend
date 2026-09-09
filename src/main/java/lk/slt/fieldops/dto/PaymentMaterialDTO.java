package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

public class PaymentMaterialDTO {

    // ─── Per-item line submitted with a payment (issue #28) ───────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineRequest {

        @NotNull(message = "Material ID is required")
        private Long materialId;

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be positive")
        private BigDecimal quantity;

        /** WARRANTY, INSTALLATION, CUSTOMER_DAMAGE or CUSTOMER_UPGRADE — fed to FocDeterminator. */
        private String classification;
    }

    // ─── Per-item line in a payment's detail view (with material name JOINed in) ──
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineResponse {
        private Long id;
        private Long materialId;
        private String materialName;
        private BigDecimal quantity;
        private BigDecimal unitPriceAtTime;
        private BigDecimal lineTotal;
        private String classification;
        private String chargeType;
        private String overrideJustification;
    }

    // ─── PATCH /api/payments/materials/{id}/override-foc ──────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverrideFocRequest {

        @NotBlank(message = "A justification is required to override the FOC classification")
        private String justification;
    }
}
