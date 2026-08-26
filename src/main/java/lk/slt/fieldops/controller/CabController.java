package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.CabDTO;
import lk.slt.fieldops.dto.CreateCabRequest;
import lk.slt.fieldops.service.CabService;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** CabController — basic CRUD, /api/cabs (Stage C scaffolding). */
@RestController
@RequestMapping("/api/cabs")
public class CabController {

    private final CabService cabService;
    private final OpmcAccessGuard opmcGuard;

    public CabController(CabService cabService, OpmcAccessGuard opmcGuard) {
        this.cabService = cabService;
        this.opmcGuard  = opmcGuard;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<CabDTO> create(@Valid @RequestBody CreateCabRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cabService.create(request));
    }

    // H1c: TEAM_LEAD read access, same reason as ExchangeController -- see its comment.
    // activeOnly: Exchange/Cab/Dp/Circuit + WorkGroup Minor (QA_Compliance_Consolidated_Report.md),
    // same convention as GET /api/users; defaults to false (unchanged behavior).
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<CabDTO>> getAll(@RequestParam(required = false) Long exchangeId,
                                                 @RequestParam(required = false, defaultValue = "false") boolean activeOnly,
                                                 @AuthenticationPrincipal Long callerId) {
        Long opmcFilter = opmcGuard.resolveOpmcFilter(callerId);
        return ResponseEntity.ok(exchangeId != null
            ? cabService.getByExchange(exchangeId, opmcFilter, activeOnly)
            : cabService.getAll(opmcFilter, activeOnly));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<CabDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(cabService.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<CabDTO> update(@PathVariable Long id, @Valid @RequestBody CreateCabRequest request) {
        return ResponseEntity.ok(cabService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<CabDTO> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(cabService.deactivate(id));
    }

    // Exchange/CAB/DP/Circuit hierarchy gap #1 (QA_Compliance_Consolidated_Report.md) — same
    // route shape as WorkGroupController/OpmcController's own PATCH /{id}/activate.
    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<CabDTO> activate(@PathVariable Long id) {
        return ResponseEntity.ok(cabService.activate(id));
    }
}
