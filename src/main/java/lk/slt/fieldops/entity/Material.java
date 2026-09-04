package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "materials",
        indexes = {
                @Index(name = "idx_material_sku",   columnList = "sku",       unique = true),
                @Index(name = "idx_material_opmc", columnList = "opmc_id"),
                @Index(name = "idx_material_stock",  columnList = "stock_status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Material {

    public enum ChargeType  { FOC, CHARGEABLE }
    public enum StockStatus { IN_STOCK, LOW_STOCK, OUT_OF_STOCK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "opmc_id")
    private Long opmcId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "sku", unique = true, length = 50)
    private String sku;

    @Column(name = "description", length = 500)
    private String description;

    // Cause/Material hierarchy import (2026-08-21): richer WFMS material metadata,
    // additive/nullable — the 2 pre-existing app-seeded rows get NULL in all four.
    @Column(name = "erp_code", length = 50)
    private String erpCode;

    @Column(name = "erp_description", length = 300)
    private String erpDescription;

    @Column(name = "brand", length = 100)
    private String brand;

    @Column(name = "measurement_code", length = 10)
    private String measurementCode;

    @Column(name = "unit", length = 20)
    @Builder.Default
    private String unit = "pcs";

    @Column(name = "unit_price", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", length = 20)
    @Builder.Default
    private ChargeType chargeType = ChargeType.FOC;

    @Column(name = "current_stock", precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal currentStock = BigDecimal.ZERO;

    @Column(name = "minimum_threshold", precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal minimumThreshold = BigDecimal.TEN;

    @Column(name = "max_threshold")
    @Builder.Default
    private Integer maxThreshold = 500;

    @Column(name = "reorder_quantity")
    @Builder.Default
    private Integer reorderQuantity = 50;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", length = 20)
    @Builder.Default
    private StockStatus stockStatus = StockStatus.OUT_OF_STOCK;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "last_restocked")
    private LocalDateTime lastRestocked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (sku == null) {
            sku = "MAT-" + System.currentTimeMillis();
        }
        stockStatus = computeStockStatus(currentStock, minimumThreshold);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        stockStatus = computeStockStatus(currentStock, minimumThreshold);
    }

    /**
     * Single source of truth for stock status — IN_STOCK / LOW_STOCK / OUT_OF_STOCK.
     * Recomputed automatically on every save via @PrePersist/@PreUpdate so the
     * persisted stockStatus column can never go stale after a stock adjustment.
     */
    public static StockStatus computeStockStatus(BigDecimal currentStock, BigDecimal minimumThreshold) {
        BigDecimal stock = currentStock != null ? currentStock : BigDecimal.ZERO;
        BigDecimal threshold = minimumThreshold != null ? minimumThreshold : BigDecimal.TEN;
        if (stock.compareTo(BigDecimal.ZERO) <= 0) {
            return StockStatus.OUT_OF_STOCK;
        }
        if (stock.compareTo(threshold) <= 0) {
            return StockStatus.LOW_STOCK;
        }
        return StockStatus.IN_STOCK;
    }
}
