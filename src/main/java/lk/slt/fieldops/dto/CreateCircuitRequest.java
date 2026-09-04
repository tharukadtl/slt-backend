package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateCircuitRequest {
    @NotBlank(message = "Circuit code is required")
    private String code;

    @NotNull(message = "DP ID is required")
    private Long dpId;

    public CreateCircuitRequest() {}

    public String getCode() { return code; }
    public Long   getDpId() { return dpId; }

    public void setCode(String v) { this.code = v; }
    public void setDpId(Long v)   { this.dpId = v; }
}
