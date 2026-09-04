package lk.slt.fieldops.dto;

import java.time.LocalDateTime;

/** WorkGroupDTO — what we SEND BACK to the client in API responses. */
public class WorkGroupDTO {

    private Long   id;
    private String name;
    private Long   opmcId;
    private String opmcName;
    private Long   teamLeadId;
    private String teamLeadName;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public WorkGroupDTO() {}

    public Long          getId()           { return id; }
    public String        getName()         { return name; }
    public Long          getOpmcId()       { return opmcId; }
    public String        getOpmcName()     { return opmcName; }
    public Long          getTeamLeadId()   { return teamLeadId; }
    public String        getTeamLeadName() { return teamLeadName; }
    public Boolean       getIsActive()     { return isActive; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public LocalDateTime getUpdatedAt()    { return updatedAt; }

    public void setId(Long v)                  { this.id           = v; }
    public void setName(String v)              { this.name         = v; }
    public void setOpmcId(Long v)              { this.opmcId       = v; }
    public void setOpmcName(String v)          { this.opmcName     = v; }
    public void setTeamLeadId(Long v)          { this.teamLeadId   = v; }
    public void setTeamLeadName(String v)      { this.teamLeadName = v; }
    public void setIsActive(Boolean v)         { this.isActive     = v; }
    public void setCreatedAt(LocalDateTime v)  { this.createdAt    = v; }
    public void setUpdatedAt(LocalDateTime v)  { this.updatedAt    = v; }
}
