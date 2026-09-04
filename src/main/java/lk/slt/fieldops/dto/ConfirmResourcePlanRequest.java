package lk.slt.fieldops.dto;

import java.util.List;
import java.util.Map;

/**
 * ConfirmResourcePlanRequest — body of POST /api/resource-plans/confirm.
 *
 * Mirrors the shape frontend-admin's ResourcePlanningPage.js already builds
 * for its (Stage 3a) "Confirm Plan" preview, plus zoneOpmcMap — the
 * Admin's explicit zone-id -> opmc-id assignment made at confirm time
 * (Stage 3b design decision: no automatic zone-to-OPMC inference exists,
 * since the AI module's K-Means zones aren't persisted/stable entities).
 */
public class ConfirmResourcePlanRequest {

    private Integer horizonDays;
    private String generatedAt;
    private String confirmedAt;

    /** zoneId (as string) -> opmcId. */
    private Map<String, Long> zoneOpmcMap;

    private List<HotspotItem> hotspots;

    public static class HotspotItem {
        private String date;
        private String shift;
        private Integer zoneId;
        private String zoneName;
        private Double predictedFaultCount;
        private Integer suggestedTechnicians;
        private Integer suggestedVehicles;
        private List<MaterialItem> materials;

        public String getDate()                     { return date; }
        public String getShift()                    { return shift; }
        public Integer getZoneId()                  { return zoneId; }
        public String getZoneName()                 { return zoneName; }
        public Double getPredictedFaultCount()       { return predictedFaultCount; }
        public Integer getSuggestedTechnicians()    { return suggestedTechnicians; }
        public Integer getSuggestedVehicles()       { return suggestedVehicles; }
        public List<MaterialItem> getMaterials()    { return materials; }

        public void setDate(String v)                     { this.date                 = v; }
        public void setShift(String v)                    { this.shift                = v; }
        public void setZoneId(Integer v)                  { this.zoneId               = v; }
        public void setZoneName(String v)                 { this.zoneName             = v; }
        public void setPredictedFaultCount(Double v)      { this.predictedFaultCount  = v; }
        public void setSuggestedTechnicians(Integer v)    { this.suggestedTechnicians = v; }
        public void setSuggestedVehicles(Integer v)       { this.suggestedVehicles    = v; }
        public void setMaterials(List<MaterialItem> v)    { this.materials            = v; }
    }

    public static class MaterialItem {
        private Long materialId;
        private String materialName;
        private Double suggestedQuantity;
        private String unit;

        public Long getMaterialId()            { return materialId; }
        public String getMaterialName()        { return materialName; }
        public Double getSuggestedQuantity()   { return suggestedQuantity; }
        public String getUnit()                { return unit; }

        public void setMaterialId(Long v)          { this.materialId        = v; }
        public void setMaterialName(String v)      { this.materialName      = v; }
        public void setSuggestedQuantity(Double v) { this.suggestedQuantity = v; }
        public void setUnit(String v)              { this.unit              = v; }
    }

    public Integer getHorizonDays()            { return horizonDays; }
    public String getGeneratedAt()             { return generatedAt; }
    public String getConfirmedAt()             { return confirmedAt; }
    public Map<String, Long> getZoneOpmcMap()  { return zoneOpmcMap; }
    public List<HotspotItem> getHotspots()     { return hotspots; }

    public void setHorizonDays(Integer v)             { this.horizonDays   = v; }
    public void setGeneratedAt(String v)              { this.generatedAt   = v; }
    public void setConfirmedAt(String v)              { this.confirmedAt   = v; }
    public void setZoneOpmcMap(Map<String, Long> v)   { this.zoneOpmcMap   = v; }
    public void setHotspots(List<HotspotItem> v)      { this.hotspots      = v; }
}
