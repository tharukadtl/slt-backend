package lk.slt.fieldops.notification.dto;

import java.time.LocalDateTime;

/**
 * NotificationDTO — returned in API responses for the notification list.
 * Never return the Notification entity directly from the controller.
 *
 * Used by:
 *   GET /api/notifications
 *   GET /api/notifications/unread
 */
public class NotificationDTO {

    private Long          id;
    private Long          recipientId;
    private String        type;
    private String        title;
    private String        body;
    private Long          referenceId;
    private String        referenceType;
    private Boolean       isRead;
    private LocalDateTime readAt;
    private Boolean       isPushSent;
    private LocalDateTime createdAt;
    private String        timeAgo;    // human-readable e.g. "5 minutes ago"

    public NotificationDTO() {}

    public Long          getId()            { return id; }
    public Long          getRecipientId()   { return recipientId; }
    public String        getType()          { return type; }
    public String        getTitle()         { return title; }
    public String        getBody()          { return body; }
    public Long          getReferenceId()   { return referenceId; }
    public String        getReferenceType() { return referenceType; }
    public Boolean       getIsRead()        { return isRead; }
    public LocalDateTime getReadAt()        { return readAt; }
    public Boolean       getIsPushSent()    { return isPushSent; }
    public LocalDateTime getCreatedAt()     { return createdAt; }
    public String        getTimeAgo()       { return timeAgo; }

    public void setId(Long v)                  { this.id            = v; }
    public void setRecipientId(Long v)         { this.recipientId   = v; }
    public void setType(String v)              { this.type          = v; }
    public void setTitle(String v)             { this.title         = v; }
    public void setBody(String v)              { this.body          = v; }
    public void setReferenceId(Long v)         { this.referenceId   = v; }
    public void setReferenceType(String v)     { this.referenceType = v; }
    public void setIsRead(Boolean v)           { this.isRead        = v; }
    public void setReadAt(LocalDateTime v)     { this.readAt        = v; }
    public void setIsPushSent(Boolean v)       { this.isPushSent    = v; }
    public void setCreatedAt(LocalDateTime v)  { this.createdAt     = v; }
    public void setTimeAgo(String v)           { this.timeAgo       = v; }
}
