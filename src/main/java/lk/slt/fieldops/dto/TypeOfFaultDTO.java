package lk.slt.fieldops.dto;

public class TypeOfFaultDTO {
    private Long   id;
    private String typeCode;
    private String description;
    private Integer sortKey;

    public TypeOfFaultDTO() {}

    public Long    getId()          { return id; }
    public String  getTypeCode()    { return typeCode; }
    public String  getDescription() { return description; }
    public Integer getSortKey()     { return sortKey; }

    public void setId(Long v)            { this.id          = v; }
    public void setTypeCode(String v)    { this.typeCode    = v; }
    public void setDescription(String v) { this.description = v; }
    public void setSortKey(Integer v)    { this.sortKey     = v; }
}
