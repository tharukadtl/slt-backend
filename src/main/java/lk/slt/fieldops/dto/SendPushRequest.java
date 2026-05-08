package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * SendPushRequest — Admin/System manually sends a push notification.
 *
 * POST /api/notifications/push
 * {
 *   "recipientId": 7,
 *   "fcmToken":    "fcm_token_from_device",
 *   "title":       "Maintenance Alert",
 *   "body":        "System maintenance at 2 AM tonight."
 * }
 */
public class SendPushRequest {

    @NotNull(message = "Recipient ID is required")
    private Long   recipientId;

    @NotBlank(message = "FCM token is required")
    private String fcmToken;

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Body is required")
    private String body;

    private String referenceType;
    private Long   referenceId;

    public SendPushRequest() {}

    public Long   getRecipientId()   { return recipientId; }
    public String getFcmToken()      { return fcmToken; }
    public String getTitle()         { return title; }
    public String getBody()          { return body; }
    public String getReferenceType() { return referenceType; }
    public Long   getReferenceId()   { return referenceId; }

    public void setRecipientId(Long v)    { this.recipientId   = v; }
    public void setFcmToken(String v)     { this.fcmToken      = v; }
    public void setTitle(String v)        { this.title         = v; }
    public void setBody(String v)         { this.body          = v; }
    public void setReferenceType(String v){ this.referenceType = v; }
    public void setReferenceId(Long v)    { this.referenceId   = v; }
}
