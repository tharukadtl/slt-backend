package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotNull;

/** AttachCauseRequest — body of PATCH /api/faults/{id}/cause (Stage 2). */
public class AttachCauseRequest {

    @NotNull(message = "causeId is required")
    private Long causeId;

    public AttachCauseRequest() {}

    public Long getCauseId() { return causeId; }
    public void setCauseId(Long v) { this.causeId = v; }
}
