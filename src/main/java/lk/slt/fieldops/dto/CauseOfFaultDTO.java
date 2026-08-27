package lk.slt.fieldops.dto;

public class CauseOfFaultDTO {
    private Long    id;
    private String  causeCode;
    private String  description;
    private Long    causeCategoryId;
    private String  clarityDescription;
    private Boolean appliesCopper;
    private Boolean appliesFtth;
    private Boolean appliesLte;
    private Integer sortKey;

    public CauseOfFaultDTO() {}

    public Long    getId()                 { return id; }
    public String  getCauseCode()          { return causeCode; }
    public String  getDescription()        { return description; }
    public Long    getCauseCategoryId()    { return causeCategoryId; }
    public String  getClarityDescription() { return clarityDescription; }
    public Boolean getAppliesCopper()      { return appliesCopper; }
    public Boolean getAppliesFtth()        { return appliesFtth; }
    public Boolean getAppliesLte()         { return appliesLte; }
    public Integer getSortKey()            { return sortKey; }

    public void setId(Long v)                  { this.id                 = v; }
    public void setCauseCode(String v)         { this.causeCode          = v; }
    public void setDescription(String v)       { this.description        = v; }
    public void setCauseCategoryId(Long v)     { this.causeCategoryId    = v; }
    public void setClarityDescription(String v){ this.clarityDescription = v; }
    public void setAppliesCopper(Boolean v)    { this.appliesCopper      = v; }
    public void setAppliesFtth(Boolean v)      { this.appliesFtth        = v; }
    public void setAppliesLte(Boolean v)       { this.appliesLte         = v; }
    public void setSortKey(Integer v)          { this.sortKey            = v; }
}
