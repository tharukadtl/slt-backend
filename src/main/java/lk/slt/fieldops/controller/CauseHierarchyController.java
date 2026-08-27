package lk.slt.fieldops.controller;

import lk.slt.fieldops.dto.CauseCategoryDTO;
import lk.slt.fieldops.dto.CauseOfFaultDTO;
import lk.slt.fieldops.dto.TypeOfFaultDTO;
import lk.slt.fieldops.service.CauseHierarchyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CauseHierarchyController — read-only GET access to the Cause/Material hierarchy (Stage 2,
 * QA_Compliance_Consolidated_Report.md causeId resolution). Same read gating as
 * CircuitController/ExchangeController's own cascading-picker endpoints (H1c precedent): the
 * Admin/Team-Lead post-hoc classification tab on FaultsPage.js is the only real caller.
 */
@RestController
public class CauseHierarchyController {

    private final CauseHierarchyService causeHierarchyService;

    public CauseHierarchyController(CauseHierarchyService causeHierarchyService) {
        this.causeHierarchyService = causeHierarchyService;
    }

    @GetMapping("/api/type-of-faults")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<TypeOfFaultDTO>> getAllTypesOfFault() {
        return ResponseEntity.ok(causeHierarchyService.getAllTypesOfFault());
    }

    @GetMapping("/api/cause-categories")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<CauseCategoryDTO>> getCauseCategories(
            @RequestParam(required = false) Long typeOfFaultId) {
        return ResponseEntity.ok(causeHierarchyService.getCauseCategories(typeOfFaultId));
    }

    // causeCategoryId is required, not optional like typeOfFaultId above — see
    // CauseHierarchyService.getCausesOfFault's javadoc for why an unscoped "all 869" fetch has no
    // real caller.
    @GetMapping("/api/cause-of-faults")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<CauseOfFaultDTO>> getCausesOfFault(
            @RequestParam Long causeCategoryId) {
        return ResponseEntity.ok(causeHierarchyService.getCausesOfFault(causeCategoryId));
    }
}
