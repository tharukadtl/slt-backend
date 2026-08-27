package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * CauseOfFault.java — maps to `cause_of_fault`. Leaf of the Cause/Material
 * hierarchy import (2026-08-21) — see TypeOfFault's javadoc. appliesCopper/
 * appliesFtth/appliesLte mirror the source's COPPER/FTTH/LTE applicability
 * flags (e.g. "faulty rosette" applies to copper+ftth, not lte) — real
 * signal from the export, not dropped on import. Fault.causeId FKs here.
 */
@Entity
@Table(name = "cause_of_fault")
public class CauseOfFault {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cause_code", nullable = false, unique = true, length = 10)
    private String causeCode;

    @Column(length = 150)
    private String description;

    @Column(name = "cause_category_id")
    private Long causeCategoryId;

    @Column(name = "clarity_description", length = 150)
    private String clarityDescription;

    @Column(name = "applies_copper", nullable = false)
    private Boolean appliesCopper = false;

    @Column(name = "applies_ftth", nullable = false)
    private Boolean appliesFtth = false;

    @Column(name = "applies_lte", nullable = false)
    private Boolean appliesLte = false;

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

    public CauseOfFault() {}

    public Long          getId()                  { return id; }
    public String        getCauseCode()            { return causeCode; }
    public String        getDescription()          { return description; }
    public Long          getCauseCategoryId()      { return causeCategoryId; }
    public String        getClarityDescription()   { return clarityDescription; }
    public Boolean       getAppliesCopper()        { return appliesCopper; }
    public Boolean       getAppliesFtth()          { return appliesFtth; }
    public Boolean       getAppliesLte()           { return appliesLte; }
    public Integer       getSortKey()              { return sortKey; }
    public LocalDateTime getCreatedAt()            { return createdAt; }
    public LocalDateTime getUpdatedAt()            { return updatedAt; }

    public void setId(Long v)                    { this.id                  = v; }
    public void setCauseCode(String v)           { this.causeCode           = v; }
    public void setDescription(String v)         { this.description         = v; }
    public void setCauseCategoryId(Long v)       { this.causeCategoryId     = v; }
    public void setClarityDescription(String v)  { this.clarityDescription  = v; }
    public void setAppliesCopper(Boolean v)      { this.appliesCopper       = v; }
    public void setAppliesFtth(Boolean v)        { this.appliesFtth         = v; }
    public void setAppliesLte(Boolean v)         { this.appliesLte          = v; }
    public void setSortKey(Integer v)            { this.sortKey             = v; }
}
