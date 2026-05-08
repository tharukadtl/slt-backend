package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotNull;

/**
 * AssignFaultRequest — Admin assigns a fault to a Team Lead.
 * POST /api/faults/{id}/assign
 *
 * Example body:
 * {
 *   "teamLeadId": 5
 * }
 */
public class AssignFaultRequest {

    @NotNull(message = "Team Lead ID is required")
    private Long teamLeadId;

    private String notes;   // optional admin note

    public AssignFaultRequest() {}

    public Long   getTeamLeadId() { return teamLeadId; }
    public String getNotes()      { return notes; }

    public void setTeamLeadId(Long v)  { this.teamLeadId = v; }
    public void setNotes(String v)     { this.notes      = v; }
}
