package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * MaterialCategory.java — maps to `material_categories` table.
 * Supports parent-child hierarchy (e.g. Cables → Ethernet Cables).
 */
@Entity
@Table(name = "material_categories")
public class MaterialCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "parent_id")
    private Long parentId;   // null = top-level category

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public MaterialCategory() {}

    public Long          getId()          { return id; }
    public String        getName()        { return name; }
    public String        getDescription() { return description; }
    public Long          getParentId()    { return parentId; }
    public Boolean       getIsActive()    { return isActive; }
    public LocalDateTime getCreatedAt()   { return createdAt; }

    public void setId(Long v)            { this.id          = v; }
    public void setName(String v)        { this.name        = v; }
    public void setDescription(String v) { this.description = v; }
    public void setParentId(Long v)      { this.parentId    = v; }
    public void setIsActive(Boolean v)   { this.isActive    = v; }
}
