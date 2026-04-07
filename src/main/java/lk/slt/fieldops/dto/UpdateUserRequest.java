package lk.slt.fieldops.user.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateUserRequest {

    @NotBlank(message = "Full name is required")
    private String fullName;

    private String email;
    private String phone;
    private String role;
    private Long   branchId;

    public UpdateUserRequest() {}

    public String getFullName() { return fullName; }
    public String getEmail()    { return email; }
    public String getPhone()    { return phone; }
    public String getRole()     { return role; }
    public Long   getBranchId() { return branchId; }

    public void setFullName(String v) { this.fullName = v; }
    public void setEmail(String v)    { this.email    = v; }
    public void setPhone(String v)    { this.phone    = v; }
    public void setRole(String v)     { this.role     = v; }
    public void setBranchId(Long v)   { this.branchId = v; }
}
