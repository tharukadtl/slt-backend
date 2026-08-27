package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.dto.FaultDTO;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.dto.LocationResponseDTO;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.FaultAssignmentService;
import lk.slt.fieldops.service.FaultService;
import lk.slt.fieldops.service.LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/faults")
@RequiredArgsConstructor
public class FaultController {

    private final FaultAssignmentService faultAssignmentService;
    private final FaultService           faultService;
    private final UserRepository         userRepo;
    private final LocationService        locationService;

    // ─── GET /api/faults — Admin/SuperAdmin: all faults, optionally filtered ──
    // status and category are both optional. Absent → no narrowing on that field,
    // one alone → narrows on that one, both → AND-ed. Filtering is done by the
    // database query (FaultRepository.findByOptionalStatusAndCategory).
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<FaultDTO>> getAll(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/faults status={} category={}", status, category);
        boolean isSuperAdmin = SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        if (isSuperAdmin) {
            return ResponseEntity.ok(faultService.getAllFaults(status, category));
        }
        // RES-023 — OPMC Admin sees only their own OPMC's faults. opmcId is
        // derived from the caller's own user record, never a client-supplied
        // parameter, so it can't be overridden the way a bare query param could.
        Long opmcId = userRepo.findById(userId).map(User::getOpmcId).orElse(null);
        if (opmcId == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(faultService.getAllFaultsForOpmc(opmcId, status, category));
    }

    // ─── GET /api/faults/my — Team Lead: faults assigned to me ───────────────
    @GetMapping("/my")
    @PreAuthorize("hasRole('TEAM_LEAD')")
    public ResponseEntity<List<FaultDTO>> getMyAssignedFaults(
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/faults/my teamLeadId={}", userId);
        return ResponseEntity.ok(faultService.getFaultsByTeamLead(userId));
    }

    // ─── GET /api/faults/my-workgroup — Team Lead: my Work Group's incoming queue (SRS 5.5.1) ──
    @GetMapping("/my-workgroup")
    @PreAuthorize("hasRole('TEAM_LEAD')")
    public ResponseEntity<List<FaultDTO>> getMyWorkGroupQueue(
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/faults/my-workgroup teamLeadId={}", userId);
        return ResponseEntity.ok(faultService.getFaultsForTeamLeadWorkGroup(userId));
    }

    // ─── GET /api/faults/my-reports — CLIENT: faults I reported ─────────────
    @GetMapping("/my-reports")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<List<FaultDTO>> getMyReportedFaults(
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/faults/my-reports customerId={}", userId);
        return ResponseEntity.ok(faultService.getMyFaults(userId));
    }

    // ─── POST /api/faults — CLIENT: report a new fault ───────────────────────
    @PostMapping
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<FaultDTO> reportFault(
            @Valid @RequestBody ReportFaultRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults customerId={}", userId);
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        req.setCategory(normalizeMobileCategory(req.getCategory()));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(faultService.reportFault(req, userId,
                user.getFullName(),
                user.getPhone() != null ? user.getPhone() : user.getPhoneNumber()));
    }

    // ─── PATCH /api/faults/{id}/cancel — CLIENT: cancel their fault ──────────
    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('CLIENT','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<FaultDTO> cancelFault(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Long userId) {
        log.info("PATCH /api/faults/{}/cancel userId={}", id, userId);
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        String reason = (body != null ? body.getOrDefault("reason", "") : "");
        if (reason.isBlank()) reason = "Cancelled by " + user.getFullName();
        boolean isClient = SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_CLIENT"));
        FaultHistory.ChangedByRole role = isClient
            ? FaultHistory.ChangedByRole.CLIENT
            : FaultHistory.ChangedByRole.ADMIN;
        return ResponseEntity.ok(faultService.cancelFault(id, reason, userId, user.getFullName(), role));
    }

    // ─── GET /api/faults/{id} ─────────────────────────────────────────────────
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENT','ADMIN','SUPER_ADMIN','TEAM_LEAD','TECHNICIAN')")
    public ResponseEntity<FaultDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(faultService.getFaultById(id));
    }

    // ─── PATCH /api/faults/{id}/status ───────────────────────────────────────
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD','TECHNICIAN')")
    public ResponseEntity<FaultDTO> updateStatus(
            @PathVariable Long id,
            @RequestBody lk.slt.fieldops.dto.UpdateFaultRequest req,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(faultService.updateStatus(
            id, req, userId, "Staff #" + userId,
            lk.slt.fieldops.entity.FaultHistory.ChangedByRole.ADMIN));
    }

    // ─── PATCH /api/faults/{id}/circuit — manually attach a Circuit (H1c, Admin/Team Lead) ──
    @PatchMapping("/{id}/circuit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<FaultDTO> attachCircuit(
            @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody lk.slt.fieldops.dto.AttachCircuitRequest req,
            @AuthenticationPrincipal Long userId) {
        User user = userRepo.findById(userId).orElse(null);
        boolean isTeamLead = SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_TEAM_LEAD"));
        lk.slt.fieldops.entity.FaultHistory.ChangedByRole role = isTeamLead
            ? lk.slt.fieldops.entity.FaultHistory.ChangedByRole.TEAM_LEAD
            : lk.slt.fieldops.entity.FaultHistory.ChangedByRole.ADMIN;
        String actorName = user != null ? user.getFullName() : ("Staff #" + userId);
        return ResponseEntity.ok(faultService.attachCircuit(id, req.getCircuitId(), userId, actorName, role));
    }

    // ─── PATCH /api/faults/{id}/cause — Admin/Team-Lead post-hoc structured cause classification
    //     (Stage 2, QA_Compliance_Consolidated_Report.md) — mirrors attachCircuit exactly ──
    @PatchMapping("/{id}/cause")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<FaultDTO> attachCause(
            @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody lk.slt.fieldops.dto.AttachCauseRequest req,
            @AuthenticationPrincipal Long userId) {
        User user = userRepo.findById(userId).orElse(null);
        boolean isTeamLead = SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_TEAM_LEAD"));
        lk.slt.fieldops.entity.FaultHistory.ChangedByRole role = isTeamLead
            ? lk.slt.fieldops.entity.FaultHistory.ChangedByRole.TEAM_LEAD
            : lk.slt.fieldops.entity.FaultHistory.ChangedByRole.ADMIN;
        String actorName = user != null ? user.getFullName() : ("Staff #" + userId);
        return ResponseEntity.ok(faultService.attachCause(id, req.getCauseId(), userId, actorName, role));
    }

    // ─── GET /api/faults/{id}/available-technicians — proximity + availability for the Technician Selector ──
    @GetMapping("/{id}/available-technicians")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<LocationResponseDTO.NearbyTechnicianDTO>> getAvailableTechnicians(
            @PathVariable Long id,
            @RequestParam(defaultValue = "15.0") double radiusKm) {
        FaultDTO fault = faultService.getFaultById(id);
        if (fault.getLatitude() == null || fault.getLongitude() == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(
            locationService.getNearbyTechnicians(fault.getLatitude(), fault.getLongitude(), radiusKm));
    }

    // ─── POST /api/faults/{id}/assign — Admin assigns fault to a Work Group ───
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<FaultAssignmentDTO.AssignmentResponse> assignFault(
            @PathVariable Long id,
            @Valid @RequestBody FaultAssignmentDTO.AssignRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults/{}/assign workGroupId={}", id, req.getWorkGroupId());
        return ResponseEntity.ok(faultAssignmentService.assignFault(id, req, userId));
    }

    // ─── POST /api/faults/{id}/reassign ──────────────────────────────────────
    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<FaultAssignmentDTO.AssignmentResponse> reassignFault(
            @PathVariable Long id,
            @Valid @RequestBody FaultAssignmentDTO.ReassignRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults/{}/reassign newWorkGroupId={}", id, req.getNewWorkGroupId());
        return ResponseEntity.ok(faultAssignmentService.reassignFault(id, req, userId));
    }

    // ─── POST /api/faults/{id}/self-assign — Team Lead "Assign to Me" (SRS 5.5.1) ──
    @PostMapping("/{id}/self-assign")
    @PreAuthorize("hasRole('TEAM_LEAD')")
    public ResponseEntity<FaultAssignmentDTO.AssignmentResponse> selfAssignFault(
            @PathVariable Long id,
            @RequestBody(required = false) FaultAssignmentDTO.SelfAssignRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults/{}/self-assign teamLeadId={}", id, userId);
        return ResponseEntity.ok(faultAssignmentService.selfAssignFault(id, req, userId));
    }

    // ─── POST /api/faults/{id}/transfer-to-admin — Team Lead cannot resolve (SRS 5.5.1) ──
    @PostMapping("/{id}/transfer-to-admin")
    @PreAuthorize("hasRole('TEAM_LEAD')")
    public ResponseEntity<FaultAssignmentDTO.AssignmentResponse> transferToAdmin(
            @PathVariable Long id,
            @Valid @RequestBody FaultAssignmentDTO.TransferToAdminRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults/{}/transfer-to-admin teamLeadId={}", id, userId);
        return ResponseEntity.ok(faultAssignmentService.transferToAdmin(id, req, userId));
    }

    // ─── POST /api/faults/{id}/escalate ──────────────────────────────────────
    @PostMapping("/{id}/escalate")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<FaultAssignmentDTO.AssignmentResponse> escalateFault(
            @PathVariable Long id,
            @Valid @RequestBody FaultAssignmentDTO.EscalateRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults/{}/escalate", id);
        return ResponseEntity.ok(faultAssignmentService.escalateFault(id, req, userId));
    }

    // ─── POST /api/faults/bulk-assign ────────────────────────────────────────
    @PostMapping("/bulk-assign")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<FaultAssignmentDTO.BulkAssignResponse> bulkAssign(
            @Valid @RequestBody FaultAssignmentDTO.BulkAssignRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults/bulk-assign faultCount={}, workGroupId={}",
                req.getFaultIds().size(), req.getWorkGroupId());
        return ResponseEntity.ok(faultAssignmentService.bulkAssign(req, userId));
    }

    // ─── GET /api/faults/{id}/timeline ───────────────────────────────────────
    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAnyRole('CLIENT','TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<FaultAssignmentDTO.TimelineEventDTO>> getFaultTimeline(
            @PathVariable Long id) {
        log.info("GET /api/faults/{}/timeline", id);
        return ResponseEntity.ok(faultAssignmentService.getFaultTimeline(id));
    }

    // ─── POST /api/faults/{id}/notes ─────────────────────────────────────────
    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<FaultAssignmentDTO.FaultNoteResponse> addNote(
            @PathVariable Long id,
            @Valid @RequestBody FaultAssignmentDTO.AddNoteRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults/{}/notes", id);
        return ResponseEntity.ok(faultAssignmentService.addFaultNote(id, req, userId));
    }

    // ─── GET /api/faults/{id}/notes ──────────────────────────────────────────
    @GetMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('CLIENT','TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<FaultAssignmentDTO.FaultNoteResponse>> getNotes(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean internal) {
        log.info("GET /api/faults/{}/notes internal={}", id, internal);
        boolean isStaff = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().contains("ADMIN")
                        || a.getAuthority().contains("TECHNICIAN")
                        || a.getAuthority().contains("TEAM_LEAD"));
        return ResponseEntity.ok(faultAssignmentService.getFaultNotes(id, internal && isStaff));
    }

    // Maps mobile app category names to backend FaultCategory enum values
    private String normalizeMobileCategory(String category) {
        if (category == null) return "OTHER";
        return switch (category.toLowerCase()) {
            case "broadband", "internet" -> "INTERNET";
            case "telephone", "phone"   -> "PHONE";
            case "fiber"                -> "FIBER";
            case "television", "tv"     -> "TV";
            default                     -> "OTHER";
        };
    }
}
