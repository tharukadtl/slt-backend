package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JobTimerLog — issue #23 / JOB-009 (FR-8): the work timer, persisted.
 *
 * One row per worked interval. Pausing closes the currently open row (sets {@code stoppedAt});
 * resuming opens a new row — so a start -> pause -> resume -> pause cycle leaves exactly two
 * completed rows for the job, whose {@code durationSeconds} sum to the total worked time. Mirrors
 * {@link CheckInOut}/{@link MaterialUsage}'s one-row-per-event shape rather than mutable
 * multi-state columns on a single row.
 */
@Entity
@Table(name = "job_timer_logs",
        indexes = {
                @Index(name = "idx_job_timer_job", columnList = "job_id"),
                @Index(name = "idx_job_timer_open", columnList = "job_id, stopped_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobTimerLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** Null while this interval is the currently running one. */
    @Column(name = "stopped_at")
    private LocalDateTime stoppedAt;

    /** Stamped when the interval closes (stoppedAt - startedAt); null while running. */
    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
