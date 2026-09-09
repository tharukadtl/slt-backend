package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JobNote.java — maps to `job_notes` table.
 *
 * JOB-014 — one row per job note, with an is_internal flag distinguishing notes visible to
 * the CLIENT from ones meant only for staff, mirroring the existing FaultNote feature.
 */
@Entity
@Table(name = "job_notes",
        indexes = {
                @Index(name = "idx_job_note_job",    columnList = "job_id"),
                @Index(name = "idx_job_note_author", columnList = "added_by")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "added_by", nullable = false)
    private Long addedBy;

    @Column(name = "added_by_name", length = 150)
    private String addedByName;

    @Column(name = "added_by_role", length = 30)
    private String addedByRole;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Column(name = "is_internal")
    @Builder.Default
    private Boolean isInternal = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
