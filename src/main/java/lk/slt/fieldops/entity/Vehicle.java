package lk.slt.fieldops.vehicle.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Vehicle.java — maps to `vehicles` table.
 *
 * Key alert rules (FR-42):
 *   insurance_expiry < 30 days away       → ALERT
 *   revenue_license_expiry < 30 days away  → ALERT
 */
@Entity
@Table(name = "vehicles")
public class Vehicle {

    public enum VehicleStatus { ACTIVE, INACTIVE, UNDER_MAINTENANCE }
    public enum FuelType { PETROL, DIESEL, ELECTRIC, HYBRID }
    public enum VehicleType { VAN, CAR, MOTORCYCLE, TRUCK, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "registration_number", nullable = false, unique = true, length = 20)
    private String registrationNumber;     // e.g. "WP CAE-3456"

    @Column(nullable = false, length = 100)
    private String make;                   // e.g. "Toyota"

    @Column(nullable = false, length = 100)
    private String model;                  // e.g. "HiAce"

    @Column(name = "model_year")
    private Integer modelYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 20)
    private VehicleType vehicleType = VehicleType.VAN;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuel_type", length = 12)
    private FuelType fuelType = FuelType.PETROL;

    @Column(name = "branch_id")
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleStatus status = VehicleStatus.ACTIVE;

    @Column(name = "current_odometer")
    private Integer currentOdometer = 0;

    // ── Document Expiry Dates (ALERT if < 30 days) ────────────────────────────
    @Column(name = "insurance_expiry")
    private LocalDate insuranceExpiry;

    @Column(name = "revenue_license_expiry")
    private LocalDate revenueLicenseExpiry;

    @Column(name = "emission_test_expiry")
    private LocalDate emissionTestExpiry;

    // ── Insurance Details ─────────────────────────────────────────────────────
    @Column(name = "insurance_company", length = 150)
    private String insuranceCompany;

    @Column(name = "insurance_policy_number", length = 100)
    private String insurancePolicyNumber;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

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

    public Vehicle() {}

    // Getters
    public Long          getId()                    { return id; }
    public String        getRegistrationNumber()    { return registrationNumber; }
    public String        getMake()                  { return make; }
    public String        getModel()                 { return model; }
    public Integer       getModelYear()             { return modelYear; }
    public VehicleType   getVehicleType()           { return vehicleType; }
    public FuelType      getFuelType()              { return fuelType; }
    public Long          getBranchId()              { return branchId; }
    public VehicleStatus getStatus()                { return status; }
    public Integer       getCurrentOdometer()       { return currentOdometer; }
    public LocalDate     getInsuranceExpiry()       { return insuranceExpiry; }
    public LocalDate     getRevenueLicenseExpiry()  { return revenueLicenseExpiry; }
    public LocalDate     getEmissionTestExpiry()    { return emissionTestExpiry; }
    public String        getInsuranceCompany()      { return insuranceCompany; }
    public String        getInsurancePolicyNumber() { return insurancePolicyNumber; }
    public String        getNotes()                 { return notes; }
    public LocalDateTime getCreatedAt()             { return createdAt; }
    public LocalDateTime getUpdatedAt()             { return updatedAt; }

    // Setters
    public void setId(Long v)                       { this.id                    = v; }
    public void setRegistrationNumber(String v)     { this.registrationNumber    = v; }
    public void setMake(String v)                   { this.make                  = v; }
    public void setModel(String v)                  { this.model                 = v; }
    public void setModelYear(Integer v)             { this.modelYear             = v; }
    public void setVehicleType(VehicleType v)       { this.vehicleType           = v; }
    public void setFuelType(FuelType v)             { this.fuelType              = v; }
    public void setBranchId(Long v)                 { this.branchId              = v; }
    public void setStatus(VehicleStatus v)          { this.status                = v; }
    public void setCurrentOdometer(Integer v)       { this.currentOdometer       = v; }
    public void setInsuranceExpiry(LocalDate v)     { this.insuranceExpiry       = v; }
    public void setRevenueLicenseExpiry(LocalDate v){ this.revenueLicenseExpiry  = v; }
    public void setEmissionTestExpiry(LocalDate v)  { this.emissionTestExpiry    = v; }
    public void setInsuranceCompany(String v)       { this.insuranceCompany      = v; }
    public void setInsurancePolicyNumber(String v)  { this.insurancePolicyNumber = v; }
    public void setNotes(String v)                  { this.notes                 = v; }
}
