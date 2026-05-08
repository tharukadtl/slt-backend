package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "check_in_out",
        indexes = {
                @Index(
                        name = "idx_checkinout_user",
                        columnList = "user_id"),
                @Index(
                        name = "idx_checkinout_time",
                        columnList = "check_in_time"),
                @Index(
                        name = "idx_checkinout_status",
                        columnList = "status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckInOut {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── User Reference ───────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",
            nullable = false)
    private User user;

    // ─── Check-In ─────────────────────────────────────────
    @Column(name = "check_in_time")
    private LocalDateTime checkInTime;

    @Column(name = "check_in_latitude")
    private Double checkInLatitude;

    @Column(name = "check_in_longitude")
    private Double checkInLongitude;

    @Column(name = "check_in_address",
            length = 500)
    private String checkInAddress;

    // ─── Check-Out ────────────────────────────────────────
    @Column(name = "check_out_time")
    private LocalDateTime checkOutTime;

    @Column(name = "check_out_latitude")
    private Double checkOutLatitude;

    @Column(name = "check_out_longitude")
    private Double checkOutLongitude;

    @Column(name = "check_out_address",
            length = 500)
    private String checkOutAddress;

    // ─── Day Session Link (nullable — individual tech check-ins have no session) ─
    @Column(name = "session_id")
    private Long sessionId;

    // ─── Team Lead Link (nullable — technician self-check-ins have no TL) ──────
    @Column(name = "team_lead_id")
    private Long teamLeadId;

    // ─── Check type — BOD (team lead) or ATTENDANCE (individual) ─────────────
    @Column(name = "check_type", length = 20, nullable = false)
    @Builder.Default
    private String checkType = "ATTENDANCE";

    // ─── Session Info ─────────────────────────────────────
    @Column(name = "status",
            length = 30)
    @Builder.Default
    private String status = "CHECKED_IN";

    @Column(name = "jobs_completed")
    private Integer jobsCompleted;

    @Column(name = "notes",
            length = 1000)
    private String notes;

    @Column(name = "device_info",
            length = 200)
    private String deviceInfo;

    // ─── Timestamps ───────────────────────────────────────
    @Column(name = "created_at",
            updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ─── Lifecycle ────────────────────────────────────────
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (checkInTime == null) {
            checkInTime = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}