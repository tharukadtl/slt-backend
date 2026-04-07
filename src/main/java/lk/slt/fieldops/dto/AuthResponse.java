package lk.slt.fieldops.auth.dto;

public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private Long   userId;
    private String username;
    private String role;
    private String fullName;
    private Long   branchId;
    private Long   expiresIn;

    public AuthResponse() {}

    public static AuthResponseBuilder builder() { return new AuthResponseBuilder(); }

    public String getAccessToken()  { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public String getTokenType()    { return tokenType; }
    public Long   getUserId()       { return userId; }
    public String getUsername()     { return username; }
    public String getRole()         { return role; }
    public String getFullName()     { return fullName; }
    public Long   getBranchId()     { return branchId; }
    public Long   getExpiresIn()    { return expiresIn; }

    public void setAccessToken(String v)  { this.accessToken  = v; }
    public void setRefreshToken(String v) { this.refreshToken = v; }
    public void setTokenType(String v)    { this.tokenType    = v; }
    public void setUserId(Long v)         { this.userId       = v; }
    public void setUsername(String v)     { this.username     = v; }
    public void setRole(String v)         { this.role         = v; }
    public void setFullName(String v)     { this.fullName     = v; }
    public void setBranchId(Long v)       { this.branchId     = v; }
    public void setExpiresIn(Long v)      { this.expiresIn    = v; }

    public static class AuthResponseBuilder {
        private String accessToken;
        private String refreshToken;
        private String tokenType = "Bearer";
        private Long   userId;
        private String username;
        private String role;
        private String fullName;
        private Long   branchId;
        private Long   expiresIn;

        public AuthResponseBuilder accessToken(String v)  { this.accessToken  = v; return this; }
        public AuthResponseBuilder refreshToken(String v) { this.refreshToken = v; return this; }
        public AuthResponseBuilder tokenType(String v)    { this.tokenType    = v; return this; }
        public AuthResponseBuilder userId(Long v)         { this.userId       = v; return this; }
        public AuthResponseBuilder username(String v)     { this.username     = v; return this; }
        public AuthResponseBuilder role(String v)         { this.role         = v; return this; }
        public AuthResponseBuilder fullName(String v)     { this.fullName     = v; return this; }
        public AuthResponseBuilder branchId(Long v)       { this.branchId     = v; return this; }
        public AuthResponseBuilder expiresIn(Long v)      { this.expiresIn    = v; return this; }

        public AuthResponse build() {
            AuthResponse r = new AuthResponse();
            r.setAccessToken(this.accessToken);
            r.setRefreshToken(this.refreshToken);
            r.setTokenType(this.tokenType);
            r.setUserId(this.userId);
            r.setUsername(this.username);
            r.setRole(this.role);
            r.setFullName(this.fullName);
            r.setBranchId(this.branchId);
            r.setExpiresIn(this.expiresIn);
            return r;
        }
    }
}
