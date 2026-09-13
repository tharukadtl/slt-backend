package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JobPhoto.java — maps to `job_photos` table.
 *
 * JOB-006/JOB-015 — one row per before/after job photo, replacing the comma-separated
 * jobs.completion_photo_urls TEXT column (a 1NF violation).
 *
 * A photo is persisted with {@code jobId = null} at upload time (POST /api/uploads/photos
 * has no job to attach to yet), then "claimed" (jobId set) when its URL is submitted as
 * part of a job's completionPhotoUrls at COMPLETED — see JobService.updateJobStatus.
 */
@Entity
@Table(name = "job_photos")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobPhoto {

    public enum PhotoType { BEFORE, AFTER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null until claimed by a job at completion time. */
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_type", nullable = false, length = 20)
    private PhotoType photoType;

    @Column(name = "uploaded_at", updatable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }
}
