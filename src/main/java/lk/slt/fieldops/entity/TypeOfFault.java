package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * TypeOfFault.java — maps to `type_of_fault`. Top of the Cause/Material
 * hierarchy import (2026-08-21): TypeOfFault -> CauseCategory -> CauseOfFault,
 * derived from the real WFMS TYPEOFFAULT.csv/CAUSECATEGORY.csv/
 * CAUSEOFFAULT.csv export. Read-only reference data, imported once by
 * fieldops/scripts/import_cause_material_data.py — no live write path yet.
 */
@Entity
@Table(name = "type_of_fault")
public class TypeOfFault {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type_code", nullable = false, unique = true, length = 10)
    private String typeCode;

    @Column(length = 150)
    private String description;

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

    public TypeOfFault() {}

    public Long           getId()          { return id; }
    public String         getTypeCode()    { return typeCode; }
    public String         getDescription() { return description; }
    public Integer        getSortKey()     { return sortKey; }
    public LocalDateTime  getCreatedAt()   { return createdAt; }
    public LocalDateTime  getUpdatedAt()   { return updatedAt; }

    public void setId(Long v)             { this.id          = v; }
    public void setTypeCode(String v)     { this.typeCode    = v; }
    public void setDescription(String v)  { this.description = v; }
    public void setSortKey(Integer v)     { this.sortKey     = v; }
}
