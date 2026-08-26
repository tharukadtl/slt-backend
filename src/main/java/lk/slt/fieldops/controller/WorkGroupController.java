package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.CreateWorkGroupRequest;
import lk.slt.fieldops.dto.WorkGroupDTO;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.WorkGroupService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * WorkGroupController — CRUD for WorkGroup (Stage C scaffolding).
 * Base URL: /api/workgroups. Admin/SuperAdmin managed, per SecurityConfig.
 */
@RestController
@RequestMapping("/api/workgroups")
public class WorkGroupController {

    private final WorkGroupService workGroupService;
    private final UserRepository   userRepo;

    public WorkGroupController(WorkGroupService workGroupService, UserRepository userRepo) {
        this.workGroupService = workGroupService;
        this.userRepo         = userRepo;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<WorkGroupDTO> create(@Valid @RequestBody CreateWorkGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workGroupService.create(request));
    }

    // activeOnly: Exchange/Cab/Dp/Circuit + WorkGroup Minor (QA_Compliance_Consolidated_Report.md),
    // same convention as GET /api/users; defaults to false (unchanged behavior). Only applies to the
    // unscoped branch — the opmcId branch already always returns active-only Work Groups (a
    // pre-existing behavior, deliberately left as-is; see WorkGroupService.getByOpmc's javadoc).
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<WorkGroupDTO>> getAll(@RequestParam(required = false) Long opmcId,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(opmcId != null
            ? workGroupService.getByOpmc(opmcId)
            : workGroupService.getAll(activeOnly));
    }

    // RES-023 — an OPMC Admin may not read another OPMC's Work Group by ID.
    // Super Admin is unscoped (SRS 5.5.6). opmcId is derived from the caller's
    // own record, not trusted from the request.
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<WorkGroupDTO> getById(@PathVariable Long id, @AuthenticationPrincipal Long userId) {
        WorkGroupDTO dto = workGroupService.getById(id);
        assertSameOpmcUnlessSuperAdmin(dto, userId);
        return ResponseEntity.ok(dto);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<WorkGroupDTO> update(@PathVariable Long id, @Valid @RequestBody CreateWorkGroupRequest request,
                                                @AuthenticationPrincipal Long userId) {
        assertSameOpmcUnlessSuperAdmin(workGroupService.getById(id), userId);
        return ResponseEntity.ok(workGroupService.update(id, request));
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<WorkGroupDTO> activate(@PathVariable Long id, @AuthenticationPrincipal Long userId) {
        assertSameOpmcUnlessSuperAdmin(workGroupService.getById(id), userId);
        return ResponseEntity.ok(workGroupService.activate(id));
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<WorkGroupDTO> deactivate(@PathVariable Long id, @AuthenticationPrincipal Long userId) {
        assertSameOpmcUnlessSuperAdmin(workGroupService.getById(id), userId);
        return ResponseEntity.ok(workGroupService.deactivate(id));
    }

    private void assertSameOpmcUnlessSuperAdmin(WorkGroupDTO dto, Long callerId) {
        boolean isSuperAdmin = SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        if (isSuperAdmin) return;

        Long callerOpmcId = userRepo.findById(callerId).map(User::getOpmcId).orElse(null);
        if (callerOpmcId == null || dto.getOpmcId() == null || !callerOpmcId.equals(dto.getOpmcId())) {
            throw new AccessDeniedException("This Work Group does not belong to your OPMC.");
        }
    }
}
