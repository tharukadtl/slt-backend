package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** CreateWorkGroupRequest — body for POST/PUT /api/workgroups. */
public class CreateWorkGroupRequest {

    @NotBlank(message = "Work group name is required")
    private String name;

    @NotNull(message = "OPMC ID is required")
    private Long opmcId;

    private Long teamLeadId;   // optional — a work group can exist before a Team Lead is assigned

    public CreateWorkGroupRequest() {}

    public String getName()       { return name; }
    public Long   getOpmcId()     { return opmcId; }
    public Long   getTeamLeadId() { return teamLeadId; }

    public void setName(String v)       { this.name       = v; }
    public void setOpmcId(Long v)       { this.opmcId     = v; }
    public void setTeamLeadId(Long v)   { this.teamLeadId = v; }
}
