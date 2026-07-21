package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * UserAuditLog.java — maps to `user_audit_log` table.
 * Immutable record of admin-initiated account changes, for REP-10 (Audit Trail Report).
 */
@Entity
@Table(name = "user_audit_log",
        indexes = {
                @Index(name = "idx_user_audit_target", columnList = "target_user_id"),
                @Index(name = "idx_user_audit_created", columnList = "created_at")
        })
public class UserAuditLog {

    public enum Action { CREATED, ROLE_CHANGED, DETAILS_UPDATED, DEACTIVATED, PASSWORD_RESET, ACCESS_RESET }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Column(name = "target_user_name", length = 150)
    private String targetUserName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Action action;

    @Column(name = "performed_by")
    private Long performedBy;

    @Column(name = "performed_by_name", length = 150)
    private String performedByName;

    @Column(name = "performed_by_role", length = 30)
    private String performedByRole;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "previous_value", length = 200)
    private String previousValue;

    @Column(name = "new_value", length = 200)
    private String newValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public UserAuditLog() {}

    public Long          getId()              { return id; }
    public Long          getTargetUserId()    { return targetUserId; }
    public String        getTargetUserName()  { return targetUserName; }
    public Action        getAction()          { return action; }
    public Long          getPerformedBy()     { return performedBy; }
    public String        getPerformedByName() { return performedByName; }
    public String        getPerformedByRole() { return performedByRole; }
    public String        getIpAddress()       { return ipAddress; }
    public String        getPreviousValue()   { return previousValue; }
    public String        getNewValue()        { return newValue; }
    public LocalDateTime getCreatedAt()       { return createdAt; }

    public void setId(Long v)                   { this.id              = v; }
    public void setTargetUserId(Long v)         { this.targetUserId    = v; }
    public void setTargetUserName(String v)     { this.targetUserName  = v; }
    public void setAction(Action v)             { this.action          = v; }
    public void setPerformedBy(Long v)          { this.performedBy     = v; }
    public void setPerformedByName(String v)    { this.performedByName = v; }
    public void setPerformedByRole(String v)    { this.performedByRole = v; }
    public void setIpAddress(String v)          { this.ipAddress       = v; }
    public void setPreviousValue(String v)      { this.previousValue   = v; }
    public void setNewValue(String v)           { this.newValue        = v; }
}
