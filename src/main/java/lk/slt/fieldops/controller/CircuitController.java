package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.CircuitDTO;
import lk.slt.fieldops.dto.CreateCircuitRequest;
import lk.slt.fieldops.service.CircuitService;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CircuitController — basic CRUD, /api/circuits (Stage C scaffolding).
 * Read is open to TEAM_LEAD too, for H1c's cascading Circuit-attachment picker
 * (Opmc -> Exchange -> Cab -> Dp -> Circuit) driven from the Team Lead mobile
 * app's AssignJobsScreen; write stays Admin-only.
 *
 * TECHNICIAN was dropped from read 2026-08-25 (Exchange/Cab/Dp/Circuit gap #3,
 * QA_Compliance_Consolidated_Report.md) — confirmed against the real caller
 * before narrowing rather than assumed: H1c's picker lives only in the Team
 * Lead screen, and its own attach endpoint (PATCH /api/faults/{id}/circuit)
 * was already Admin/Team-Lead-only from the start, so this brings read in
 * line with its sibling Exchange/Cab/Dp controllers, which never opened to
 * TECHNICIAN in the first place.
 */
@RestController
@RequestMapping("/api/circuits")
public class CircuitController {

    private final CircuitService circuitService;
    private final OpmcAccessGuard opmcGuard;

    public CircuitController(CircuitService circuitService, OpmcAccessGuard opmcGuard) {
        this.circuitService = circuitService;
        this.opmcGuard      = opmcGuard;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<CircuitDTO> create(@Valid @RequestBody CreateCircuitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(circuitService.create(request));
    }

    // activeOnly: Exchange/Cab/Dp/Circuit + WorkGroup Minor (QA_Compliance_Consolidated_Report.md),
    // same convention as GET /api/users; defaults to false (unchanged behavior).
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<CircuitDTO>> getAll(@RequestParam(required = false) Long dpId,
                                                     @RequestParam(required = false, defaultValue = "false") boolean activeOnly,
                                                     @AuthenticationPrincipal Long callerId) {
        Long opmcFilter = opmcGuard.resolveOpmcFilter(callerId);
        return ResponseEntity.ok(dpId != null
            ? circuitService.getByDp(dpId, opmcFilter, activeOnly)
            : circuitService.getAll(opmcFilter, activeOnly));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<CircuitDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(circuitService.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<CircuitDTO> update(@PathVariable Long id, @Valid @RequestBody CreateCircuitRequest request) {
        return ResponseEntity.ok(circuitService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<CircuitDTO> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(circuitService.deactivate(id));
    }

    // Exchange/CAB/DP/Circuit hierarchy gap #1 (QA_Compliance_Consolidated_Report.md) — same
    // route shape as WorkGroupController/OpmcController's own PATCH /{id}/activate.
    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<CircuitDTO> activate(@PathVariable Long id) {
        return ResponseEntity.ok(circuitService.activate(id));
    }
}
