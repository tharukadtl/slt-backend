package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * WorkGroupAllocation — SRS 5.5.3 (v1.9): how much of one Material an OPMC Admin
 * has allocated to one Work Group, still available for that Work Group's Team
 * Lead to distribute to themself or a Technician. The OPMC-level pool is
 * {@code Material.currentStock} itself (already scoped by {@code Material.opmcId}
 * since Stage B) — this table is only the middle tier the hierarchy adds.
 */
@Entity
@Table(name = "work_group_allocations",
        uniqueConstraints = @UniqueConstraint(name = "uk_wg_allocation_material",
                columnNames = {"work_group_id", "material_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkGroupAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_group_id", nullable = false)
    private Long workGroupId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "allocated_quantity", precision = 12, scale = 3, nullable = false)
    @Builder.Default
    private BigDecimal allocatedQuantity = BigDecimal.ZERO;

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
