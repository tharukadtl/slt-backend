package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Circuit.java — maps to `circuits`. The actual attach point a Fault
 * references: one specific Exchange+CAB+DP combination, captured via a
 * single FK to the DP (Exchange and CAB follow from DP → CAB → Exchange,
 * so they're not duplicated as separate columns here). Stage C
 * scaffolding — structure only, no assignment/workflow logic yet.
 *
 * `dp` is nullable to allow importing a Circuit row whose source DP value
 * was itself a placeholder ("DEFXXX" in CIRCUIT.csv, 12 of 349,180 rows —
 * see the master-data import, 2026-08-20) rather than inventing a fake DP.
 * `circuitCategory` mirrors CIRCUIT.csv's CIRCUITTYPE column and is nullable
 * for the same reason (35 rows have a blank CIRCUITTYPE in the source).
 */
@Entity
@Table(name = "circuits")
public class Circuit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dp_id", nullable = true)
    private Dp dp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "circuit_category_id", nullable = true)
    private CircuitCategory circuitCategory;

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

    public Circuit() {}

    public Long             getId()              { return id; }
    public String           getCode()            { return code; }
    public Dp               getDp()              { return dp; }
    public CircuitCategory  getCircuitCategory()  { return circuitCategory; }
    public Boolean          getIsActive()        { return isActive; }
    public LocalDateTime    getCreatedAt()       { return createdAt; }
    public LocalDateTime    getUpdatedAt()       { return updatedAt; }

    public void setId(Long v)                       { this.id              = v; }
    public void setCode(String v)                   { this.code            = v; }
    public void setDp(Dp v)                         { this.dp              = v; }
    public void setCircuitCategory(CircuitCategory v) { this.circuitCategory = v; }
    public void setIsActive(Boolean v)              { this.isActive        = v; }
}
