package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotNull;

/**
 * ReassignJobRequest — body for POST /api/jobs/{id}/reassign
 * {
 *   "newTechnicianId": 8
 * }
 */
public class ReassignJobRequest {

    @NotNull(message = "newTechnicianId is required")
    private Long newTechnicianId;

    public ReassignJobRequest() {}

    public Long getNewTechnicianId() { return newTechnicianId; }

    public void setNewTechnicianId(Long v) { this.newTechnicianId = v; }
}
