package lk.slt.fieldops.user.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    public enum Role {
        SUPER_ADMIN, ADMIN, TEAM_LEAD, TECHNICIAN, CLIENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(length = 150)
    private String email;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Role role;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "branch_name", length = 100)
    private String branchName;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "fcm_token", length = 255)
    private String fcmToken;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public User() {}

    public Long          getId()           { return id; }
    public String        getUsername()     { return username; }
    public String        getPasswordHash() { return passwordHash; }
    public String        getFullName()     { return fullName; }
    public String        getEmail()        { return email; }
    public String        getPhone()        { return phone; }
    public Role          getRole()         { return role; }
    public Long          getBranchId()     { return branchId; }
    public String        getBranchName()   { return branchName; }
    public Boolean       getIsActive()     { return isActive; }
    public String        getFcmToken()     { return fcmToken; }
    public LocalDateTime getLastLogin()    { return lastLogin; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public LocalDateTime getUpdatedAt()    { return updatedAt; }

    public void setId(Long v)                  { this.id           = v; }
    public void setUsername(String v)          { this.username     = v; }
    public void setPasswordHash(String v)      { this.passwordHash = v; }
    public void setFullName(String v)          { this.fullName     = v; }
    public void setEmail(String v)             { this.email        = v; }
    public void setPhone(String v)             { this.phone        = v; }
    public void setRole(Role v)                { this.role         = v; }
    public void setBranchId(Long v)            { this.branchId     = v; }
    public void setBranchName(String v)        { this.branchName   = v; }
    public void setIsActive(Boolean v)         { this.isActive     = v; }
    public void setFcmToken(String v)          { this.fcmToken     = v; }
    public void setLastLogin(LocalDateTime v)  { this.lastLogin    = v; }
}
