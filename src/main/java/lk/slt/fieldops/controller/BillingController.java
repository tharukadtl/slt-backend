package lk.slt.fieldops.controller;

import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Client-facing billing endpoints.
 * GET /api/billing      → authenticated client's own bills
 * GET /api/billing/{id} → single bill
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
    public ResponseEntity<Payment> getBillById(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getById(id));
    }
}
