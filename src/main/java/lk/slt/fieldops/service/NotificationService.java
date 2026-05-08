package lk.slt.fieldops.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import lk.slt.fieldops.dto.NotificationCountDTO;
import lk.slt.fieldops.dto.NotificationDTO;
import lk.slt.fieldops.entity.Notification;
import lk.slt.fieldops.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * NotificationService — two delivery channels.
 *
 *   ① sendPush(fcmToken, title, body)
 *        → Firebase Cloud Messaging push to device
 *
 *   ② saveInApp(recipientId, type, title, body, referenceId, referenceType)
 *        → Persists to `notifications` table (in-app bell icon)
 *
 * Firebase Setup (when ready):
 *   1. Add to pom.xml:
 *      <dependency>
 *          <groupId>com.google.firebase</groupId>
 *          <artifactId>firebase-admin</artifactId>
 *          <version>9.2.0</version>
 *      </dependency>
 *
 *   2. Download serviceAccountKey.json from Firebase Console
 *      → Project Settings → Service Accounts → Generate New Private Key
 *      → Place file at: src/main/resources/serviceAccountKey.json
 *
 *   3. Add to application.yml:
 *      firebase:
 *        service-account-path: classpath:serviceAccountKey.json
 *
 *   4. Uncomment the Firebase blocks marked TODO_FIREBASE below.
 */
@Service
public class NotificationService {

    private static final Logger log = Logger.getLogger(NotificationService.class.getName());

    private final NotificationRepository notificationRepo;

    @Value("${app.firebase.enabled:false}")
    private boolean firebaseEnabled;

    public NotificationService(NotificationRepository notificationRepo) {
        this.notificationRepo = notificationRepo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // METHOD 1: sendPush — Firebase push notification to device
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Sends a Firebase push notification to one device.
     *
     * @param fcmToken  device FCM token (stored in users.fcm_token column)
     * @param title     notification title shown on device
     * @param body      notification body text
     */
    public void sendPush(String fcmToken, String title, String body) {
        if (fcmToken == null || fcmToken.isBlank()) {
            log.warning("sendPush skipped — FCM token is null or blank.");
            return;
        }

        if (!firebaseEnabled || FirebaseApp.getApps().isEmpty()) {
            log.info("[PUSH-DISABLED] token=" + fcmToken.substring(0, Math.min(8, fcmToken.length())) +
                     "... | title=" + title);
            return;
        }

        try {
            Message message = Message.builder()
                .setToken(fcmToken)
                .setNotification(
                    com.google.firebase.messaging.Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .build();
            String response = FirebaseMessaging.getInstance().send(message);
            log.info("Push sent OK. FCM response: " + response);
        } catch (FirebaseMessagingException e) {
            log.severe("Firebase push failed: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // METHOD 2: saveInApp — persist to DB for in-app notification list
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Saves a notification to the `notifications` table.
     *
     * @param recipientId   ID of the user who should see this notification
     * @param type          one of the NotificationType enum values
     * @param title         short title (max 200 chars)
     * @param body          full message
     * @param referenceId   the related entity ID (jobId, faultId, paymentId, etc.)
     * @param referenceType "JOB", "FAULT", "PAYMENT", "MATERIAL_REQUEST", etc.
     */
    @Transactional
    public Notification saveInApp(Long recipientId,
                                   Notification.NotificationType type,
                                   String title,
                                   String body,
                                   Long referenceId,
                                   String referenceType) {
        Notification n = new Notification();
        n.setRecipientId(recipientId);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setReferenceId(referenceId);
        n.setReferenceType(referenceType);
        n.setIsRead(false);
        n.setIsPushSent(false);
        return notificationRepo.save(n);
    }

    /**
     * Sends BOTH a push AND saves in-app in one call.
     * Most common pattern — use this unless you need only one channel.
     */
    @Transactional
    public void notifyUser(Long recipientId,
                            String fcmToken,
                            Notification.NotificationType type,
                            String title,
                            String body,
                            Long referenceId,
                            String referenceType) {
        saveInApp(recipientId, type, title, body, referenceId, referenceType);
        sendPush(fcmToken, title, body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COMMON EVENT HELPERS — call from FaultService, JobService, PaymentService
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public void notifyFaultAssigned(Long teamLeadId, String tlFcmToken,
                                     String faultNumber, Long faultId) {
        notifyUser(teamLeadId, tlFcmToken,
            Notification.NotificationType.FAULT_ASSIGNED,
            "Fault Assigned to You",
            "Fault #" + faultNumber + " has been assigned to your team.",
            faultId, "FAULT");
    }

    @Transactional
    public void notifyJobAssigned(Long technicianId, String techFcmToken,
                                   String jobNumber, Long jobId) {
        notifyUser(technicianId, techFcmToken,
            Notification.NotificationType.JOB_ASSIGNED,
            "New Job Assigned",
            "You have been assigned Job #" + jobNumber + ". Please accept to begin.",
            jobId, "JOB");
    }

    @Transactional
    public void notifyJobCompleted(Long teamLeadId, String tlFcmToken,
                                    String jobNumber, Long jobId) {
        notifyUser(teamLeadId, tlFcmToken,
            Notification.NotificationType.JOB_COMPLETED,
            "Job Completed",
            "Job #" + jobNumber + " has been marked as completed.",
            jobId, "JOB");
    }

    @Transactional
    public void notifyJobRejectedToTeamLead(Long teamLeadId, String tlFcmToken,
                                             String jobNumber, Long jobId, String reason) {
        notifyUser(teamLeadId, tlFcmToken,
            Notification.NotificationType.GENERAL,
            "Job Rejected by Technician",
            "Job #" + jobNumber + " was rejected. Reason: " + reason + ". Please reassign.",
            jobId, "JOB");
    }

    @Transactional
    public void notifyFaultCompletedToClient(Long customerId, String customerFcmToken,
                                              String faultNumber, Long faultId) {
        notifyUser(customerId, customerFcmToken,
            Notification.NotificationType.FAULT_COMPLETED,
            "Fault Resolved ✓",
            "Your reported fault #" + faultNumber + " has been resolved. Thank you for your patience.",
            faultId, "FAULT");
    }

    @Transactional
    public void notifyPaymentApproved(Long teamLeadId, String tlFcmToken,
                                       String paymentNumber, Long paymentId) {
        notifyUser(teamLeadId, tlFcmToken,
            Notification.NotificationType.PAYMENT_APPROVED,
            "Payment Approved",
            "Payment #" + paymentNumber + " has been approved and billed.",
            paymentId, "PAYMENT");
    }

    @Transactional
    public void notifyPaymentRejected(Long teamLeadId, String tlFcmToken,
                                       String paymentNumber, Long paymentId,
                                       String reason) {
        notifyUser(teamLeadId, tlFcmToken,
            Notification.NotificationType.PAYMENT_REJECTED,
            "Payment Rejected",
            "Payment #" + paymentNumber + " was rejected. Reason: " + reason,
            paymentId, "PAYMENT");
    }

    @Transactional
    public void notifyLowStock(Long adminId, String materialName, Long materialId) {
        // Low stock is in-app only — no push
        saveInApp(adminId,
            Notification.NotificationType.LOW_STOCK_ALERT,
            "Low Stock Alert",
            "'" + materialName + "' stock is at or below minimum threshold. Please restock.",
            materialId, "MATERIAL");
    }

    @Transactional
    public void notifyVehicleExpiry(Long adminId, String vehicleReg,
                                     Long vehicleId, int daysLeft) {
        saveInApp(adminId,
            Notification.NotificationType.VEHICLE_EXPIRY_ALERT,
            "Vehicle Document Expiring",
            "Vehicle " + vehicleReg + " has a document expiring in " + daysLeft + " days.",
            vehicleId, "VEHICLE");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // READ METHODS — return DTOs
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<NotificationDTO> getNotificationsForUser(Long userId) {
        return notificationRepo.findByRecipientIdOrderByCreatedAtDesc(userId)
            .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<NotificationDTO> getUnreadForUser(Long userId) {
        return notificationRepo.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(userId)
            .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public NotificationCountDTO getUnreadCount(Long userId) {
        long unread = notificationRepo.countByRecipientIdAndIsReadFalse(userId);
        long total  = notificationRepo.findByRecipientIdOrderByCreatedAtDesc(userId).size();
        return new NotificationCountDTO(unread, total);
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepo.markAllAsRead(userId);
    }

    @Transactional
    public NotificationDTO markOneRead(Long notificationId) {
        Notification n = notificationRepo.findById(notificationId)
            .orElseThrow(() -> new RuntimeException(
                "Notification not found with id: " + notificationId));
        n.setIsRead(true);
        n.setReadAt(LocalDateTime.now());
        return mapToDTO(notificationRepo.save(n));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MAPPER — Notification entity → NotificationDTO
    // ══════════════════════════════════════════════════════════════════════════

    public NotificationDTO mapToDTO(Notification n) {
        NotificationDTO dto = new NotificationDTO();
        dto.setId(n.getId());
        dto.setRecipientId(n.getRecipientId());
        dto.setType(n.getType() != null ? n.getType().name() : null);
        dto.setTitle(n.getTitle());
        dto.setBody(n.getBody());
        dto.setReferenceId(n.getReferenceId());
        dto.setReferenceType(n.getReferenceType());
        dto.setIsRead(n.getIsRead());
        dto.setReadAt(n.getReadAt());
        dto.setIsPushSent(n.getIsPushSent());
        dto.setCreatedAt(n.getCreatedAt());
        dto.setTimeAgo(buildTimeAgo(n.getCreatedAt()));
        return dto;
    }

    /**
     * Returns human-readable "time ago" string.
     * e.g. "2 minutes ago", "3 hours ago", "Yesterday"
     */
    private String buildTimeAgo(LocalDateTime createdAt) {
        if (createdAt == null) return "";
        LocalDateTime now     = LocalDateTime.now();
        long          minutes = ChronoUnit.MINUTES.between(createdAt, now);
        long          hours   = ChronoUnit.HOURS.between(createdAt, now);
        long          days    = ChronoUnit.DAYS.between(createdAt, now);

        if (minutes < 1)  return "Just now";
        if (minutes < 60) return minutes + " minute" + (minutes == 1 ? "" : "s") + " ago";
        if (hours   < 24) return hours   + " hour"   + (hours   == 1 ? "" : "s") + " ago";
        if (days    == 1) return "Yesterday";
        return days + " days ago";
    }
}
