package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "fault_notes",
        indexes = {
                @Index(name = "idx_fault_note_fault",  columnList = "fault_id"),
                @Index(name = "idx_fault_note_author", columnList = "added_by"),
                @Index(name = "idx_fault_note_time",   columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaultNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Fault reference (flat ID) ────────────────────────
    @Column(name = "fault_id", nullable = false)
    private Long faultId;

    // ─── Author (flat ID) ─────────────────────────────────
    @Column(name = "added_by", nullable = false)
    private Long addedBy;

    @Column(name = "added_by_name", length = 150)
    private String addedByName;

    // ─── Note Content ─────────────────────────────────────
    @Column(name = "note", nullable = false, length = 2000)
    private String note;

    @Column(name = "note_type", length = 30)
    @Builder.Default
    private String noteType = "GENERAL";

    @Column(name = "is_internal")
    @Builder.Default
    private Boolean isInternal = false;

    @Column(name = "attachments", length = 1000)
    private String attachments;

    // ─── Timestamps ───────────────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
