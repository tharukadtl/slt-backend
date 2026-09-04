package lk.slt.fieldops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CreateOpmcRequest — what the client SENDS to create a new OPMC.
 * Formerly CreateBranchRequest — renamed as part of the OPMC restructure (Stage B).
 *
 * Used in:
 *   POST /api/opmcs       → create new OPMC
 *   PUT  /api/opmcs/{id}  → update existing OPMC (reused)
 *
 * Example request body:
 * {
 *   "name":       "SLT Colombo North",
 *   "code":       "CMB-02",
 *   "opmcType":   "LOCAL_BRANCH",
 *   "address":    "No. 50, Baseline Road, Colombo 09",
 *   "city":       "Colombo",
 *   "district":   "Colombo",
 *   "province":   "WESTERN",
 *   "phone":      "0112345678",
 *   "email":      "colombonorth@slt.lk",
 *   "latitude":   6.9344,
 *   "longitude":  79.8658
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateOpmcRequest {

    @NotBlank(message = "OPMC name is required")
    @Size(max = 150, message = "Name cannot exceed 150 characters")
    private String name;

    private String code;
    private String region;

    // Optional fields — no @NotBlank
    private String opmcType;   // REGIONAL_OFFICE / DISTRICT_CENTER / LOCAL_BRANCH

    @Size(max = 500)
    private String address;

    private String city;
    private String district;
    private String province;   // WESTERN / CENTRAL / SOUTHERN / NORTHERN / EASTERN /
                                // NORTH_WESTERN / NORTH_CENTRAL / UVA / SABARAGAMUWA
    private String postalCode;
    private String phone;

    @Email(message = "Please enter a valid email address")
    private String email;

    private Double latitude;
    private Double longitude;
    private String coverageDistricts;
    private String coverageCities;
    private String workingHoursStart;   // e.g. "08:00"
    private String workingHoursEnd;     // e.g. "17:00"
    private String workingDays;         // e.g. "MON,TUE,WED,THU,FRI"

    public CreateOpmcRequest() {}

    // Getters
    public String getName()             { return name; }
    public String getCode()             { return code; }
    public String getRegion()           { return region; }
    public String getOpmcType()         { return opmcType; }
    public String getAddress()          { return address; }
    public String getCity()             { return city; }
    public String getDistrict()         { return district; }
    public String getProvince()         { return province; }
    public String getPostalCode()       { return postalCode; }
    public String getPhone()            { return phone; }
    public String getEmail()            { return email; }
    public Double getLatitude()         { return latitude; }
    public Double getLongitude()        { return longitude; }
    public String getCoverageDistricts(){ return coverageDistricts; }
    public String getCoverageCities()   { return coverageCities; }
    public String getWorkingHoursStart(){ return workingHoursStart; }
    public String getWorkingHoursEnd()  { return workingHoursEnd; }
    public String getWorkingDays()      { return workingDays; }

    // Setters
    public void setName(String v)              { this.name             = v; }
    public void setCode(String v)              { this.code             = v; }
    public void setRegion(String v)            { this.region           = v; }
    public void setOpmcType(String v)          { this.opmcType         = v; }
    public void setAddress(String v)           { this.address          = v; }
    public void setCity(String v)              { this.city             = v; }
    public void setDistrict(String v)          { this.district         = v; }
    public void setProvince(String v)          { this.province         = v; }
    public void setPostalCode(String v)        { this.postalCode       = v; }
    public void setPhone(String v)             { this.phone            = v; }
    public void setEmail(String v)             { this.email            = v; }
    public void setLatitude(Double v)          { this.latitude         = v; }
    public void setLongitude(Double v)         { this.longitude        = v; }
    public void setCoverageDistricts(String v) { this.coverageDistricts= v; }
    public void setCoverageCities(String v)    { this.coverageCities   = v; }
    public void setWorkingHoursStart(String v) { this.workingHoursStart= v; }
    public void setWorkingHoursEnd(String v)   { this.workingHoursEnd  = v; }
    public void setWorkingDays(String v)       { this.workingDays      = v; }
}
