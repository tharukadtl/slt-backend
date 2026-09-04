package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * CircuitCategory.java — maps to `circuit_categories`. Independent lookup
 * for Circuit.circuitCategory (CIRCUITTYPE in the CIRCUITCATEGORY.csv source
 * master data: 1=CAB, 2=MSAN, 3=DFA). Id is NOT auto-generated — it is the
 * literal CIRCUITCATCODE value from the source, so Circuit rows can FK to it
 * directly without an extra lookup at import time.
 */
@Entity
@Table(name = "circuit_categories")
public class CircuitCategory {

    @Id
    private Integer id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

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

    public CircuitCategory() {}

    public Integer       getId()        { return id; }
    public String        getCode()      { return code; }
    public String        getName()      { return name; }
    public Boolean       getIsActive()  { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Integer v)       { this.id       = v; }
    public void setCode(String v)      { this.code     = v; }
    public void setName(String v)      { this.name     = v; }
    public void setIsActive(Boolean v) { this.isActive = v; }
}
