package lk.slt.fieldops.dto;

import java.time.LocalDateTime;

public class UserDTO {

    private Long          id;
    private String        username;
    private String        fullName;
    private String        email;
    private String        phone;
    private String        role;
    private Long          opmcId;
    private String        opmcName;
    private Long          workgroupId;
    private String        workgroupName;
    private Boolean       isActive;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
    private String        language;
    private NotificationPreferencesDTO notificationPreferences;

    public UserDTO() {}

    public Long          getId()         { return id; }
    public String        getUsername()   { return username; }
    public String        getFullName()   { return fullName; }
    public String        getEmail()      { return email; }
    public String        getPhone()      { return phone; }
    public String        getRole()       { return role; }
    public Long          getOpmcId()     { return opmcId; }
    public String        getOpmcName()  { return opmcName; }
    public Long          getWorkgroupId()   { return workgroupId; }
    public String        getWorkgroupName() { return workgroupName; }
    public Boolean       getIsActive()   { return isActive; }
    public LocalDateTime getLastLogin()  { return lastLogin; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public String        getLanguage()   { return language; }
    public NotificationPreferencesDTO getNotificationPreferences() { return notificationPreferences; }

    public void setId(Long v)                 { this.id         = v; }
    public void setUsername(String v)         { this.username   = v; }
    public void setFullName(String v)         { this.fullName   = v; }
    public void setEmail(String v)            { this.email      = v; }
    public void setPhone(String v)            { this.phone      = v; }
    public void setRole(String v)             { this.role       = v; }
    public void setOpmcId(Long v)             { this.opmcId     = v; }
    public void setOpmcName(String v)         { this.opmcName   = v; }
    public void setWorkgroupId(Long v)        { this.workgroupId   = v; }
    public void setWorkgroupName(String v)    { this.workgroupName = v; }
    public void setIsActive(Boolean v)        { this.isActive   = v; }
    public void setLastLogin(LocalDateTime v) { this.lastLogin  = v; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt  = v; }
    public void setLanguage(String v)         { this.language   = v; }
    public void setNotificationPreferences(NotificationPreferencesDTO v) { this.notificationPreferences = v; }
}
