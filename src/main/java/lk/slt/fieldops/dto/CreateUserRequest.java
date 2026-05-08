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

    @NotNull(message = "Role is required")
    private String role;

    private Long branchId;

    public CreateUserRequest() {}

    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getFullName() { return fullName; }
    public String getEmail()    { return email; }
    public String getPhone()    { return phone; }
    public String getRole()     { return role; }
    public Long   getBranchId() { return branchId; }

    public void setUsername(String v) { this.username = v; }
    public void setPassword(String v) { this.password = v; }
    public void setFullName(String v) { this.fullName = v; }
    public void setEmail(String v)    { this.email    = v; }
    public void setPhone(String v)    { this.phone    = v; }
    public void setRole(String v)     { this.role     = v; }
    public void setBranchId(Long v)   { this.branchId = v; }
}
