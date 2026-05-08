package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "fault_history",
        indexes = {
                @Index(
                        name = "idx_fault_history_fault",
                        columnList = "fault_id"),
                @Index(
                        name = "idx_fault_history_time",
                        columnList = "created_at"),
                @Index(
                        name = "idx_fault_history_type",
                        columnList = "event_type")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaultHistory {

    public enum ChangedByRole { CLIENT, ADMIN, TEAM_LEAD, TECHNICIAN, SYSTEM }

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── Fault Reference ──────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fault_id",
            nullable = false)
    private Fault fault;

    @Column(name = "fault_number", nullable = false, length = 30)
    private String faultNumber;

    // ─── Actor ────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    // ─── Event Details ────────────────────────────────────
    @Column(name = "event_type",
            nullable = false,
            length = 50)
    private String eventType;

    @Column(name = "title",
            length = 200)
    private String title;

    @Column(name = "description",
            length = 1000)
    private String description;

    @Column(name = "previous_value",
            length = 200)
    private String previousValue;

    @Column(name = "new_value",
            length = 200)
    private String newValue;

    @Column(name = "is_system")
    @Builder.Default
    private Boolean isSystem = false;

    // ─── Timestamps ───────────────────────────────────────
    @Column(name = "created_at",
            nullable = false,
            updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}