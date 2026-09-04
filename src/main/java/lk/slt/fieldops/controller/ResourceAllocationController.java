package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.WorkGroupAllocationDTO;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.ResourceAllocationService;
import lk.slt.fieldops.service.WorkGroupService;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ResourceAllocationController — SRS 5.5.3 (v1.9). Base URL: /api/resource-allocations.
 * Admin allocates from the OPMC pool (Material.currentStock) to a Work Group;
 * a Work Group's own Team Lead (or any Admin/SuperAdmin) can read its balance.
 */
@RestController
@RequestMapping("/api/resource-allocations")
public class ResourceAllocationController {

    private final ResourceAllocationService allocationService;
    private final WorkGroupService          workGroupService;
    private final UserRepository            userRepo;
    private final OpmcAccessGuard           opmcGuard;

    public ResourceAllocationController(ResourceAllocationService allocationService,
                                         WorkGroupService workGroupService,
                                         UserRepository userRepo,
                                         OpmcAccessGuard opmcGuard) {
        this.allocationService = allocationService;
        this.workGroupService  = workGroupService;
        this.userRepo          = userRepo;
        this.opmcGuard         = opmcGuard;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<WorkGroupAllocationDTO.AllocationResponse> allocate(
            @Valid @RequestBody WorkGroupAllocationDTO.AllocateRequest req,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(allocationService.allocateToWorkGroup(req, userId));
    }

    @GetMapping("/work-group/{id}")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<WorkGroupAllocationDTO.AllocationResponse>> getForWorkGroup(
            @PathVariable Long id, @AuthenticationPrincipal Long userId) {

        boolean isTeamLead = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_TEAM_LEAD"));
        if (isTeamLead) {
            User caller = userRepo.findById(userId).orElse(null);
            if (caller == null || caller.getWorkgroup() == null
                    || !caller.getWorkgroup().getId().equals(id)) {
                throw new AccessDeniedException("This is not your Work Group.");
            }
        } else {
            // Stage F #2 — an Admin was previously able to read any Work Group's allocations
            // regardless of OPMC; only the TEAM_LEAD branch above was ever scoped. Super Admin
            // stays unscoped inside assertSameOpmc.
            opmcGuard.assertSameOpmc(workGroupService.getById(id).getOpmcId(), userId);
        }
        return ResponseEntity.ok(allocationService.getAllocationsForWorkGroup(id));
    }

    @GetMapping("/opmc/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<WorkGroupAllocationDTO.AllocationResponse>> getForOpmc(
            @PathVariable Long id, @AuthenticationPrincipal Long userId) {
        // Stage F #2 — previously any ADMIN could read any OPMC's allocations by ID.
        opmcGuard.assertSameOpmc(id, userId);
        return ResponseEntity.ok(allocationService.getAllocationsForOpmc(id));
    }
}
