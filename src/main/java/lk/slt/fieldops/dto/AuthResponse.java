package lk.slt.fieldops.dto;

public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";

    private Long userId;
    private String username;
    private String role;
    private String fullName;
    private String phoneNumber;
    private Long branchId;

    private Long expiresIn;

    public AuthResponse() {}

    // ================= GETTERS =================

    public String getAccessToken() { return accessToken; }

    public String getRefreshToken() { return refreshToken; }

    public String getTokenType() { return tokenType; }

    public Long getUserId() { return userId; }

    public String getUsername() { return username; }

    public String getRole() { return role; }

    public String getFullName() { return fullName; }

    public String getPhoneNumber() { return phoneNumber; }

    public Long getBranchId() { return branchId; }

    public Long getExpiresIn() { return expiresIn; }

    // ================= SETTERS =================

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public void setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
    }
}