package lk.slt.fieldops.job.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * DaySessionMember.java — maps to `day_session_members` table.
 *
 * When Team Lead does BOD, they select which technicians are
 * working today. Each selected technician gets one row here.
 *
 * KEY RULE (SRS §4.3.1, §5.1):
 *   A Technician can ONLY log in if they have a row here
 *   for today with is_active = TRUE.
 */
@Entity
@Table(name = "day_session_members")
public class DaySessionMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "technician_id", nullable = false)
    private Long technicianId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "added_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    @PrePersist
    protected void onCreate() { this.addedAt = LocalDateTime.now(); }

    public DaySessionMember() {}

    // Getters
    public Long          getId()          { return id; }
    public Long          getSessionId()   { return sessionId; }
    public Long          getTechnicianId(){ return technicianId; }
    public Boolean       getIsActive()    { return isActive; }
    public LocalDateTime getAddedAt()     { return addedAt; }

    // Setters
    public void setId(Long v)            { this.id           = v; }
    public void setSessionId(Long v)     { this.sessionId    = v; }
    public void setTechnicianId(Long v)  { this.technicianId = v; }
    public void setIsActive(Boolean v)   { this.isActive     = v; }
}
