package lk.slt.fieldops.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignRequest {

        @NotNull(message = "Team Lead ID is required")
        @JsonAlias("teamLeadId")
        private Long technicianId;

        private String priority;
        private String scheduledDate;
        private Integer estimatedDurationHours;
        private String notes;
        private boolean notifyTechnician;
        private boolean notifyCustomer;
    }

    // ─── Reassign Request ─────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReassignRequest {

        @NotNull(message = "New Team Lead ID is required")
        @JsonAlias("newTeamLeadId")
        private Long newTechnicianId;

        @NotBlank(message =
                "Reason is required")
        private String reason;

        private String notes;
        private boolean notifyTechnician;
        private boolean notifyCustomer;
        private boolean notifyPreviousTechnician;
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
        private boolean notifyAdmin;
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

        @NotNull(message = "Team Lead ID is required")
        @JsonAlias("teamLeadId")
        private Long technicianId;

        private String priority;
        private String notes;
        private boolean notifyTechnician;
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
        private Long technicianId;
        private String technicianName;
        private String technicianPhone;
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