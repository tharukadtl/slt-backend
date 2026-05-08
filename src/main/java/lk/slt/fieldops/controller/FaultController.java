package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.dto.FaultDTO;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.FaultAssignmentService;
import lk.slt.fieldops.service.FaultService;
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

    // ─── GET /api/faults — Admin/SuperAdmin: all faults ──────────────────────
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<FaultDTO>> getAll() {
        return ResponseEntity.ok(faultService.getAllFaults());
    }

    // ─── GET /api/faults/my — Team Lead: faults assigned to me ───────────────
    @GetMapping("/my")
    @PreAuthorize("hasRole('TEAM_LEAD')")
    public ResponseEntity<List<FaultDTO>> getMyAssignedFaults(
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/faults/my teamLeadId={}", userId);
        return ResponseEntity.ok(faultService.getFaultsByTeamLead(userId));
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

    // ─── POST /api/faults/{id}/assign — Admin assigns fault to Team Lead ──────
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<FaultAssignmentDTO.AssignmentResponse> assignFault(
            @PathVariable Long id,
            @Valid @RequestBody FaultAssignmentDTO.AssignRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults/{}/assign teamLeadId={}", id, req.getTechnicianId());
        return ResponseEntity.ok(faultAssignmentService.assignFault(id, req, userId));
    }

    // ─── POST /api/faults/{id}/reassign ──────────────────────────────────────
    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<FaultAssignmentDTO.AssignmentResponse> reassignFault(
            @PathVariable Long id,
            @Valid @RequestBody FaultAssignmentDTO.ReassignRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/faults/{}/reassign newTeamLeadId={}", id, req.getNewTechnicianId());
        return ResponseEntity.ok(faultAssignmentService.reassignFault(id, req, userId));
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
        log.info("POST /api/faults/bulk-assign faultCount={}, teamLeadId={}",
                req.getFaultIds().size(), req.getTechnicianId());
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
            case "fiber"                -> "INTERNET";
            case "television", "tv"     -> "TV";
            default                     -> "OTHER";
        };
    }
}
