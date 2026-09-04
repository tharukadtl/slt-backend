package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Cab.java — Cabinet, maps to `cabs`. Second level of the infrastructure
 * hierarchy under OPMC (OPMC → Exchange → CAB → DP), Stage C scaffolding.
 * No coordinates yet — that's separate geocoding work, not this stage.
 *
 * `code` is unique per Exchange, not globally (real CAB naming reuses the
 * same code under different Exchanges — confirmed against the CIRCUIT.csv
 * master-data import, 2026-08-20). `exchange` is nullable to allow importing
 * a Cab whose source EXCHANGECODE has no matching Exchange row (a logged,
 * open master-data-export gap — see QA_Compliance_Consolidated_Report.md —
 * rather than silently dropping the Cab or inventing a placeholder Exchange).
 */
@Entity
@Table(name = "cabs", uniqueConstraints = {
    @UniqueConstraint(name = "uk_cab_exchange_code", columnNames = {"exchange_id", "code"})
})
public class Cab {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 30)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exchange_id", nullable = true)
    private Exchange exchange;

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

    public Cab() {}

    public Long          getId()        { return id; }
    public String        getName()      { return name; }
    public String        getCode()      { return code; }
    public Exchange      getExchange()  { return exchange; }
    public Boolean       getIsActive()  { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long v)          { this.id       = v; }
    public void setName(String v)      { this.name     = v; }
    public void setCode(String v)      { this.code     = v; }
    public void setExchange(Exchange v){ this.exchange = v; }
    public void setIsActive(Boolean v) { this.isActive = v; }
}
