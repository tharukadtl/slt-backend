package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "technician_locations",
        indexes = {
                @Index(name = "idx_tech_location_user",
                        columnList = "user_id"),
                @Index(name = "idx_tech_location_updated",
                        columnList = "last_updated"),
                @Index(name = "idx_tech_location_active",
                        columnList = "is_active")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicianLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Technician Reference ─────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",
            nullable = false)
    private User user;

    // ─── GPS Coordinates ──────────────────────────────────
    @Column(name = "latitude",
            nullable = false,
            precision = 10)
    private Double latitude;

    @Column(name = "longitude",
            nullable = false,
            precision = 10)
    private Double longitude;

    // ─── Address ──────────────────────────────────────────
    @Column(name = "address",
            length = 500)
    private String address;

    // ─── Movement Data ────────────────────────────────────
    @Column(name = "speed")
    private Double speed;

    @Column(name = "heading")
    private Double heading;

    @Column(name = "accuracy")
    private Double accuracy;

    // ─── Job Reference ────────────────────────────────────
    @Column(name = "current_job_id")
    private String currentJobId;

    // ─── Status ───────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "technician_status",
            length = 30)
    private TechnicianStatus technicianStatus;

    @Column(name = "is_active",
            nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    // ─── Timestamps ───────────────────────────────────────
    @Column(name = "last_updated",
            nullable = false)
    private LocalDateTime lastUpdated;

    @Column(name = "created_at",
            nullable = false,
            updatable = false)
    private LocalDateTime createdAt;

    // ─── Lifecycle ────────────────────────────────────────
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastUpdated = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdated = LocalDateTime.now();
    }

    // ─── Status Enum ──────────────────────────────────────
    public enum TechnicianStatus {
        AVAILABLE,
        ON_JOB,
        TRAVELLING,
        BREAK,
        CHECKED_OUT,
        OFFLINE
    }
}