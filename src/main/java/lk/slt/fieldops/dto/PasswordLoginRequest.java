package lk.slt.fieldops.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class PasswordLoginRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    private String deviceInfo;

    public PasswordLoginRequest() {}

    public String getUsername()    { return username; }
    public String getPassword()    { return password; }
    public String getDeviceInfo()  { return deviceInfo; }

    public void setUsername(String v)    { this.username    = v; }
    public void setPassword(String v)    { this.password    = v; }
    public void setDeviceInfo(String v)  { this.deviceInfo  = v; }
}
