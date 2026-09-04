package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * MaterialCause.java — maps to `material_cause`. Reference/suggestion
 * catalog: which Materials are typically associated with which CauseOfFault
 * (2026-08-21 import, MATERIALCAUSE_.csv). causeId/materialId are REAL
 * generated ids (cause_of_fault.id / materials.id) resolved by
 * fieldops/scripts/import_cause_material_data.py's row-position logic at
 * import time — MATERIALCAUSE_.csv's own CAUSECODE/MATERIALCODE columns are
 * NOT usable as foreign-key values by themselves (they are 1-indexed row
 * positions into CAUSEOFFAULT.csv/MATERIAL.csv's original export order, not
 * the alphanumeric business-key codes those two files use for the same
 * column names — confirmed by direct investigation, 0% direct-value match,
 * 100% row-position match across all 62 source rows). See the import
 * script's own large comment for the full explanation — this entity itself
 * only ever stores the resolved, real ids.
 */
@Entity
@Table(name = "material_cause")
public class MaterialCause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cause_id", nullable = false)
    private Long causeId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "sort_key")
    private Integer sortKey;

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

    public MaterialCause() {}

    public Long          getId()         { return id; }
    public Long          getCauseId()    { return causeId; }
    public Long          getMaterialId() { return materialId; }
    public Integer       getSortKey()    { return sortKey; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public LocalDateTime getUpdatedAt()  { return updatedAt; }

    public void setId(Long v)         { this.id         = v; }
    public void setCauseId(Long v)    { this.causeId    = v; }
    public void setMaterialId(Long v) { this.materialId = v; }
    public void setSortKey(Integer v) { this.sortKey    = v; }
}
