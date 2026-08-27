package lk.slt.fieldops.dto;

public class CauseCategoryDTO {
    private Long   id;
    private String causeCategoryCode;
    private String description;
    private Long   typeOfFaultId;
    private Integer sortKey;

    public CauseCategoryDTO() {}

    public Long    getId()                { return id; }
    public String  getCauseCategoryCode() { return causeCategoryCode; }
    public String  getDescription()       { return description; }
    public Long    getTypeOfFaultId()     { return typeOfFaultId; }
    public Integer getSortKey()           { return sortKey; }

    public void setId(Long v)                 { this.id                = v; }
    public void setCauseCategoryCode(String v){ this.causeCategoryCode = v; }
    public void setDescription(String v)      { this.description       = v; }
    public void setTypeOfFaultId(Long v)      { this.typeOfFaultId     = v; }
    public void setSortKey(Integer v)         { this.sortKey           = v; }
}
