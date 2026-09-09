package lk.slt.fieldops.dto;

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
        private boolean isInternal;
        private Long authorId;
        private String authorName;
        private String authorRole;
        private LocalDateTime createdAt;
    }
}
