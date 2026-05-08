package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * CreateVehicleRequest — body for POST /api/vehicles
 * {
 *   "registrationNumber": "WP CAE-3456",
 *   "make":               "Toyota",
 *   "model":              "HiAce",
 *   "modelYear":          2020,
 *   "vehicleType":        "VAN",
 *   "fuelType":           "DIESEL",
 *   "branchId":           1,
 *   "insuranceExpiry":    "2026-06-30",
 *   "revenueLicenseExpiry": "2026-03-15"
 * }
 */
public class CreateVehicleRequest {

    @NotBlank(message = "Registration number is required")
    private String registrationNumber;

    @NotBlank(message = "Make is required")
    private String make;

    @NotBlank(message = "Model is required")
    private String model;

    private Integer modelYear;
    private String  vehicleType;   // VAN / CAR / MOTORCYCLE / TRUCK / OTHER
    private String  fuelType;      // PETROL / DIESEL / ELECTRIC / HYBRID

    private Long branchId;

    private Integer   currentOdometer = 0;
    private LocalDate insuranceExpiry;
    private LocalDate revenueLicenseExpiry;
    private LocalDate emissionTestExpiry;
    private String    insuranceCompany;
    private String    insurancePolicyNumber;
    private String    notes;

    public CreateVehicleRequest() {}

    public String    getRegistrationNumber()    { return registrationNumber; }
    public String    getMake()                  { return make; }
    public String    getModel()                 { return model; }
    public Integer   getModelYear()             { return modelYear; }
    public String    getVehicleType()           { return vehicleType; }
    public String    getFuelType()              { return fuelType; }
    public Long      getBranchId()              { return branchId; }
    public Integer   getCurrentOdometer()       { return currentOdometer; }
    public LocalDate getInsuranceExpiry()       { return insuranceExpiry; }
    public LocalDate getRevenueLicenseExpiry()  { return revenueLicenseExpiry; }
    public LocalDate getEmissionTestExpiry()    { return emissionTestExpiry; }
    public String    getInsuranceCompany()      { return insuranceCompany; }
    public String    getInsurancePolicyNumber() { return insurancePolicyNumber; }
    public String    getNotes()                 { return notes; }

    public void setRegistrationNumber(String v)    { this.registrationNumber    = v; }
    public void setMake(String v)                  { this.make                  = v; }
    public void setModel(String v)                 { this.model                 = v; }
    public void setModelYear(Integer v)            { this.modelYear             = v; }
    public void setVehicleType(String v)           { this.vehicleType           = v; }
    public void setFuelType(String v)              { this.fuelType              = v; }
    public void setBranchId(Long v)                { this.branchId              = v; }
    public void setCurrentOdometer(Integer v)      { this.currentOdometer       = v; }
    public void setInsuranceExpiry(LocalDate v)    { this.insuranceExpiry       = v; }
    public void setRevenueLicenseExpiry(LocalDate v){ this.revenueLicenseExpiry = v; }
    public void setEmissionTestExpiry(LocalDate v) { this.emissionTestExpiry    = v; }
    public void setInsuranceCompany(String v)      { this.insuranceCompany      = v; }
    public void setInsurancePolicyNumber(String v) { this.insurancePolicyNumber = v; }
    public void setNotes(String v)                 { this.notes                 = v; }
}
