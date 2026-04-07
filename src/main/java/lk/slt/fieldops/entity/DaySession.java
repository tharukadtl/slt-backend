package lk.slt.fieldops.job.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DaySession.java — maps to `day_sessions` table.
 *
 * Created when Team Lead does BOD (Beginning of Day).
 * Closed when Team Lead does EOD (End of Day).
 *
 * One session per Team Lead per day.
 * UNIQUE KEY: (team_lead_id, session_date)
 */
@Entity
@Table(name = "day_sessions")
public class DaySession {

    public enum SessionStatus { ACTIVE, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_lead_id", nullable = false)
    private Long teamLeadId;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SessionStatus status = SessionStatus.ACTIVE;

    // ── BOD fields ────────────────────────────────────────────────────────────
    @Column(name = "bod_time")
    private LocalDateTime bodTime;

    @Column(name = "bod_latitude")
    private Double bodLatitude;

    @Column(name = "bod_longitude")
    private Double bodLongitude;

    @Column(name = "bod_vehicle_id")
    private Long bodVehicleId;

    @Column(name = "bod_odometer")
    private Integer bodOdometer;

    // ── EOD fields ────────────────────────────────────────────────────────────
    @Column(name = "eod_time")
    private LocalDateTime eodTime;

    @Column(name = "eod_odometer")
    private Integer eodOdometer;

    @Column(name = "eod_notes", columnDefinition = "TEXT")
    private String eodNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt   = LocalDateTime.now();
        this.updatedAt   = LocalDateTime.now();
        this.sessionDate = LocalDate.now();
    }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public DaySession() {}

    // Getters
    public Long          getId()            { return id; }
    public Long          getTeamLeadId()    { return teamLeadId; }
    public LocalDate     getSessionDate()   { return sessionDate; }
    public SessionStatus getStatus()        { return status; }
    public LocalDateTime getBodTime()       { return bodTime; }
    public Double        getBodLatitude()   { return bodLatitude; }
    public Double        getBodLongitude()  { return bodLongitude; }
    public Long          getBodVehicleId()  { return bodVehicleId; }
    public Integer       getBodOdometer()   { return bodOdometer; }
    public LocalDateTime getEodTime()       { return eodTime; }
    public Integer       getEodOdometer()   { return eodOdometer; }
    public String        getEodNotes()      { return eodNotes; }
    public LocalDateTime getCreatedAt()     { return createdAt; }
    public LocalDateTime getUpdatedAt()     { return updatedAt; }

    // Setters
    public void setId(Long v)                   { this.id           = v; }
    public void setTeamLeadId(Long v)           { this.teamLeadId   = v; }
    public void setSessionDate(LocalDate v)     { this.sessionDate  = v; }
    public void setStatus(SessionStatus v)      { this.status       = v; }
    public void setBodTime(LocalDateTime v)     { this.bodTime      = v; }
    public void setBodLatitude(Double v)        { this.bodLatitude  = v; }
    public void setBodLongitude(Double v)       { this.bodLongitude = v; }
    public void setBodVehicleId(Long v)         { this.bodVehicleId = v; }
    public void setBodOdometer(Integer v)       { this.bodOdometer  = v; }
    public void setEodTime(LocalDateTime v)     { this.eodTime      = v; }
    public void setEodOdometer(Integer v)       { this.eodOdometer  = v; }
    public void setEodNotes(String v)           { this.eodNotes     = v; }
}
