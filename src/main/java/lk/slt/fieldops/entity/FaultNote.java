package lk.slt.fieldops.fault.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * FaultNote.java — internal notes added by Admin or Team Lead on a fault.
 * These are NOT visible to the client. Used for internal communication.
 *
 * Example: "Customer called twice asking for update. Priority raised."
 */
@Entity
@Table(name = "fault_notes")
public class FaultNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fault_id", nullable = false)
    private Long faultId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String note;

    @Column(name = "added_by")
    private Long addedBy;

    @Column(name = "added_by_name", length = 150)
    private String addedByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public FaultNote() {}

    // ── Getters ───────────────────────────────────────────────────────────────
    public Long          getId()          { return id; }
    public Long          getFaultId()     { return faultId; }
    public String        getNote()        { return note; }
    public Long          getAddedBy()     { return addedBy; }
    public String        getAddedByName() { return addedByName; }
    public LocalDateTime getCreatedAt()   { return createdAt; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setId(Long v)            { this.id          = v; }
    public void setFaultId(Long v)       { this.faultId     = v; }
    public void setNote(String v)        { this.note        = v; }
    public void setAddedBy(Long v)       { this.addedBy     = v; }
    public void setAddedByName(String v) { this.addedByName = v; }
}
