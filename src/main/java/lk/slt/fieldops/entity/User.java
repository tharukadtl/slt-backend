package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    public enum Role {
        SUPER_ADMIN, ADMIN, TEAM_LEAD, TECHNICIAN, CLIENT
    }

    public enum UserStatus {
        ACTIVE, INACTIVE, LOCKED, SUSPENDED, PENDING
    }

    public enum Language {
        ENGLISH, SINHALA, TAMIL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Identity ─────────────────────────────────────────
    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 75)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 75)
    private String lastName;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    // ─── Contact ──────────────────────────────────────────
    @Column(length = 150, unique = true)
    private String email;

    // Maps the legacy 'phone' column — used by AuthService
    @Column(name = "phone", length = 20)
    private String phone;

    // Maps the 'phone_number' column used by other parts of the schema
    @Column(name = "phone_number", length = 20, unique = true)
    private String phoneNumber;

    // ─── Role & Status ────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // ─── Security ─────────────────────────────────────────
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "force_password_change", nullable = false)
    private boolean forcePasswordChange = false;

    @Column(name = "account_locked_until")
    private LocalDateTime accountLockedUntil;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    // ─── Preferences ──────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_language", nullable = false, length = 10)
    private Language preferredLanguage = Language.ENGLISH;

    // ─── Identity extras ──────────────────────────────────
    @Column(name = "full_name_nic", length = 150)
    private String fullNameNic;

    @Column(name = "nic_number", length = 20, unique = true)
    private String nicNumber;

    @Column(length = 500)
    private String address;

    @Column(name = "subscription_number", length = 100, unique = true)
    private String subscriptionNumber;

    @Column(name = "profile_photo_url", length = 500)
    private String profilePhotoUrl;

    // ─── Branch / Workgroup ───────────────────────────────
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "branch_name", length = 100)
    private String branchName;

    @Column(name = "workgroup_id")
    private Long workgroupId;

    // ─── Tokens ───────────────────────────────────────────
    @Column(name = "fcm_token", length = 512)
    private String fcmToken;

    @Column(name = "firebase_token", length = 500)
    private String firebaseToken;

    // ─── Login tracking ───────────────────────────────────
    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    // ─── Timestamps / Audit ───────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

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

    // ─── Getters ──────────────────────────────────────────
    public Long          getId()                   { return id; }
    public String        getUsername()             { return username; }
    public String        getPasswordHash()         { return passwordHash; }
    public String        getFirstName()            { return firstName; }
    public String        getLastName()             { return lastName; }
    public String        getFullName()             { return fullName; }
    public String        getEmail()                { return email; }
    public String        getPhone()                { return phone; }
    public String        getPhoneNumber()          { return phoneNumber; }
    public Role          getRole()                 { return role; }
    public UserStatus    getStatus()               { return status; }
    public Boolean       getIsActive()             { return isActive; }
    public int           getFailedLoginAttempts()  { return failedLoginAttempts; }
    public boolean       isForcePasswordChange()   { return forcePasswordChange; }
    public LocalDateTime getAccountLockedUntil()   { return accountLockedUntil; }
    public LocalDateTime getPasswordChangedAt()    { return passwordChangedAt; }
    public Language      getPreferredLanguage()    { return preferredLanguage; }
    public Long          getBranchId()             { return branchId; }
    public String        getBranchName()           { return branchName; }
    public String        getFullNameNic()           { return fullNameNic; }
    public String        getNicNumber()             { return nicNumber; }
    public String        getAddress()               { return address; }
    public String        getSubscriptionNumber()    { return subscriptionNumber; }
    public String        getProfilePhotoUrl()       { return profilePhotoUrl; }
    public Long          getWorkgroupId()           { return workgroupId; }
    public String        getFcmToken()              { return fcmToken; }
    public String        getFirebaseToken()         { return firebaseToken; }
    public LocalDateTime getLastLogin()             { return lastLogin; }
    public LocalDateTime getLastLoginAt()           { return lastLoginAt; }
    public String        getLastLoginIp()           { return lastLoginIp; }
    public LocalDateTime getCreatedAt()             { return createdAt; }
    public LocalDateTime getUpdatedAt()             { return updatedAt; }
    public Long          getCreatedBy()             { return createdBy; }
    public Long          getUpdatedBy()             { return updatedBy; }

    // ─── Setters ──────────────────────────────────────────
    public void setId(Long v)                          { this.id                  = v; }
    public void setUsername(String v)                  { this.username            = v; }
    public void setPasswordHash(String v)              { this.passwordHash        = v; }
    public void setFirstName(String v)                 { this.firstName           = v; }
    public void setLastName(String v)                  { this.lastName            = v; }
    public void setFullName(String v)                  { this.fullName            = v; }
    public void setEmail(String v)                     { this.email               = v; }
    public void setPhone(String v)                     { this.phone               = v; }
    public void setPhoneNumber(String v)               { this.phoneNumber         = v; }
    public void setRole(Role v)                        { this.role                = v; }
    public void setStatus(UserStatus v)                { this.status              = v; }
    public void setIsActive(Boolean v)                 { this.isActive            = v; }
    public void setFailedLoginAttempts(int v)          { this.failedLoginAttempts = v; }
    public void setForcePasswordChange(boolean v)      { this.forcePasswordChange = v; }
    public void setAccountLockedUntil(LocalDateTime v) { this.accountLockedUntil  = v; }
    public void setPasswordChangedAt(LocalDateTime v)  { this.passwordChangedAt   = v; }
    public void setPreferredLanguage(Language v)       { this.preferredLanguage   = v; }
    public void setBranchId(Long v)                    { this.branchId            = v; }
    public void setBranchName(String v)                { this.branchName          = v; }
    public void setFullNameNic(String v)               { this.fullNameNic         = v; }
    public void setNicNumber(String v)                 { this.nicNumber           = v; }
    public void setAddress(String v)                   { this.address             = v; }
    public void setSubscriptionNumber(String v)        { this.subscriptionNumber  = v; }
    public void setProfilePhotoUrl(String v)           { this.profilePhotoUrl     = v; }
    public void setWorkgroupId(Long v)                 { this.workgroupId         = v; }
    public void setFcmToken(String v)                  { this.fcmToken            = v; }
    public void setFirebaseToken(String v)             { this.firebaseToken       = v; }
    public void setLastLogin(LocalDateTime v)          { this.lastLogin           = v; }
    public void setLastLoginAt(LocalDateTime v)        { this.lastLoginAt         = v; }
    public void setLastLoginIp(String v)               { this.lastLoginIp         = v; }
    public void setCreatedBy(Long v)                   { this.createdBy           = v; }
    public void setUpdatedBy(Long v)                   { this.updatedBy           = v; }
}
