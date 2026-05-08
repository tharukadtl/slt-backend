package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * VehicleAssignment.java — maps to `vehicle_assignments` table.
 *
 * One vehicle per Team Lead per day (unique constraint).
 * Distance travelled is auto-calculated: eod_odometer - bod_odometer.
 */
@Entity
@Table(name = "vehicle_assignments")
public class VehicleAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "team_lead_id", nullable = false)
    private Long teamLeadId;

    @Column(name = "team_lead_name", length = 150)
    private String teamLeadName;

    @Column(name = "assignment_date", nullable = false)
    private LocalDate assignmentDate;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "bod_odometer")
    private Integer bodOdometer;

    @Column(name = "eod_odometer")
    private Integer eodOdometer;

    /** Auto-calculated: eod_odometer - bod_odometer */
    @Column(name = "distance_km")
    private Integer distanceKm;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt      = LocalDateTime.now();
        this.assignmentDate = LocalDate.now();
    }

    public VehicleAssignment() {}

    // Getters
    public Long      getId()            { return id; }
    public Long      getVehicleId()     { return vehicleId; }
    public Long      getTeamLeadId()    { return teamLeadId; }
    public String    getTeamLeadName()  { return teamLeadName; }
    public LocalDate getAssignmentDate(){ return assignmentDate; }
    public Long      getSessionId()     { return sessionId; }
    public Integer   getBodOdometer()   { return bodOdometer; }
    public Integer   getEodOdometer()   { return eodOdometer; }
    public Integer   getDistanceKm()    { return distanceKm; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // Setters
    public void setId(Long v)                 { this.id             = v; }
    public void setVehicleId(Long v)          { this.vehicleId      = v; }
    public void setTeamLeadId(Long v)         { this.teamLeadId     = v; }
    public void setTeamLeadName(String v)     { this.teamLeadName   = v; }
    public void setAssignmentDate(LocalDate v){ this.assignmentDate = v; }
    public void setSessionId(Long v)          { this.sessionId      = v; }
    public void setBodOdometer(Integer v)     { this.bodOdometer    = v; }
    public void setEodOdometer(Integer v)     { this.eodOdometer    = v; }
    public void setDistanceKm(Integer v)      { this.distanceKm     = v; }
}
