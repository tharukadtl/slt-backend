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
                @Index(name = "idx_material_branch", columnList = "branch_id"),
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

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "sku", unique = true, length = 50)
    private String sku;

    @Column(name = "description", length = 500)
    private String description;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
