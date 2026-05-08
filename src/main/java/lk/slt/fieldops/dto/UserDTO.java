package lk.slt.fieldops.dto;

import java.time.LocalDateTime;

public class UserDTO {

    private Long          id;
    private String        username;
    private String        fullName;
    private String        email;
    private String        phone;
    private String        role;
    private Long          branchId;
    private String        branchName;
    private Boolean       isActive;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;

    public UserDTO() {}

    public Long          getId()         { return id; }
    public String        getUsername()   { return username; }
    public String        getFullName()   { return fullName; }
    public String        getEmail()      { return email; }
    public String        getPhone()      { return phone; }
    public String        getRole()       { return role; }
    public Long          getBranchId()   { return branchId; }
    public String        getBranchName() { return branchName; }
    public Boolean       getIsActive()   { return isActive; }
    public LocalDateTime getLastLogin()  { return lastLogin; }
    public LocalDateTime getCreatedAt()  { return createdAt; }

    public void setId(Long v)                 { this.id         = v; }
    public void setUsername(String v)         { this.username   = v; }
    public void setFullName(String v)         { this.fullName   = v; }
    public void setEmail(String v)            { this.email      = v; }
    public void setPhone(String v)            { this.phone      = v; }
    public void setRole(String v)             { this.role       = v; }
    public void setBranchId(Long v)           { this.branchId   = v; }
    public void setBranchName(String v)       { this.branchName = v; }
    public void setIsActive(Boolean v)        { this.isActive   = v; }
    public void setLastLogin(LocalDateTime v) { this.lastLogin  = v; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt  = v; }
}
