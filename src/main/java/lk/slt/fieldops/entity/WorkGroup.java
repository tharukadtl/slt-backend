package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * WorkGroup.java — maps to the `work_groups` table.
 *
 * Stage C scaffolding: belongs to one OPMC, has exactly one Team Lead
 * (assignment-workflow logic that uses this is a later stage — this is
 * structure only). Technician membership is the reverse side of
 * {@code User.workgroup}, not a collection here.
 */
// RES-020 — one Team Lead per Work Group (NULLs excluded from a MySQL unique
// index, so any number of Work Groups may still have no Team Lead assigned).
@Entity
@Table(name = "work_groups",
        uniqueConstraints = @UniqueConstraint(name = "uk_work_group_team_lead", columnNames = "team_lead_id"))
public class WorkGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opmc_id", nullable = false)
    private Opmc opmc;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_lead_id")
    private User teamLead;

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

    public WorkGroup() {}

    public Long          getId()        { return id; }
    public String        getName()      { return name; }
    public Opmc          getOpmc()      { return opmc; }
    public User          getTeamLead()  { return teamLead; }
    public Boolean       getIsActive()  { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long v)              { this.id       = v; }
    public void setName(String v)          { this.name     = v; }
    public void setOpmc(Opmc v)            { this.opmc     = v; }
    public void setTeamLead(User v)        { this.teamLead = v; }
    public void setIsActive(Boolean v)     { this.isActive = v; }
}
