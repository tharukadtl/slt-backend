package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * BodRequest — Team Lead submits Beginning of Day.
 *
 * POST /api/jobs/bod
 * {
 *   "vehicleId":       3,
 *   "odometerStart":   45200,
 *   "latitude":        6.9271,
 *   "longitude":       79.8612,
 *   "technicianIds":   [7, 8, 9]
 * }
 */
public class BodRequest {

    private Long vehicleId;

    private Integer odometerStart;

    private Double latitude;
    private Double longitude;
    private String locationAddress;

    @NotNull(message = "At least one technician must be selected")
    private List<Long> technicianIds;

    public BodRequest() {}

    public Long        getVehicleId()      { return vehicleId; }
    public Integer     getOdometerStart()  { return odometerStart; }
    public Double      getLatitude()       { return latitude; }
    public Double      getLongitude()      { return longitude; }
    public String      getLocationAddress(){ return locationAddress; }
    public List<Long>  getTechnicianIds()  { return technicianIds; }

    public void setVehicleId(Long v)           { this.vehicleId       = v; }
    public void setOdometerStart(Integer v)    { this.odometerStart   = v; }
    public void setLatitude(Double v)          { this.latitude        = v; }
    public void setLongitude(Double v)         { this.longitude       = v; }
    public void setLocationAddress(String v)   { this.locationAddress = v; }
    public void setTechnicianIds(List<Long> v) { this.technicianIds   = v; }
}
