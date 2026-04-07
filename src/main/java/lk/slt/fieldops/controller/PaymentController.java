package lk.slt.fieldops.payment.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.payment.dto.ReviewPaymentRequest;
import lk.slt.fieldops.payment.dto.SubmitPaymentRequest;
import lk.slt.fieldops.payment.entity.Payment;
import lk.slt.fieldops.payment.entity.PaymentApproval;
import lk.slt.fieldops.payment.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PaymentController — billing workflow endpoints.
 *
 * POST   /api/payments                  TL submits payment
 * GET    /api/payments/{id}             Get one payment
 * GET    /api/payments/pending          Admin: queue to review
 * GET    /api/payments/branch/{id}      Branch payment history
 * GET    /api/payments/my               TL: my submitted payments
 * GET    /api/payments/customer/{id}    Customer billing history
 * PATCH  /api/payments/{id}/review      Admin: approve or reject
 * GET    /api/payments/{id}/approvals   Audit trail
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<Payment> submit(
            @Valid @RequestBody SubmitPaymentRequest request,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(paymentService.submitPayment(request, userId, "Team Lead #" + userId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<Payment> getById(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getById(id));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<Payment>> getPending() {
        return ResponseEntity.ok(paymentService.getPendingPayments());
    }

    @GetMapping("/branch/{branchId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<Payment>> getByBranch(@PathVariable Long branchId) {
        return ResponseEntity.ok(paymentService.getByBranch(branchId));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<List<Payment>> getMy(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(paymentService.getByTeamLead(userId));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<Payment>> getForCustomer(@PathVariable Long customerId) {
        return ResponseEntity.ok(paymentService.getForCustomer(customerId));
    }

    @PatchMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Payment> review(
            @PathVariable Long id,
            @Valid @RequestBody ReviewPaymentRequest request,
            @AuthenticationPrincipal Long adminId) {
        return ResponseEntity.ok(
            paymentService.reviewPayment(id, request, adminId, "Admin #" + adminId));
    }

    @GetMapping("/{id}/approvals")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<PaymentApproval>> getApprovals(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getApprovalHistory(id));
    }
}
