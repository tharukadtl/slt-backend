package lk.slt.fieldops.inventory.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * StockTransaction.java — maps to `stock_transactions` table.
 * Records every change in stock — IN (restock) or OUT (job usage / request).
 * Full audit trail for inventory.
 */
@Entity
@Table(name = "stock_transactions")
public class StockTransaction {

    public enum TransactionType { STOCK_IN, STOCK_OUT, ADJUSTMENT }
    public enum ReferenceType   { JOB, MATERIAL_REQUEST, MANUAL_ADJUSTMENT, INITIAL_STOCK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 15)
    private TransactionType transactionType;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "stock_before", precision = 12, scale = 3)
    private BigDecimal stockBefore;

    @Column(name = "stock_after", precision = 12, scale = 3)
    private BigDecimal stockAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 20)
    private ReferenceType referenceType;

    @Column(name = "reference_id")
    private Long referenceId;           // jobId or materialRequestId

    @Column(length = 500)
    private String notes;

    @Column(name = "performed_by")
    private Long performedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public StockTransaction() {}

    // Getters
    public Long            getId()              { return id; }
    public Long            getMaterialId()      { return materialId; }
    public String          getMaterialName()    { return materialName; }
    public TransactionType getTransactionType() { return transactionType; }
    public BigDecimal      getQuantity()        { return quantity; }
    public BigDecimal      getStockBefore()     { return stockBefore; }
    public BigDecimal      getStockAfter()      { return stockAfter; }
    public ReferenceType   getReferenceType()   { return referenceType; }
    public Long            getReferenceId()     { return referenceId; }
    public String          getNotes()           { return notes; }
    public Long            getPerformedBy()     { return performedBy; }
    public LocalDateTime   getCreatedAt()       { return createdAt; }

    // Setters
    public void setId(Long v)                       { this.id              = v; }
    public void setMaterialId(Long v)               { this.materialId      = v; }
    public void setMaterialName(String v)           { this.materialName    = v; }
    public void setTransactionType(TransactionType v){ this.transactionType = v; }
    public void setQuantity(BigDecimal v)           { this.quantity        = v; }
    public void setStockBefore(BigDecimal v)        { this.stockBefore     = v; }
    public void setStockAfter(BigDecimal v)         { this.stockAfter      = v; }
    public void setReferenceType(ReferenceType v)   { this.referenceType   = v; }
    public void setReferenceId(Long v)              { this.referenceId     = v; }
    public void setNotes(String v)                  { this.notes           = v; }
    public void setPerformedBy(Long v)              { this.performedBy     = v; }
}
