package lk.slt.fieldops.dto;

/**
 * AssignTechnicianRequest — body for PATCH /api/vehicles/{id}/assign-technician
 * {
 *   "technicianId": 8   // omit or pass null to unassign
 * }
 */
public class AssignTechnicianRequest {

    private Long technicianId;

    public AssignTechnicianRequest() {}

    public Long getTechnicianId() { return technicianId; }

    public void setTechnicianId(Long v) { this.technicianId = v; }
}
