package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateExchangeRequest {
    @NotBlank(message = "Exchange name is required")
    private String name;

    @NotBlank(message = "Exchange code is required")
    private String code;

    @NotNull(message = "OPMC ID is required")
    private Long opmcId;

    public CreateExchangeRequest() {}

    public String getName()   { return name; }
    public String getCode()   { return code; }
    public Long   getOpmcId() { return opmcId; }

    public void setName(String v)   { this.name   = v; }
    public void setCode(String v)   { this.code   = v; }
    public void setOpmcId(Long v)   { this.opmcId = v; }
}
