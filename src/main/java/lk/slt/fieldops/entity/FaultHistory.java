package lk.slt.fieldops.fault.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * FaultHistory.java — records EVERY status change on a fault.
 *
 * Every time a fault status changes, a new row is inserted here.
 * This gives a complete timeline: who changed what, when, and why.
 *
 * Example timeline for fault F-2026-001:
 *   REPORTED    → Client submitted (2026-02-14 10:00)
 *   ASSIGNED    → Admin assigned to TL Amal (2026-02-14 10:30)
 *   IN_PROGRESS → Technician started work (2026-02-14 14:00)
 *   COMPLETED   → Work done (2026-02-14 16:30)
 */
@Entity
@Table(name = "fault_history")
public class FaultHistory {

    public enum ChangedByRole {
        SUPER_ADMIN, ADMIN, TEAM_LEAD, TECHNICIAN, CLIENT, SYSTEM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fault_id", nullable = false)
    private Long faultId;

    @Column(name = "fault_number", nullable = false, length = 30)
    private String faultNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 20)
    private Fault.FaultStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private Fault.FaultStatus newStatus;

    @Column(name = "changed_by_id")
    private Long changedById;

    @Column(name = "changed_by_name", length = 150)
    private String changedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_role", length = 20)
    private ChangedByRole changedByRole;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "is_system_action", nullable = false)
    private Boolean isSystemAction = false;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PrePersist
    protected void onCreate() {
        this.changedAt = LocalDateTime.now();
    }

    public FaultHistory() {}

    // ── Getters ───────────────────────────────────────────────────────────────
    public Long              getId()            { return id; }
    public Long              getFaultId()       { return faultId; }
    public String            getFaultNumber()   { return faultNumber; }
    public Fault.FaultStatus getOldStatus()     { return oldStatus; }
    public Fault.FaultStatus getNewStatus()     { return newStatus; }
    public Long              getChangedById()   { return changedById; }
    public String            getChangedByName() { return changedByName; }
    public ChangedByRole     getChangedByRole() { return changedByRole; }
    public String            getReason()        { return reason; }
    public Boolean           getIsSystemAction(){ return isSystemAction; }
    public LocalDateTime     getChangedAt()     { return changedAt; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setId(Long v)                        { this.id            = v; }
    public void setFaultId(Long v)                   { this.faultId       = v; }
    public void setFaultNumber(String v)             { this.faultNumber   = v; }
    public void setOldStatus(Fault.FaultStatus v)    { this.oldStatus     = v; }
    public void setNewStatus(Fault.FaultStatus v)    { this.newStatus     = v; }
    public void setChangedById(Long v)               { this.changedById   = v; }
    public void setChangedByName(String v)           { this.changedByName = v; }
    public void setChangedByRole(ChangedByRole v)    { this.changedByRole = v; }
    public void setReason(String v)                  { this.reason        = v; }
    public void setIsSystemAction(Boolean v)         { this.isSystemAction= v; }
}
