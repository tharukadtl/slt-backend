package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * RefreshTokenRequest — body for POST /api/auth/refresh
 * {
 *   "refreshToken": "eyJhbGciOiJIUzUxMiJ9..."
 * }
 */
public class RefreshTokenRequest {

    @NotBlank(message = "refreshToken is required")
    private String refreshToken;

    public RefreshTokenRequest() {}

    public String getRefreshToken() { return refreshToken; }

    public void setRefreshToken(String v) { this.refreshToken = v; }
}
