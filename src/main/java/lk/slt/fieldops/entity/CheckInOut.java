package lk.slt.fieldops.job.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * CheckInOut.java — maps to `check_in_out` table.
 * Records vehicle check-in at BOD and check-out at EOD.
 */
@Entity
@Table(name = "check_in_out")
public class CheckInOut {

    public enum CheckType { CHECK_IN, CHECK_OUT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "team_lead_id", nullable = false)
    private Long teamLeadId;

    @Column(name = "team_lead_name", length = 150)
    private String teamLeadName;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 15)
    private CheckType checkType;

    @Column(name = "check_time", nullable = false)
    private LocalDateTime checkTime;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "location_address", length = 300)
    private String locationAddress;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "vehicle_number", length = 20)
    private String vehicleNumber;

    @Column(name = "odometer_reading")
    private Integer odometerReading;

    @Column(name = "fuel_level_percent")
    private Integer fuelLevelPercent;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public CheckInOut() {}

    // Getters
    public Long          getId()             { return id; }
    public Long          getSessionId()      { return sessionId; }
    public Long          getTeamLeadId()     { return teamLeadId; }
    public String        getTeamLeadName()   { return teamLeadName; }
    public CheckType     getCheckType()      { return checkType; }
    public LocalDateTime getCheckTime()      { return checkTime; }
    public Double        getLatitude()       { return latitude; }
    public Double        getLongitude()      { return longitude; }
    public String        getLocationAddress(){ return locationAddress; }
    public Long          getVehicleId()      { return vehicleId; }
    public String        getVehicleNumber()  { return vehicleNumber; }
    public Integer       getOdometerReading(){ return odometerReading; }
    public Integer       getFuelLevelPercent(){ return fuelLevelPercent; }
    public String        getNotes()          { return notes; }
    public LocalDateTime getCreatedAt()      { return createdAt; }

    // Setters
    public void setId(Long v)                    { this.id              = v; }
    public void setSessionId(Long v)             { this.sessionId       = v; }
    public void setTeamLeadId(Long v)            { this.teamLeadId      = v; }
    public void setTeamLeadName(String v)        { this.teamLeadName    = v; }
    public void setCheckType(CheckType v)        { this.checkType       = v; }
    public void setCheckTime(LocalDateTime v)    { this.checkTime       = v; }
    public void setLatitude(Double v)            { this.latitude        = v; }
    public void setLongitude(Double v)           { this.longitude       = v; }
    public void setLocationAddress(String v)     { this.locationAddress = v; }
    public void setVehicleId(Long v)             { this.vehicleId       = v; }
    public void setVehicleNumber(String v)       { this.vehicleNumber   = v; }
    public void setOdometerReading(Integer v)    { this.odometerReading = v; }
    public void setFuelLevelPercent(Integer v)   { this.fuelLevelPercent= v; }
    public void setNotes(String v)               { this.notes           = v; }
}
