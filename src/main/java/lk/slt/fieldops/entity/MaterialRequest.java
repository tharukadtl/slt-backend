package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "material_requests",
        indexes = {
                @Index(name = "idx_mat_req_requested_by", columnList = "requested_by"),
                @Index(name = "idx_mat_req_status",       columnList = "status"),
                @Index(name = "idx_mat_req_created",      columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialRequest {

    public enum RequestStatus {
        PENDING, APPROVED, REJECTED, FULFILLED, DELIVERED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_number", unique = true, length = 20)
    private String requestNumber;

    // ─── Material ─────────────────────────────────────────
    // Nullable: the live multi-item submission flow (MaterialRequestService)
    // packs items into itemsData instead of a single materialId. Only the
    // legacy single-item path (InventoryService) populates this field.
    @Column(name = "material_id")
    private Long materialId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    // ─── OPMC / Work Group ─────────────────────────────────
    @Column(name = "opmc_id")
    private Long opmcId;

    // Stage D (SRS 5.5.3): the requester's Work Group at submission time. Set for
    // Technician/Team Lead requesters (who always belong to one); null for a
    // requester with no Work Group, in which case approval falls back to the
    // pre-Stage-D direct-from-OPMC-pool behavior (see MaterialRequestService).
    @Column(name = "work_group_id")
    private Long workGroupId;

    // ─── Requester (flat ID) ──────────────────────────────
    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_by_name", length = 150)
    private String requestedByName;

    // ─── Reviewer (flat ID) ───────────────────────────────
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_by_name", length = 150)
    private String reviewedByName;

    // ─── Job/Fault reference ──────────────────────────────
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "fault_id", length = 50)
    private String faultId;

    @Column(name = "task_id", length = 50)
    private String taskId;

    // ─── Request details ──────────────────────────────────
    @Column(name = "quantity_requested", precision = 10, scale = 3)
    private BigDecimal quantityRequested;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "urgency", length = 20)
    @Builder.Default
    private String urgency = "NORMAL";

    @Column(name = "items_data", columnDefinition = "TEXT")
    private String itemsData;

    @Column(name = "requester_notes", length = 1000)
    private String requesterNotes;

    @Column(name = "reviewer_notes", length = 1000)
    private String reviewerNotes;

    @Column(name = "total_estimated_cost")
    private Double totalEstimatedCost;

    @Column(name = "total_approved_cost")
    private Double totalApprovedCost;

    // ─── Status ───────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // ─── Timestamps ───────────────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (requestNumber == null) {
            requestNumber = "MR-" + System.currentTimeMillis();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
