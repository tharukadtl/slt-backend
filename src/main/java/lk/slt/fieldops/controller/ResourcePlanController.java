package lk.slt.fieldops.controller;

import lk.slt.fieldops.dto.ConfirmResourcePlanRequest;
import lk.slt.fieldops.dto.ConfirmResourcePlanResponse;
import lk.slt.fieldops.dto.ConfirmedPlanDTO;
import lk.slt.fieldops.service.ResourcePlanConfirmationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * ResourcePlanController — FR-33 Stage 3b (SRS 5.6.8).
 *
 * Base URL: /api/resource-plans
 *
 * POST /api/resource-plans/confirm   Admin persists a reviewed/adjusted plan
 *                                     (frontend-admin Resource Planning panel)
 * GET  /api/resource-plans/lookup    Team Lead reads today's confirmed plan
 *                                     for their own OPMC (BOD screen)
 */
@RestController
@RequestMapping("/api/resource-plans")
public class ResourcePlanController {

    private final ResourcePlanConfirmationService service;

    public ResourcePlanController(ResourcePlanConfirmationService service) {
        this.service = service;
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ConfirmResourcePlanResponse> confirm(
            @RequestBody ConfirmResourcePlanRequest request,
            @AuthenticationPrincipal Long userId) {

        return ResponseEntity.ok(service.confirmPlan(request, userId));
    }

    @GetMapping("/lookup")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<ConfirmedPlanDTO>> lookup(
            @RequestParam(required = false) String date,
            @AuthenticationPrincipal Long userId) {

        LocalDate planDate = (date != null && !date.isBlank()) ? LocalDate.parse(date) : LocalDate.now();
        return ResponseEntity.ok(service.getConfirmedPlanForTeamLead(userId, planDate));
    }
}
