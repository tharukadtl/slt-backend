package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Dp.java — Distribution Point, maps to `dps`. Third level of the
 * infrastructure hierarchy under OPMC (OPMC → Exchange → CAB → DP),
 * Stage C scaffolding. No coordinates yet — that's separate geocoding
 * work, not this stage.
 *
 * `code` is unique per Cab, not globally — real DP codes (e.g. "U009",
 * "C001") are short and heavily reused across different Cabs, confirmed
 * against the CIRCUIT.csv master-data import (2026-08-20).
 */
@Entity
@Table(name = "dps", uniqueConstraints = {
    @UniqueConstraint(name = "uk_dp_cab_code", columnNames = {"cab_id", "code"})
})
public class Dp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 30)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cab_id", nullable = false)
    private Cab cab;

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

    public Dp() {}

    public Long          getId()        { return id; }
    public String        getName()      { return name; }
    public String        getCode()      { return code; }
    public Cab           getCab()       { return cab; }
    public Boolean       getIsActive()  { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long v)          { this.id       = v; }
    public void setName(String v)      { this.name     = v; }
    public void setCode(String v)      { this.code     = v; }
    public void setCab(Cab v)          { this.cab      = v; }
    public void setIsActive(Boolean v) { this.isActive = v; }
}
