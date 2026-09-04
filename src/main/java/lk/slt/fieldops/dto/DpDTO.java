package lk.slt.fieldops.dto;

public class DpDTO {
    private Long    id;
    private String  name;
    private String  code;
    private Long    cabId;
    private String  cabName;
    private Boolean isActive;

    public DpDTO() {}

    public Long    getId()       { return id; }
    public String  getName()     { return name; }
    public String  getCode()     { return code; }
    public Long    getCabId()    { return cabId; }
    public String  getCabName()  { return cabName; }
    public Boolean getIsActive() { return isActive; }

    public void setId(Long v)        { this.id       = v; }
    public void setName(String v)    { this.name     = v; }
    public void setCode(String v)    { this.code     = v; }
    public void setCabId(Long v)     { this.cabId    = v; }
    public void setCabName(String v) { this.cabName  = v; }
    public void setIsActive(Boolean v){ this.isActive = v; }
}
