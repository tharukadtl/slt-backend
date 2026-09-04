package lk.slt.fieldops.entity;

/**
 * ConfirmedResourcePlanMaterial.java — maps to `confirmed_resource_plan_materials`.
 *
 * One row per material suggested for a ConfirmedResourcePlan. materialId
 * corresponds to the same `materials` table the AI module reads directly
 * (both sides share slt_fieldops_db) — materialName/unit are a snapshot at
 * confirm time so a later rename/removal of the material doesn't corrupt
 * historical confirmed plans.
 */
import jakarta.persistence.*;

@Entity
@Table(name = "confirmed_resource_plan_materials")
public class ConfirmedResourcePlanMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "confirmed_resource_plan_id", nullable = false)
    private Long confirmedResourcePlanId;

    @Column(name = "material_id")
    private Long materialId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @Column(name = "suggested_quantity")
    private Double suggestedQuantity;

    @Column(name = "unit", length = 20)
    private String unit;

    public ConfirmedResourcePlanMaterial() {}

    // Getters
    public Long   getId()                      { return id; }
    public Long   getConfirmedResourcePlanId() { return confirmedResourcePlanId; }
    public Long   getMaterialId()              { return materialId; }
    public String getMaterialName()            { return materialName; }
    public Double getSuggestedQuantity()       { return suggestedQuantity; }
    public String getUnit()                    { return unit; }

    // Setters
    public void setId(Long v)                      { this.id                      = v; }
    public void setConfirmedResourcePlanId(Long v) { this.confirmedResourcePlanId = v; }
    public void setMaterialId(Long v)              { this.materialId              = v; }
    public void setMaterialName(String v)          { this.materialName            = v; }
    public void setSuggestedQuantity(Double v)     { this.suggestedQuantity       = v; }
    public void setUnit(String v)                  { this.unit                    = v; }
}
