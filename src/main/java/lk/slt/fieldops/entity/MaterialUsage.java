package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "material_usage",
        indexes = {
                @Index(name = "idx_mat_usage_job",      columnList = "job_id"),
                @Index(name = "idx_mat_usage_material", columnList = "material_id"),
                @Index(name = "idx_mat_usage_created",  columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialUsage {

    public enum ChargeType { FOC, CHARGEABLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "job_number", length = 30)
    private String jobNumber;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @Column(name = "quantity_used", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityUsed;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "total_cost", precision = 12, scale = 2)
    private BigDecimal totalCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", length = 20)
    @Builder.Default
    private ChargeType chargeType = ChargeType.FOC;

    @Column(name = "justification", length = 500)
    private String justification;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
