package lk.slt.fieldops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** JOB-014 — job notes, mirroring FaultAssignmentDTO's AddNoteRequest/FaultNoteResponse shape. */
public class JobNoteDTO {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddNoteRequest {

        @NotBlank(message = "Note content is required")
        private String content;

        // KPI-003's exact bug shape: Lombok exposes a boolean field already prefixed "is" as
        // getter isInternal()/setter setInternal(...), so plain Jackson binds the JSON property
        // "internal", not "isInternal" — the real request body's own key. Pinned explicitly
        // rather than relying on AppConfig's ObjectMapper fix (a separate, not-yet-merged PR).
        @JsonProperty("isInternal")
        private boolean isInternal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NoteResponse {
        private Long id;
        private Long jobId;
        private String content;

        @JsonProperty("isInternal")
        private boolean isInternal;
        private Long authorId;
        private String authorName;
        private String authorRole;
        private LocalDateTime createdAt;
    }
}
