package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Exchange.java — maps to `exchanges`. Top level of the infrastructure
 * hierarchy under OPMC (OPMC → Exchange → CAB → DP), Stage C scaffolding.
 *
 * H1a: coordinates added — geocoded from the real Exchange master data
 * (`fieldops/scripts/geocode_master_data.py`, 2026-08-20), nullable since
 * not every real Exchange name resolved with confidence (see that script's
 * report and QA_Compliance_Consolidated_Report.md's H1a entry).
 */
@Entity
@Table(name = "exchanges")
public class Exchange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opmc_id", nullable = false)
    private Opmc opmc;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

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

    public Exchange() {}

    public Long          getId()        { return id; }
    public String        getName()      { return name; }
    public String        getCode()      { return code; }
    public Opmc          getOpmc()      { return opmc; }
    public Double        getLatitude()  { return latitude; }
    public Double        getLongitude() { return longitude; }
    public Boolean       getIsActive()  { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long v)          { this.id        = v; }
    public void setName(String v)      { this.name      = v; }
    public void setCode(String v)      { this.code      = v; }
    public void setOpmc(Opmc v)        { this.opmc      = v; }
    public void setLatitude(Double v)  { this.latitude  = v; }
    public void setLongitude(Double v) { this.longitude = v; }
    public void setIsActive(Boolean v) { this.isActive  = v; }
}
