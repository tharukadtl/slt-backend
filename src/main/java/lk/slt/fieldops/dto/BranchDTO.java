package lk.slt.fieldops.branch.dto;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * BranchDTO — what we SEND BACK to the client in API responses.
 *
 * RULE: Never return the Branch entity directly from the controller.
 *       Always convert it to a DTO first. This protects internal fields.
 *
 * Used in:
 *   GET  /api/branches       → returns List<BranchDTO>
 *   GET  /api/branches/{id}  → returns BranchDTO
 *   POST /api/branches       → returns BranchDTO (the newly created branch)
 */
public class BranchDTO {

    private Long   id;
    private String name;
    private String code;
    private String branchType;
    private String address;
    private String city;
    private String district;
    private String province;
    private String postalCode;
    private String phone;
    private String email;
    private Double latitude;
    private Double longitude;
    private String coverageDistricts;
    private String coverageCities;
    private String workingHoursStart;
    private String workingHoursEnd;
    private String workingDays;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public BranchDTO() {}

    // Getters
    public Long   getId()               { return id; }
    public String getName()             { return name; }
    public String getCode()             { return code; }
    public String getBranchType()       { return branchType; }
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
    public String getStatus()           { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // Setters
    public void setId(Long v)                { this.id               = v; }
    public void setName(String v)            { this.name             = v; }
    public void setCode(String v)            { this.code             = v; }
    public void setBranchType(String v)      { this.branchType       = v; }
    public void setAddress(String v)         { this.address          = v; }
    public void setCity(String v)            { this.city             = v; }
    public void setDistrict(String v)        { this.district         = v; }
    public void setProvince(String v)        { this.province         = v; }
    public void setPostalCode(String v)      { this.postalCode       = v; }
    public void setPhone(String v)           { this.phone            = v; }
    public void setEmail(String v)           { this.email            = v; }
    public void setLatitude(Double v)        { this.latitude         = v; }
    public void setLongitude(Double v)       { this.longitude        = v; }
    public void setCoverageDistricts(String v){ this.coverageDistricts= v; }
    public void setCoverageCities(String v)  { this.coverageCities   = v; }
    public void setWorkingHoursStart(String v){ this.workingHoursStart= v; }
    public void setWorkingHoursEnd(String v) { this.workingHoursEnd  = v; }
    public void setWorkingDays(String v)     { this.workingDays      = v; }
    public void setStatus(String v)          { this.status           = v; }
    public void setCreatedAt(LocalDateTime v){ this.createdAt        = v; }
    public void setUpdatedAt(LocalDateTime v){ this.updatedAt        = v; }
}
