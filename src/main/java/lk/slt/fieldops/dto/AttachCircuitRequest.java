package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotNull;

/** AttachCircuitRequest — body of PATCH /api/faults/{id}/circuit (H1c). */
public class AttachCircuitRequest {

    @NotNull(message = "circuitId is required")
    private Long circuitId;

    public AttachCircuitRequest() {}

    public Long getCircuitId() { return circuitId; }
    public void setCircuitId(Long v) { this.circuitId = v; }
}
