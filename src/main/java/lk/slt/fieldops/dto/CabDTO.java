package lk.slt.fieldops.dto;

public class CabDTO {
    private Long    id;
    private String  name;
    private String  code;
    private Long    exchangeId;
    private String  exchangeName;
    private Boolean isActive;

    public CabDTO() {}

    public Long    getId()           { return id; }
    public String  getName()         { return name; }
    public String  getCode()         { return code; }
    public Long    getExchangeId()   { return exchangeId; }
    public String  getExchangeName() { return exchangeName; }
    public Boolean getIsActive()     { return isActive; }

    public void setId(Long v)             { this.id           = v; }
    public void setName(String v)         { this.name         = v; }
    public void setCode(String v)         { this.code         = v; }
    public void setExchangeId(Long v)     { this.exchangeId   = v; }
    public void setExchangeName(String v) { this.exchangeName = v; }
    public void setIsActive(Boolean v)    { this.isActive     = v; }
}
