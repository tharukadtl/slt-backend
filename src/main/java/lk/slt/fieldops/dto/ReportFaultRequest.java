package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * ReportFaultRequest — Client reports a new fault.
 * POST /api/faults
 *
 * Example body:
 * {
 *   "category":        "INTERNET",
 *   "description":     "No internet since morning",
 *   "locationAddress": "No. 5 Main Street, Colombo 03",
 *   "locationCity":    "Colombo",
 *   "branchId":        1
 * }
 */
public class ReportFaultRequest {

    @NotNull(message = "Category is required")
    private String category;   // INTERNET / PHONE / TV / OTHER

    @NotBlank(message = "Description is required")
    private String description;

    private String locationAddress;
    private String locationCity;
    private String locationDistrict;
    private Double latitude;
    private Double longitude;

    @NotNull(message = "Branch ID is required")
    private Long branchId;

    private String priority;   // HIGH / MEDIUM / LOW — defaults to MEDIUM if not sent

    public ReportFaultRequest() {}

    public String getCategory()         { return category; }
    public String getDescription()      { return description; }
    public String getLocationAddress()  { return locationAddress; }
    public String getLocationCity()     { return locationCity; }
    public String getLocationDistrict() { return locationDistrict; }
    public Double getLatitude()         { return latitude; }
    public Double getLongitude()        { return longitude; }
    public Long   getBranchId()         { return branchId; }
    public String getPriority()         { return priority; }

    public void setCategory(String v)          { this.category         = v; }
    public void setDescription(String v)       { this.description      = v; }
    public void setLocationAddress(String v)   { this.locationAddress  = v; }
    public void setLocationCity(String v)      { this.locationCity     = v; }
    public void setLocationDistrict(String v)  { this.locationDistrict = v; }
    public void setLatitude(Double v)          { this.latitude         = v; }
    public void setLongitude(Double v)         { this.longitude        = v; }
    public void setBranchId(Long v)            { this.branchId         = v; }
    public void setPriority(String v)          { this.priority         = v; }
}
