package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_transactions",
        indexes = {
                @Index(name = "idx_stock_tx_material", columnList = "material_id"),
                @Index(name = "idx_stock_tx_type",     columnList = "transaction_type"),
                @Index(name = "idx_stock_tx_created",  columnList = "created_at"),
                @Index(name = "idx_stock_tx_by",       columnList = "performed_by")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTransaction {

    public enum TransactionType { STOCK_IN, STOCK_OUT, ADJUSTMENT, INITIAL_STOCK }
    public enum ReferenceType   { JOB, MANUAL_ADJUSTMENT, INITIAL_STOCK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @Column(name = "material_sku", length = 50)
    private String materialSku;

    @Column(name = "material_unit", length = 20)
    private String materialUnit;

    @Column(name = "performed_by")
    private Long performedBy;

    @Column(name = "performed_by_name", length = 150)
    private String performedByName;

    @Column(name = "performed_by_role", length = 30)
    private String performedByRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private TransactionType transactionType;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "stock_before", precision = 12, scale = 3)
    private BigDecimal stockBefore;

    @Column(name = "stock_after", precision = 12, scale = 3)
    private BigDecimal stockAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 30)
    private ReferenceType referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "total_cost", precision = 14, scale = 2)
    private BigDecimal totalCost;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
