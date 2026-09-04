package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateCabRequest {
    @NotBlank(message = "Cab name is required")
    private String name;

    @NotBlank(message = "Cab code is required")
    private String code;

    @NotNull(message = "Exchange ID is required")
    private Long exchangeId;

    public CreateCabRequest() {}

    public String getName()       { return name; }
    public String getCode()       { return code; }
    public Long   getExchangeId() { return exchangeId; }

    public void setName(String v)       { this.name       = v; }
    public void setCode(String v)       { this.code       = v; }
    public void setExchangeId(Long v)   { this.exchangeId = v; }
}
