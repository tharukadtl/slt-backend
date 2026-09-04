package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ConfirmedResourcePlan.java — maps to `confirmed_resource_plans` table.
 *
 * FR-33 Stage 3b: the Admin's confirmed Predictive Resource Planning (SRS
 * 5.6.8) suggestions, persisted per OPMC/date/shift so the Team Lead's BOD
 * screen (day_sessions) can read a starting point instead of blank fields.
 *
 * The AI module's resource-plan hotspots are keyed by a K-Means zone (an
 * ephemeral cluster index + nearest-district label, recomputed on every
 * call — never persisted there). opmcId here is NOT derived automatically
 * from that zone; the Admin explicitly maps each zone to an OPMC at confirm
 * time (frontend-admin's Resource Planning panel), since no reliable
 * zone-to-OPMC correspondence exists otherwise.
 *
 * Re-confirming the same (opmc, date, shift) replaces the previous row
 * (and its materials) — this is a starting point the Admin can re-review and
 * resubmit, not an append-only log.
 *
 * ONE row per (opmc_id, plan_date, shift).
 */
@Entity
@Table(name = "confirmed_resource_plans", uniqueConstraints = @UniqueConstraint(
        name = "uk_confirmed_plan_opmc_date_shift",
        columnNames = {"opmc_id", "plan_date", "shift"}))
public class ConfirmedResourcePlan {

    public enum Shift { MORNING, AFTERNOON, EVENING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "opmc_id", nullable = false)
    private Long opmcId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Shift shift;

    // ── Informational — the AI module's zone at confirm time, not re-used for lookup ──
    @Column(name = "zone_id")
    private Integer zoneId;

    @Column(name = "zone_name", length = 100)
    private String zoneName;

    @Column(name = "predicted_fault_count")
    private Double predictedFaultCount;

    // ── The suggestions the Team Lead's BOD screen pre-populates from ──────────
    @Column(name = "suggested_technicians")
    private Integer suggestedTechnicians;

    @Column(name = "suggested_vehicles")
    private Integer suggestedVehicles;

    @Column(name = "confirmed_by", nullable = false)
    private Long confirmedBy;

    @Column(name = "confirmed_at", nullable = false)
    private LocalDateTime confirmedAt;

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

    public ConfirmedResourcePlan() {}

    // Getters
    public Long          getId()                   { return id; }
    public Long          getOpmcId()               { return opmcId; }
    public LocalDate     getPlanDate()               { return planDate; }
    public Shift          getShift()                { return shift; }
    public Integer       getZoneId()                { return zoneId; }
    public String        getZoneName()              { return zoneName; }
    public Double        getPredictedFaultCount()   { return predictedFaultCount; }
    public Integer       getSuggestedTechnicians()  { return suggestedTechnicians; }
    public Integer       getSuggestedVehicles()     { return suggestedVehicles; }
    public Long          getConfirmedBy()           { return confirmedBy; }
    public LocalDateTime getConfirmedAt()           { return confirmedAt; }
    public LocalDateTime getCreatedAt()             { return createdAt; }
    public LocalDateTime getUpdatedAt()             { return updatedAt; }

    // Setters
    public void setId(Long v)                       { this.id                   = v; }
    public void setOpmcId(Long v)                   { this.opmcId               = v; }
    public void setPlanDate(LocalDate v)            { this.planDate             = v; }
    public void setShift(Shift v)                   { this.shift                = v; }
    public void setZoneId(Integer v)                { this.zoneId               = v; }
    public void setZoneName(String v)               { this.zoneName             = v; }
    public void setPredictedFaultCount(Double v)    { this.predictedFaultCount  = v; }
    public void setSuggestedTechnicians(Integer v)  { this.suggestedTechnicians = v; }
    public void setSuggestedVehicles(Integer v)     { this.suggestedVehicles    = v; }
    public void setConfirmedBy(Long v)              { this.confirmedBy          = v; }
    public void setConfirmedAt(LocalDateTime v)     { this.confirmedAt          = v; }
}
