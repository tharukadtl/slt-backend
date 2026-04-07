package lk.slt.fieldops.inventory.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Material.java — maps to `materials` table.
 *
 * Key fields:
 *   chargeType       → FOC (Free of Charge) or CHARGEABLE
 *   currentStock     → updated on every usage / restock
 *   minimumThreshold → when stock <= this, LOW STOCK alert fires
 */
@Entity
@Table(name = "materials")
public class Material {

    public enum ChargeType { FOC, CHARGEABLE }
    public enum StockStatus { IN_STOCK, LOW_STOCK, OUT_OF_STOCK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String sku;                   // Stock Keeping Unit (unique code)

    @Column(length = 500)
    private String description;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "branch_id")
    private Long branchId;               // which branch holds this stock

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 12)
    private ChargeType chargeType = ChargeType.FOC;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(length = 30)
    private String unit;                  // e.g. "meters", "pieces", "rolls"

    @Column(name = "current_stock", nullable = false, precision = 12, scale = 3)
    private BigDecimal currentStock = BigDecimal.ZERO;

    @Column(name = "minimum_threshold", nullable = false, precision = 12, scale = 3)
    private BigDecimal minimumThreshold = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", nullable = false, length = 15)
    private StockStatus stockStatus = StockStatus.IN_STOCK;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public Material() {}

    // Getters
    public Long        getId()              { return id; }
    public String      getName()            { return name; }
    public String      getSku()             { return sku; }
    public String      getDescription()     { return description; }
    public Long        getCategoryId()      { return categoryId; }
    public Long        getBranchId()        { return branchId; }
    public ChargeType  getChargeType()      { return chargeType; }
    public BigDecimal  getUnitPrice()       { return unitPrice; }
    public String      getUnit()            { return unit; }
    public BigDecimal  getCurrentStock()    { return currentStock; }
    public BigDecimal  getMinimumThreshold(){ return minimumThreshold; }
    public StockStatus getStockStatus()     { return stockStatus; }
    public Boolean     getIsActive()        { return isActive; }
    public LocalDateTime getCreatedAt()     { return createdAt; }
    public LocalDateTime getUpdatedAt()     { return updatedAt; }

    // Setters
    public void setId(Long v)                   { this.id               = v; }
    public void setName(String v)               { this.name             = v; }
    public void setSku(String v)                { this.sku              = v; }
    public void setDescription(String v)        { this.description      = v; }
    public void setCategoryId(Long v)           { this.categoryId       = v; }
    public void setBranchId(Long v)             { this.branchId         = v; }
    public void setChargeType(ChargeType v)     { this.chargeType       = v; }
    public void setUnitPrice(BigDecimal v)      { this.unitPrice        = v; }
    public void setUnit(String v)               { this.unit             = v; }
    public void setCurrentStock(BigDecimal v)   { this.currentStock     = v; }
    public void setMinimumThreshold(BigDecimal v){ this.minimumThreshold = v; }
    public void setStockStatus(StockStatus v)   { this.stockStatus      = v; }
    public void setIsActive(Boolean v)          { this.isActive         = v; }
}
