package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class FaultAssignmentDTO {

    // ─── Assign Request ───────────────────────────────────
    // SRS 5.5.1 (v1.9) — Admin assigns a fault to a Work Group, never directly
    // to a person. Deliberately has no technicianId/targetType field at all: an
    // old-shape payload (e.g. {targetType:'TECHNICIAN', targetId}) fails bean
    // validation on the missing required workGroupId and is rejected outright,
    // rather than being silently accepted through a compatibility shim.
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignRequest {

        @NotNull(message = "Work Group ID is required")
        private Long workGroupId;

        private String priority;
        private String scheduledDate;
        private Integer estimatedDurationHours;
        private String notes;
        @Builder.Default
        private boolean notifyTeamLead = true;
        @Builder.Default
        private boolean notifyCustomer = true;
    }

    // ─── Reassign Request ─────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReassignRequest {

        @NotNull(message = "New Work Group ID is required")
        private Long newWorkGroupId;

        @NotBlank(message =
                "Reason is required")
        private String reason;

        private String notes;
        @Builder.Default
        private boolean notifyTeamLead = true;
        @Builder.Default
        private boolean notifyCustomer = true;
        @Builder.Default
        private boolean notifyPreviousTeamLead = true;
    }

    // ─── Self-Assign Request (SRS 5.5.1 — Team Lead "Assign to Me") ───────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelfAssignRequest {
        private String notes;
    }

    // ─── Transfer to Admin Request (SRS 5.5.1) ────────────────────────────
    // Mirrors the EOD "Forward to Admin" (5.4.2.1) and on-site Issue Mismatch
    // (5.3.1.2) escalation patterns: a mandatory reason, full audit trail.
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferToAdminRequest {

        @NotBlank(message = "A reason is required to transfer a fault back to Admin")
        private String reason;

        private String notes;
    }

    // ─── Escalate Request ─────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EscalateRequest {

        @NotBlank(message =
                "Escalation reason is required")
        private String reason;

        private String escalateTo;
        private String priority;
        private String notes;
        @Builder.Default
        private boolean notifyAdmin = true;
    }

    // ─── Bulk Assign Request ──────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAssignRequest {

        @NotEmpty(message =
                "Fault IDs are required")
        private List<Long> faultIds;

        @NotNull(message = "Work Group ID is required")
        private Long workGroupId;

        private String priority;
        private String notes;
        @Builder.Default
        private boolean notifyTeamLead = true;
    }

    // ─── Add Note Request ─────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddNoteRequest {

        @NotBlank(message =
                "Note content is required")
        private String content;

        private String noteType;
        private boolean isInternal;
        private List<String> attachments;
    }

    // ─── Assignment Response ──────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignmentResponse {
        private Long faultId;
        private String faultStatus;
        private Long workGroupId;
        private String workGroupName;
        private Long teamLeadId;
        private String teamLeadName;
        private String priority;
        private String scheduledDate;
        private Integer estimatedDurationHours;
        private String assignedBy;
        private LocalDateTime assignedAt;
        private String message;
        private boolean notificationSent;
    }

    // ─── Bulk Assign Response ─────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAssignResponse {
        private int totalRequested;
        private int successCount;
        private int failureCount;
        private List<Long> successFaultIds;
        private List<Long> failedFaultIds;
        private List<String> errors;
        private LocalDateTime processedAt;
    }

    // ─── Timeline Event ───────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineEventDTO {
        private Long id;
        private String eventType;
        private String eventIcon;
        private String eventColor;
        private String title;
        private String description;
        private String actorName;
        private String actorRole;
        private String previousValue;
        private String newValue;
        private LocalDateTime timestamp;
        private String timeAgo;
        private boolean isSystem;
    }

    // ─── Fault Note Response ──────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FaultNoteResponse {
        private Long id;
        private Long faultId;
        private String content;
        private String noteType;
        private boolean isInternal;
        private Long authorId;
        private String authorName;
        private String authorRole;
        private List<String> attachments;
        private LocalDateTime createdAt;
        private String timeAgo;
    }
}