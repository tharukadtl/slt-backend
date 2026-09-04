package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateDpRequest {
    @NotBlank(message = "DP name is required")
    private String name;

    @NotBlank(message = "DP code is required")
    private String code;

    @NotNull(message = "Cab ID is required")
    private Long cabId;

    public CreateDpRequest() {}

    public String getName()  { return name; }
    public String getCode()  { return code; }
    public Long   getCabId() { return cabId; }

    public void setName(String v)  { this.name  = v; }
    public void setCode(String v)  { this.code  = v; }
    public void setCabId(Long v)   { this.cabId = v; }
}
