package lk.slt.fieldops.dto;

public class ExchangeDTO {
    private Long    id;
    private String  name;
    private String  code;
    private Long    opmcId;
    private String  opmcName;
    private Double  latitude;
    private Double  longitude;
    private Boolean isActive;

    public ExchangeDTO() {}

    public Long    getId()        { return id; }
    public String  getName()      { return name; }
    public String  getCode()      { return code; }
    public Long    getOpmcId()    { return opmcId; }
    public String  getOpmcName()  { return opmcName; }
    public Double  getLatitude()  { return latitude; }
    public Double  getLongitude() { return longitude; }
    public Boolean getIsActive()  { return isActive; }

    public void setId(Long v)          { this.id        = v; }
    public void setName(String v)      { this.name      = v; }
    public void setCode(String v)      { this.code      = v; }
    public void setOpmcId(Long v)      { this.opmcId    = v; }
    public void setOpmcName(String v)  { this.opmcName  = v; }
    public void setLatitude(Double v)  { this.latitude  = v; }
    public void setLongitude(Double v) { this.longitude = v; }
    public void setIsActive(Boolean v) { this.isActive  = v; }
}
