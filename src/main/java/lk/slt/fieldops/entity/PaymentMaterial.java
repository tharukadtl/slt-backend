package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PaymentMaterial — the payment↔material junction (issue #28 / PAY-012/014/017).
 *
 * Mirrors {@link MaterialUsage}'s exact shape (plain FK-by-id columns, a snapshot price, a
 * {@code chargeType} enum, a free-text justification, {@code createdAt} via {@code @PrePersist}) —
 * that entity is a structurally identical, already-correct precedent for a different workflow
 * (technician job-material logging), reused here rather than a new parallel design.
 *
 * 2NF (PAY-014): carries ONLY quantity, the snapshot price and the charge classification — no
 * material name or "current" price. Those stay on {@code materials} and are reached through a
 * JOIN in {@code PaymentService.getPaymentWithDetails}, since they depend on material_id alone,
 * not the full (payment_id, material_id) key.
 */
@Entity
@Table(name = "payment_materials",
        indexes = {
                @Index(name = "idx_pay_mat_payment",  columnList = "payment_id"),
                @Index(name = "idx_pay_mat_material", columnList = "material_id"),
                @Index(name = "idx_pay_mat_created",  columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMaterial {

    public enum ChargeType { FOC, CHARGEABLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Price snapshot at billing time — NOT materials.unit_price, which can change later. */
    @Column(name = "unit_price_at_time", precision = 12, scale = 2)
    private BigDecimal unitPriceAtTime;

    @Column(name = "line_total", precision = 12, scale = 2)
    private BigDecimal lineTotal;

    /** Business-rule input to FocDeterminator: WARRANTY, INSTALLATION, CUSTOMER_DAMAGE, CUSTOMER_UPGRADE. */
    @Column(name = "classification", length = 30)
    private String classification;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", length = 20)
    @Builder.Default
    private ChargeType chargeType = ChargeType.CHARGEABLE;

    /** Required only when {@code chargeType} was flipped away from FocDeterminator's default via overrideFoc. */
    @Column(name = "override_justification", length = 500)
    private String overrideJustification;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
