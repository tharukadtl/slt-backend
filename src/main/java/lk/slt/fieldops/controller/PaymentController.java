package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.ClientBillDTO;
import lk.slt.fieldops.dto.ReviewPaymentRequest;
import lk.slt.fieldops.dto.SubmitPaymentRequest;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.PaymentApproval;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

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
    private final UserRepository userRepo;

    public PaymentController(PaymentService paymentService, UserRepository userRepo) {
        this.paymentService = paymentService;
        this.userRepo       = userRepo;
    }

    private String resolveFullName(Long userId) {
        return userRepo.findById(userId)
            .map(User::getFullName)
            .orElse("User #" + userId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<Payment> submit(
            @Valid @RequestBody SubmitPaymentRequest request,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(paymentService.submitPayment(request, userId, resolveFullName(userId)));
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

    @GetMapping("/all")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<Payment>> getAll() {
        return ResponseEntity.ok(paymentService.getAll());
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

    /** Client: view their own bills (no customerId needed — resolved from JWT) */
    @GetMapping("/my-bills")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<List<ClientBillDTO>> getMyBills(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(
            paymentService.getForCustomer(userId).stream()
                .map(ClientBillDTO::from)
                .collect(Collectors.toList()));
    }

    @PatchMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Payment> review(
            @PathVariable Long id,
            @Valid @RequestBody ReviewPaymentRequest request,
            @AuthenticationPrincipal Long adminId) {
        return ResponseEntity.ok(
            paymentService.reviewPayment(id, request, adminId, resolveFullName(adminId)));
    }

    @GetMapping("/{id}/approvals")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<PaymentApproval>> getApprovals(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getApprovalHistory(id));
    }
}
