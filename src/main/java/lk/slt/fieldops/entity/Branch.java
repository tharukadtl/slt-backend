package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Branch.java — maps to the `branches` table in slt_fieldops_db.
 *
 * Every column in the branches table is mapped here.
 * The field names match the DB column names (with camelCase).
 */
@Entity
@Table(name = "branches")
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "branch_type", nullable = false, length = 30)
    private BranchType branchType = BranchType.LOCAL_BRANCH;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String district;

    @Column(length = 100)
    private String province;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(length = 20)
    private String phone;

    @Column(length = 150)
    private String email;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "coverage_districts", length = 500)
    private String coverageDistricts;

    @Column(name = "coverage_cities", columnDefinition = "TEXT")
    private String coverageCities;

    @Column(name = "working_hours_start")
    private LocalTime workingHoursStart = LocalTime.of(8, 0);

    @Column(name = "working_hours_end")
    private LocalTime workingHoursEnd = LocalTime.of(17, 0);

    @Column(name = "working_days", nullable = false, length = 50)
    private String workingDays = "MON,TUE,WED,THU,FRI";

    @Column(name = "holiday_list", columnDefinition = "TEXT")
    private String holidayList;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BranchStatus status = BranchStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── Enums ────────────────────────────────────────────────────────────────
    public enum BranchType {
        REGIONAL_OFFICE, DISTRICT_CENTER, LOCAL_BRANCH
    }

    public enum BranchStatus {
        ACTIVE, INACTIVE
    }

    // ── No-arg constructor (required by JPA) ─────────────────────────────────
    public Branch() {}

    // ── Getters ───────────────────────────────────────────────────────────────
    public Long          getId()               { return id; }
    public String        getName()             { return name; }
    public String        getCode()             { return code; }
    public BranchType    getBranchType()       { return branchType; }
    public String        getAddress()          { return address; }
    public String        getCity()             { return city; }
    public String        getDistrict()         { return district; }
    public String        getProvince()         { return province; }
    public String        getPostalCode()       { return postalCode; }
    public String        getPhone()            { return phone; }
    public String        getEmail()            { return email; }
    public Double        getLatitude()         { return latitude; }
    public Double        getLongitude()        { return longitude; }
    public String        getCoverageDistricts(){ return coverageDistricts; }
    public String        getCoverageCities()   { return coverageCities; }
    public LocalTime     getWorkingHoursStart(){ return workingHoursStart; }
    public LocalTime     getWorkingHoursEnd()  { return workingHoursEnd; }
    public String        getWorkingDays()      { return workingDays; }
    public String        getHolidayList()      { return holidayList; }
    public BranchStatus  getStatus()           { return status; }
    public LocalDateTime getCreatedAt()        { return createdAt; }
    public LocalDateTime getUpdatedAt()        { return updatedAt; }
    public Long          getCreatedBy()        { return createdBy; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setId(Long v)                      { this.id               = v; }
    public void setName(String v)                  { this.name             = v; }
    public void setCode(String v)                  { this.code             = v; }
    public void setBranchType(BranchType v)        { this.branchType       = v; }
    public void setAddress(String v)               { this.address          = v; }
    public void setCity(String v)                  { this.city             = v; }
    public void setDistrict(String v)              { this.district         = v; }
    public void setProvince(String v)              { this.province         = v; }
    public void setPostalCode(String v)            { this.postalCode       = v; }
    public void setPhone(String v)                 { this.phone            = v; }
    public void setEmail(String v)                 { this.email            = v; }
    public void setLatitude(Double v)              { this.latitude         = v; }
    public void setLongitude(Double v)             { this.longitude        = v; }
    public void setCoverageDistricts(String v)     { this.coverageDistricts= v; }
    public void setCoverageCities(String v)        { this.coverageCities   = v; }
    public void setWorkingHoursStart(LocalTime v)  { this.workingHoursStart= v; }
    public void setWorkingHoursEnd(LocalTime v)    { this.workingHoursEnd  = v; }
    public void setWorkingDays(String v)           { this.workingDays      = v; }
    public void setHolidayList(String v)           { this.holidayList      = v; }
    public void setStatus(BranchStatus v)          { this.status           = v; }
    public void setCreatedBy(Long v)               { this.createdBy        = v; }
}
