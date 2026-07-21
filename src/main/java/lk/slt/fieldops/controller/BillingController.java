package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.ReportDisputeRequest;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Client-facing billing endpoints.
 * GET  /api/billing            → authenticated client's own bills
 * GET  /api/billing/{id}       → single bill
 * POST /api/billing/{id}/dispute → client reports an issue on a bill (FR-31, SRS 5.2.3)
 * POST /api/billing/{id}/accept  → client accepts a bill, closing the cycle (FR-31/FR-32)
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final PaymentService paymentService;

    public BillingController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public ResponseEntity<List<Payment>> getMyBills(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(paymentService.getForCustomer(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Payment> getBillById(
            @PathVariable Long id, @AuthenticationPrincipal Long userId) {
        Payment payment = paymentService.getById(id);
        if (payment.getCustomerId() == null || !payment.getCustomerId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                "You do not have access to this bill.");
        }
        return ResponseEntity.ok(payment);
    }

    /**
     * Client reports an issue on their bill (FR-31, SRS 5.2.3). Ownership is verified
     * the same way as {@link #getBillById}; the bill moves to DISPUTED and stays in the
     * client's active issues (out of Service History) until the dispute is resolved.
     */
    @PostMapping("/{id}/dispute")
    public ResponseEntity<Payment> reportDispute(
            @PathVariable Long id,
            @Valid @RequestBody ReportDisputeRequest request,
            @AuthenticationPrincipal Long userId) {
        Payment payment = paymentService.getById(id);
        if (payment.getCustomerId() == null || !payment.getCustomerId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                "You do not have access to this bill.");
        }
        return ResponseEntity.ok(paymentService.reportDispute(id, request, userId));
    }

    /**
     * Client accepts their bill, closing the dispute/amendment cycle (FR-31 / FR-32). Ownership is
     * verified the same way as {@link #reportDispute}; the bill moves to the terminal
     * CLIENT_ACCEPTED status and leaves the client's active issues for Service History. No request
     * body is needed — acceptance carries no client-supplied fields (unlike dispute/amend).
     */
    @PostMapping("/{id}/accept")
    public ResponseEntity<Payment> acceptBill(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId) {
        Payment payment = paymentService.getById(id);
        if (payment.getCustomerId() == null || !payment.getCustomerId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                "You do not have access to this bill.");
        }
        return ResponseEntity.ok(paymentService.acceptBill(id, userId));
    }
}
