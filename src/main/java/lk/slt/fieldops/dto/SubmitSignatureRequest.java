package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * SubmitSignatureRequest — body for POST /api/jobs/{id}/signature
 * {
 *   "signature": "data:image/png;base64,iVBORw0KG..."
 * }
 */
public class SubmitSignatureRequest {

    @NotBlank(message = "signature is required")
    private String signature;

    public SubmitSignatureRequest() {}

    public String getSignature() { return signature; }

    public void setSignature(String v) { this.signature = v; }
}
