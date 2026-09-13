package lk.slt.fieldops.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** JOB-009 — work-timer interval, returned by GET /api/jobs/{id}/timer. */
public class JobTimerDTO {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogResponse {
        private Long id;
        private Long jobId;
        private LocalDateTime startedAt;
        private LocalDateTime stoppedAt;
        private Long durationSeconds;
    }
}
