package lk.slt.fieldops.payment.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PaymentApproval.java — maps to `payment_approvals` table.
 * Audit trail for every admin decision on a payment.
 */
@Entity
@Table(name = "payment_approvals")
public class PaymentApproval {

    public enum Action { APPROVED, REJECTED, ADJUSTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Action action;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "admin_name", length = 150)
    private String adminName;

    @Column(name = "original_amount", precision = 12, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "adjusted_amount", precision = 12, scale = 2)
    private BigDecimal adjustedAmount;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public PaymentApproval() {}

    public Long          getId()              { return id; }
    public Long          getPaymentId()       { return paymentId; }
    public Action        getAction()          { return action; }
    public Long          getAdminId()         { return adminId; }
    public String        getAdminName()       { return adminName; }
    public BigDecimal    getOriginalAmount()  { return originalAmount; }
    public BigDecimal    getAdjustedAmount()  { return adjustedAmount; }
    public String        getReason()          { return reason; }
    public LocalDateTime getCreatedAt()       { return createdAt; }

    public void setId(Long v)                   { this.id             = v; }
    public void setPaymentId(Long v)            { this.paymentId      = v; }
    public void setAction(Action v)             { this.action         = v; }
    public void setAdminId(Long v)              { this.adminId        = v; }
    public void setAdminName(String v)          { this.adminName      = v; }
    public void setOriginalAmount(BigDecimal v) { this.originalAmount = v; }
    public void setAdjustedAmount(BigDecimal v) { this.adjustedAmount = v; }
    public void setReason(String v)             { this.reason         = v; }
}
