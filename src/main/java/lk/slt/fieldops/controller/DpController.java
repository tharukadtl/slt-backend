package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.CreateDpRequest;
import lk.slt.fieldops.dto.DpDTO;
import lk.slt.fieldops.service.DpService;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** DpController — basic CRUD, /api/dps (Stage C scaffolding). */
@RestController
@RequestMapping("/api/dps")
public class DpController {

    private final DpService dpService;
    private final OpmcAccessGuard opmcGuard;

    public DpController(DpService dpService, OpmcAccessGuard opmcGuard) {
        this.dpService = dpService;
        this.opmcGuard = opmcGuard;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<DpDTO> create(@Valid @RequestBody CreateDpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dpService.create(request));
    }

    // H1c: TEAM_LEAD read access, same reason as ExchangeController -- see its comment.
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<DpDTO>> getAll(@RequestParam(required = false) Long cabId,
                                                @AuthenticationPrincipal Long callerId) {
        Long opmcFilter = opmcGuard.resolveOpmcFilter(callerId);
        return ResponseEntity.ok(cabId != null
            ? dpService.getByCab(cabId, opmcFilter)
            : dpService.getAll(opmcFilter));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<DpDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(dpService.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<DpDTO> update(@PathVariable Long id, @Valid @RequestBody CreateDpRequest request) {
        return ResponseEntity.ok(dpService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<DpDTO> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(dpService.deactivate(id));
    }

    // Exchange/CAB/DP/Circuit hierarchy gap #1 (QA_Compliance_Consolidated_Report.md) — same
    // route shape as WorkGroupController/OpmcController's own PATCH /{id}/activate.
    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<DpDTO> activate(@PathVariable Long id) {
        return ResponseEntity.ok(dpService.activate(id));
    }
}
