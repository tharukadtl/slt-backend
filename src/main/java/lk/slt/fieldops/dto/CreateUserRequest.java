package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateUserRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    @NotBlank(message = "Full name is required")
    private String fullName;

    private String email;
    private String phone;
    private String address;

    @NotNull(message = "Role is required")
    private String role;

    private Long opmcId;
    private Long workgroupId;

    public CreateUserRequest() {}

    public String getUsername()    { return username; }
    public String getPassword()    { return password; }
    public String getFullName()    { return fullName; }
    public String getEmail()       { return email; }
    public String getPhone()       { return phone; }
    public String getAddress()     { return address; }
    public String getRole()        { return role; }
    public Long   getOpmcId()      { return opmcId; }
    public Long   getWorkgroupId() { return workgroupId; }

    public void setUsername(String v)    { this.username    = v; }
    public void setPassword(String v)    { this.password    = v; }
    public void setFullName(String v)    { this.fullName    = v; }
    public void setEmail(String v)       { this.email       = v; }
    public void setPhone(String v)       { this.phone       = v; }
    public void setAddress(String v)     { this.address     = v; }
    public void setRole(String v)        { this.role        = v; }
    public void setOpmcId(Long v)        { this.opmcId      = v; }
    public void setWorkgroupId(Long v)   { this.workgroupId = v; }
}
