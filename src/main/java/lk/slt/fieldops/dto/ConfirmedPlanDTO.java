package lk.slt.fieldops.dto;

import java.util.List;

/**
 * ConfirmedPlanDTO — one confirmed (OPMC, date, shift) row, as returned by
 * GET /api/resource-plans/lookup to the Team Lead's BOD screen.
 */
public class ConfirmedPlanDTO {

    private String shift;
    private Integer zoneId;
    private String zoneName;
    private Double predictedFaultCount;
    private Integer suggestedTechnicians;
    private Integer suggestedVehicles;
    private List<MaterialDTO> materials;
    private String confirmedAt;

    public static class MaterialDTO {
        private Long materialId;
        private String materialName;
        private Double suggestedQuantity;
        private String unit;

        public MaterialDTO() {}

        public MaterialDTO(Long materialId, String materialName, Double suggestedQuantity, String unit) {
            this.materialId = materialId;
            this.materialName = materialName;
            this.suggestedQuantity = suggestedQuantity;
            this.unit = unit;
        }

        public Long getMaterialId()            { return materialId; }
        public String getMaterialName()        { return materialName; }
        public Double getSuggestedQuantity()   { return suggestedQuantity; }
        public String getUnit()                { return unit; }

        public void setMaterialId(Long v)          { this.materialId        = v; }
        public void setMaterialName(String v)      { this.materialName      = v; }
        public void setSuggestedQuantity(Double v) { this.suggestedQuantity = v; }
        public void setUnit(String v)              { this.unit              = v; }
    }

    public String getShift()                    { return shift; }
    public Integer getZoneId()                  { return zoneId; }
    public String getZoneName()                 { return zoneName; }
    public Double getPredictedFaultCount()       { return predictedFaultCount; }
    public Integer getSuggestedTechnicians()    { return suggestedTechnicians; }
    public Integer getSuggestedVehicles()       { return suggestedVehicles; }
    public List<MaterialDTO> getMaterials()     { return materials; }
    public String getConfirmedAt()              { return confirmedAt; }

    public void setShift(String v)                    { this.shift                = v; }
    public void setZoneId(Integer v)                  { this.zoneId               = v; }
    public void setZoneName(String v)                 { this.zoneName             = v; }
    public void setPredictedFaultCount(Double v)      { this.predictedFaultCount  = v; }
    public void setSuggestedTechnicians(Integer v)    { this.suggestedTechnicians = v; }
    public void setSuggestedVehicles(Integer v)       { this.suggestedVehicles    = v; }
    public void setMaterials(List<MaterialDTO> v)     { this.materials            = v; }
    public void setConfirmedAt(String v)              { this.confirmedAt          = v; }
}
