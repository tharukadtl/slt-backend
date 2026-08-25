package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.CreateExchangeRequest;
import lk.slt.fieldops.dto.ExchangeDTO;
import lk.slt.fieldops.service.ExchangeService;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** ExchangeController — basic CRUD, /api/exchanges (Stage C scaffolding). */
@RestController
@RequestMapping("/api/exchanges")
public class ExchangeController {

    private final ExchangeService exchangeService;
    private final OpmcAccessGuard opmcGuard;

    public ExchangeController(ExchangeService exchangeService, OpmcAccessGuard opmcGuard) {
        this.exchangeService = exchangeService;
        this.opmcGuard       = opmcGuard;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ExchangeDTO> create(@Valid @RequestBody CreateExchangeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exchangeService.create(request));
    }

    // H1c: TEAM_LEAD read access added 2026-08-21 so the manual Circuit-attachment cascading
    // picker (Opmc -> Exchange -> Cab -> Dp -> Circuit) can be walked from the Team Lead mobile
    // app -- mirrors CircuitController's own precedent (read opened to TEAM_LEAD/TECHNICIAN
    // ahead of this exact use case). Mutation endpoints below are untouched.
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<ExchangeDTO>> getAll(@RequestParam(required = false) Long opmcId,
                                                      @AuthenticationPrincipal Long callerId) {
        Long opmcFilter = opmcGuard.resolveOpmcFilter(callerId);
        return ResponseEntity.ok(opmcId != null
            ? exchangeService.getByOpmc(opmcId, opmcFilter)
            : exchangeService.getAll(opmcFilter));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<ExchangeDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(exchangeService.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ExchangeDTO> update(@PathVariable Long id, @Valid @RequestBody CreateExchangeRequest request) {
        return ResponseEntity.ok(exchangeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ExchangeDTO> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(exchangeService.deactivate(id));
    }

    // Exchange/CAB/DP/Circuit hierarchy gap #1 (QA_Compliance_Consolidated_Report.md) — same
    // route shape as WorkGroupController/OpmcController's own PATCH /{id}/activate.
    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ExchangeDTO> activate(@PathVariable Long id) {
        return ResponseEntity.ok(exchangeService.activate(id));
    }
}
