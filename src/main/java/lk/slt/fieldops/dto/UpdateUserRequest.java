package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateUserRequest {

    @NotBlank(message = "Full name is required")
    private String fullName;

    private String email;
    private String phone;
    private String address;
    private String role;
    private Long   opmcId;
    private Long   workgroupId;

    public UpdateUserRequest() {}

    public String getFullName()    { return fullName; }
    public String getEmail()       { return email; }
    public String getPhone()       { return phone; }
    public String getAddress()     { return address; }
    public String getRole()        { return role; }
    public Long   getOpmcId()      { return opmcId; }
    public Long   getWorkgroupId() { return workgroupId; }

    public void setFullName(String v)    { this.fullName    = v; }
    public void setEmail(String v)       { this.email       = v; }
    public void setPhone(String v)       { this.phone       = v; }
    public void setAddress(String v)     { this.address     = v; }
    public void setRole(String v)        { this.role        = v; }
    public void setOpmcId(Long v)        { this.opmcId      = v; }
    public void setWorkgroupId(Long v)   { this.workgroupId = v; }
}
