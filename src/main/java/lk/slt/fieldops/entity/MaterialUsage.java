package lk.slt.fieldops.job.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MaterialUsage.java — maps to `material_usage` table.
 * Technician logs which materials were used in a job.
 * This drives the payment calculation (FOC vs CHARGEABLE).
 */
@Entity
@Table(name = "material_usage")
public class MaterialUsage {

    public enum ChargeType { FOC, CHARGEABLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "job_number", length = 30)
    private String jobNumber;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @Column(name = "material_sku", length = 100)
    private String materialSku;

    @Column(name = "quantity_used", nullable = false, precision = 10, scale = 3)
    private BigDecimal quantityUsed;

    @Column(length = 30)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 12)
    private ChargeType chargeType = ChargeType.FOC;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "justification", length = 500)
    private String justification;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public MaterialUsage() {}

    // Getters
    public Long       getId()           { return id; }
    public Long       getJobId()        { return jobId; }
    public String     getJobNumber()    { return jobNumber; }
    public Long       getMaterialId()   { return materialId; }
    public String     getMaterialName() { return materialName; }
    public String     getMaterialSku()  { return materialSku; }
    public BigDecimal getQuantityUsed() { return quantityUsed; }
    public String     getUnit()         { return unit; }
    public ChargeType getChargeType()   { return chargeType; }
    public BigDecimal getUnitPrice()    { return unitPrice; }
    public String     getJustification(){ return justification; }
    public Long       getRecordedBy()   { return recordedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // Setters
    public void setId(Long v)                 { this.id           = v; }
    public void setJobId(Long v)              { this.jobId        = v; }
    public void setJobNumber(String v)        { this.jobNumber    = v; }
    public void setMaterialId(Long v)         { this.materialId   = v; }
    public void setMaterialName(String v)     { this.materialName = v; }
    public void setMaterialSku(String v)      { this.materialSku  = v; }
    public void setQuantityUsed(BigDecimal v) { this.quantityUsed = v; }
    public void setUnit(String v)             { this.unit         = v; }
    public void setChargeType(ChargeType v)   { this.chargeType   = v; }
    public void setUnitPrice(BigDecimal v)    { this.unitPrice    = v; }
    public void setJustification(String v)    { this.justification= v; }
    public void setRecordedBy(Long v)         { this.recordedBy   = v; }
}
