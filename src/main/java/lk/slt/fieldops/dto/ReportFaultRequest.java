package lk.slt.fieldops.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
 *   "opmcId":          1
 * }
 */
public class ReportFaultRequest {

    // Accepts the real FaultCategory enum names plus the mobile-app aliases
    // (BROADBAND/TELEPHONE/TELEVISION) that FaultController.normalizeMobileCategory
    // translates after validation — anything else (including case variants of neither
    // set) is rejected here instead of silently falling through normalizeMobileCategory's
    // default branch to OTHER.
    @NotNull(message = "Category is required")
    @Pattern(
        regexp = "(?i)^(INTERNET|PHONE|FIBER|TV|OTHER|BROADBAND|TELEPHONE|TELEVISION)$",
        message = "Invalid category. Valid: INTERNET, PHONE, FIBER, TV, OTHER"
    )
    private String category;   // INTERNET / PHONE / TV / OTHER

    @NotBlank(message = "Description is required")
    @Size(min = 10, max = 500, message = "Description must be between 10 and 500 characters")
    private String description;

    @NotBlank(message = "Location address is required")
    private String locationAddress;
    private String locationCity;
    private String locationDistrict;

    @NotNull(message = "Latitude is required")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    private Double longitude;

    @NotNull(message = "OPMC ID is required")
    private Long opmcId;

    private String priority;   // HIGH / MEDIUM / LOW — defaults to MEDIUM if not sent

    @JsonAlias("photos")
    private String[] photoUrls;  // max 5, JPEG/PNG — uploaded separately, URLs referenced here

    public ReportFaultRequest() {}

    public String   getCategory()         { return category; }
    public String   getDescription()      { return description; }
    public String   getLocationAddress()  { return locationAddress; }
    public String   getLocationCity()     { return locationCity; }
    public String   getLocationDistrict() { return locationDistrict; }
    public Double   getLatitude()         { return latitude; }
    public Double   getLongitude()        { return longitude; }
    public Long     getOpmcId()           { return opmcId; }
    public String   getPriority()         { return priority; }
    public String[] getPhotoUrls()        { return photoUrls; }

    public void setCategory(String v)          { this.category         = v; }
    public void setDescription(String v)       { this.description      = v; }
    public void setLocationAddress(String v)   { this.locationAddress  = v; }
    public void setLocationCity(String v)      { this.locationCity     = v; }
    public void setLocationDistrict(String v)  { this.locationDistrict = v; }
    public void setLatitude(Double v)          { this.latitude         = v; }
    public void setLongitude(Double v)         { this.longitude        = v; }
    public void setOpmcId(Long v)              { this.opmcId           = v; }
    public void setPriority(String v)          { this.priority         = v; }
    public void setPhotoUrls(String[] v)       { this.photoUrls        = v; }
}
