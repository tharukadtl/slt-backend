package lk.slt.fieldops.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * CreateMaterialRequest — body for POST /api/inventory/materials
 * {
 *   "name":             "Cat6 Ethernet Cable",
 *   "sku":              "CAB-CAT6-001",
 *   "categoryId":       2,
 *   "branchId":         1,
 *   "chargeType":       "FOC",
 *   "unitPrice":        0,
 *   "unit":             "meters",
 *   "currentStock":     500,
 *   "minimumThreshold": 50
 * }
 */
public class CreateMaterialRequest {

    @NotBlank(message = "Material name is required")
    private String name;

    @NotBlank(message = "SKU is required")
    private String sku;

    private String description;

    @NotNull(message = "Category ID is required")
    private Long categoryId;

    @NotNull(message = "Branch ID is required")
    private Long branchId;

    private String chargeType = "FOC";    // FOC or CHARGEABLE

    private BigDecimal unitPrice = BigDecimal.ZERO;

    private String unit;

    private BigDecimal currentStock = BigDecimal.ZERO;

    private BigDecimal minimumThreshold = BigDecimal.ZERO;

    public CreateMaterialRequest() {}

    public String     getName()             { return name; }
    public String     getSku()              { return sku; }
    public String     getDescription()      { return description; }
    public Long       getCategoryId()       { return categoryId; }
    public Long       getBranchId()         { return branchId; }
    public String     getChargeType()       { return chargeType; }
    public BigDecimal getUnitPrice()        { return unitPrice; }
    public String     getUnit()             { return unit; }
    public BigDecimal getCurrentStock()     { return currentStock; }
    public BigDecimal getMinimumThreshold() { return minimumThreshold; }

    public void setName(String v)              { this.name             = v; }
    public void setSku(String v)               { this.sku              = v; }
    public void setDescription(String v)       { this.description      = v; }
    public void setCategoryId(Long v)          { this.categoryId       = v; }
    public void setBranchId(Long v)            { this.branchId         = v; }
    public void setChargeType(String v)        { this.chargeType       = v; }
    public void setUnitPrice(BigDecimal v)     { this.unitPrice        = v; }
    public void setUnit(String v)              { this.unit             = v; }
    public void setCurrentStock(BigDecimal v)  { this.currentStock     = v; }
    public void setMinimumThreshold(BigDecimal v){ this.minimumThreshold = v; }
}
