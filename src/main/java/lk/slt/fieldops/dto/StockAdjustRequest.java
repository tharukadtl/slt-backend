package lk.slt.fieldops.inventory.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * StockAdjustRequest — manual stock IN/OUT/ADJUSTMENT.
 * POST /api/inventory/materials/{id}/stock
 * {
 *   "transactionType": "STOCK_IN",
 *   "quantity":        100,
 *   "notes":           "Monthly restock from warehouse"
 * }
 */
public class StockAdjustRequest {

    @NotNull(message = "Transaction type is required")
    private String transactionType;   // STOCK_IN / STOCK_OUT / ADJUSTMENT

    @NotNull(message = "Quantity is required")
    private BigDecimal quantity;

    private String notes;

    public StockAdjustRequest() {}

    public String     getTransactionType() { return transactionType; }
    public BigDecimal getQuantity()        { return quantity; }
    public String     getNotes()           { return notes; }

    public void setTransactionType(String v)   { this.transactionType = v; }
    public void setQuantity(BigDecimal v)      { this.quantity        = v; }
    public void setNotes(String v)             { this.notes           = v; }
}
