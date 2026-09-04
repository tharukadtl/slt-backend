package lk.slt.fieldops.dto;

public class CircuitDTO {
    private Long    id;
    private String  code;
    private Long    dpId;
    private String  dpName;
    private Long    cabId;
    private String  cabName;
    private Long    exchangeId;
    private String  exchangeName;
    private Long    opmcId;
    private String  opmcName;
    private Integer circuitCategoryId;
    private String  circuitCategoryCode;
    private Boolean isActive;

    public CircuitDTO() {}

    public Long    getId()                { return id; }
    public String  getCode()              { return code; }
    public Long    getDpId()              { return dpId; }
    public String  getDpName()            { return dpName; }
    public Long    getCabId()             { return cabId; }
    public String  getCabName()           { return cabName; }
    public Long    getExchangeId()        { return exchangeId; }
    public String  getExchangeName()      { return exchangeName; }
    public Long    getOpmcId()            { return opmcId; }
    public String  getOpmcName()          { return opmcName; }
    public Integer getCircuitCategoryId() { return circuitCategoryId; }
    public String  getCircuitCategoryCode() { return circuitCategoryCode; }
    public Boolean getIsActive()          { return isActive; }

    public void setId(Long v)                  { this.id                = v; }
    public void setCode(String v)              { this.code              = v; }
    public void setDpId(Long v)                { this.dpId              = v; }
    public void setDpName(String v)            { this.dpName            = v; }
    public void setCabId(Long v)               { this.cabId             = v; }
    public void setCabName(String v)           { this.cabName           = v; }
    public void setExchangeId(Long v)          { this.exchangeId        = v; }
    public void setExchangeName(String v)      { this.exchangeName      = v; }
    public void setOpmcId(Long v)              { this.opmcId            = v; }
    public void setOpmcName(String v)          { this.opmcName          = v; }
    public void setCircuitCategoryId(Integer v)  { this.circuitCategoryId   = v; }
    public void setCircuitCategoryCode(String v) { this.circuitCategoryCode = v; }
    public void setIsActive(Boolean v)         { this.isActive          = v; }
}
