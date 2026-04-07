package lk.slt.fieldops.notification.dto;

/**
 * NotificationCountDTO — returned by GET /api/notifications/count
 * Used for the notification bell badge in the mobile app.
 *
 * Response example:
 * {
 *   "unread":   3,
 *   "total":    25,
 *   "hasUnread": true
 * }
 */
public class NotificationCountDTO {

    private long    unread;
    private long    total;
    private boolean hasUnread;

    public NotificationCountDTO() {}

    public NotificationCountDTO(long unread, long total) {
        this.unread    = unread;
        this.total     = total;
        this.hasUnread = unread > 0;
    }

    public long    getUnread()    { return unread; }
    public long    getTotal()     { return total; }
    public boolean isHasUnread()  { return hasUnread; }

    public void setUnread(long v)     { this.unread    = v; this.hasUnread = v > 0; }
    public void setTotal(long v)      { this.total     = v; }
    public void setHasUnread(boolean v){ this.hasUnread = v; }
}
