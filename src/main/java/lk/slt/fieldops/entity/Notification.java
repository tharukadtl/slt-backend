package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Notification.java — maps to `notifications` table.
 * In-app notifications for the bell icon.
 * Push notifications go directly to Firebase (NotificationService.sendPush).
 */
@Entity
@Table(name = "notifications")
public class Notification {

    public enum NotificationType {
        FAULT_REPORTED, FAULT_ASSIGNED, FAULT_IN_PROGRESS,
        FAULT_COMPLETED, FAULT_ON_HOLD, FAULT_CANCELLED,
        JOB_ASSIGNED, JOB_ACCEPTED, JOB_COMPLETED, JOB_ON_HOLD,
        PAYMENT_SUBMITTED, PAYMENT_APPROVED, PAYMENT_REJECTED,
        MATERIAL_REQUEST_SUBMITTED, MATERIAL_REQUEST_APPROVED,
        LOW_STOCK_ALERT, VEHICLE_EXPIRY_ALERT, GENERAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(50)")
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "is_push_sent", nullable = false)
    private Boolean isPushSent = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public Notification() {}

    public Long             getId()            { return id; }
    public Long             getRecipientId()   { return recipientId; }
    public NotificationType getType()          { return type; }
    public String           getTitle()         { return title; }
    public String           getBody()          { return body; }
    public Long             getReferenceId()   { return referenceId; }
    public String           getReferenceType() { return referenceType; }
    public Boolean          getIsRead()        { return isRead; }
    public LocalDateTime    getReadAt()        { return readAt; }
    public Boolean          getIsPushSent()    { return isPushSent; }
    public LocalDateTime    getCreatedAt()     { return createdAt; }

    public void setId(Long v)                      { this.id            = v; }
    public void setRecipientId(Long v)             { this.recipientId   = v; }
    public void setType(NotificationType v)        { this.type          = v; }
    public void setTitle(String v)                 { this.title         = v; }
    public void setBody(String v)                  { this.body          = v; }
    public void setReferenceId(Long v)             { this.referenceId   = v; }
    public void setReferenceType(String v)         { this.referenceType = v; }
    public void setIsRead(Boolean v)               { this.isRead        = v; }
    public void setReadAt(LocalDateTime v)         { this.readAt        = v; }
    public void setIsPushSent(Boolean v)           { this.isPushSent    = v; }
}
