package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * CauseCategory.java — maps to `cause_category`. Middle tier of the
 * Cause/Material hierarchy import (2026-08-21) — see TypeOfFault's javadoc.
 */
@Entity
@Table(name = "cause_category")
public class CauseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cause_category_code", nullable = false, unique = true, length = 10)
    private String causeCategoryCode;

    @Column(length = 150)
    private String description;

    @Column(name = "type_of_fault_id")
    private Long typeOfFaultId;

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

    public CauseCategory() {}

    public Long          getId()                { return id; }
    public String        getCauseCategoryCode()  { return causeCategoryCode; }
    public String        getDescription()        { return description; }
    public Long          getTypeOfFaultId()       { return typeOfFaultId; }
    public Integer       getSortKey()             { return sortKey; }
    public LocalDateTime getCreatedAt()           { return createdAt; }
    public LocalDateTime getUpdatedAt()           { return updatedAt; }

    public void setId(Long v)                 { this.id                = v; }
    public void setCauseCategoryCode(String v){ this.causeCategoryCode = v; }
    public void setDescription(String v)      { this.description       = v; }
    public void setTypeOfFaultId(Long v)      { this.typeOfFaultId     = v; }
    public void setSortKey(Integer v)         { this.sortKey           = v; }
}
