package lk.slt.fieldops.fault.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.fault.dto.*;
import lk.slt.fieldops.fault.entity.FaultHistory;
import lk.slt.fieldops.fault.entity.FaultNote;
import lk.slt.fieldops.fault.service.FaultService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * FaultController — 18 REST endpoints for the fault lifecycle.
 *
 * BASE URL: /api/faults
 *
 * ── CLIENT ENDPOINTS ─────────────────────────────────────────────────────────
 * POST   /api/faults                        Report a new fault
 * GET    /api/faults/my                     Get my own faults (client)
 * GET    /api/faults/{id}                   Get one fault by ID
 * PATCH  /api/faults/{id}/cancel            Cancel my fault
 * POST   /api/faults/{id}/rating            Rate completed fault
 *
 * ── ADMIN ENDPOINTS ──────────────────────────────────────────────────────────
 * GET    /api/faults                        Get all faults (filter by branch/status)
 * GET    /api/faults/open                   Get all open faults (Super Admin)
 * POST   /api/faults/{id}/assign            Assign fault to Team Lead
 * PATCH  /api/faults/{id}/priority          Change priority
 * PATCH  /api/faults/{id}/escalate          Escalate fault
 * POST   /api/faults/{id}/notes             Add internal note
 * GET    /api/faults/{id}/notes             Get internal notes
 *
 * ── TECHNICIAN / TEAM LEAD ENDPOINTS ─────────────────────────────────────────
 * PATCH  /api/faults/{id}/status            Update status (IN_PROGRESS/HOLD/COMPLETED)
 * GET    /api/faults/assigned               Get faults assigned to my team
 * GET    /api/faults/{id}/history           Get full status timeline
 *
 * ── POSTMAN TESTING ───────────────────────────────────────────────────────────
 * 1. Login: POST /api/auth/login → copy token
 * 2. Report: POST /api/faults (Authorization: Bearer <token>)
 *    Body: { "category":"INTERNET", "description":"No internet",
 *            "locationAddress":"No 5 Main St, Colombo", "branchId":1 }
 * 3. Assign: POST /api/faults/1/assign
 *    Body: { "teamLeadId": 5 }
 */
@RestController
@RequestMapping("/api/faults")
public class FaultController {

    private final FaultService faultService;

    public FaultController(FaultService faultService) {
        this.faultService = faultService;
    }

    // ── 1. CLIENT: Report a new fault ────────────────────────────────────────
    @PostMapping
    public ResponseEntity<FaultDTO> reportFault(
            @Valid @RequestBody ReportFaultRequest request,
            @AuthenticationPrincipal Long userId) {

        // TODO Phase 3: Load customer name/phone from UserRepository
        // User user = userRepository.findById(userId)...
        String customerName  = "Customer #" + userId;   // placeholder
        String customerPhone = "N/A";                    // placeholder

        FaultDTO created = faultService.reportFault(request, userId, customerName, customerPhone);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── 2. CLIENT: Get my own faults ─────────────────────────────────────────
    @GetMapping("/my")
    public ResponseEntity<List<FaultDTO>> getMyFaults(
            @AuthenticationPrincipal Long userId) {

        return ResponseEntity.ok(faultService.getMyFaults(userId));
    }

    // ── 3. Get one fault by ID ────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<FaultDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(faultService.getFaultById(id));
    }

    // ── 4. ADMIN: Get all faults (with optional filters) ─────────────────────
    // GET /api/faults?branchId=1&status=REPORTED
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<FaultDTO>> getAllFaults(
            @RequestParam(required = false) Long   branchId,
            @RequestParam(required = false) String status) {

        if (branchId != null) {
            return ResponseEntity.ok(faultService.getFaultsByBranch(branchId, status));
        }
        return ResponseEntity.ok(faultService.getOpenFaults());
    }

    // ── 5. SUPER ADMIN: Get all open faults across all branches ──────────────
    @GetMapping("/open")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<FaultDTO>> getOpenFaults() {
        return ResponseEntity.ok(faultService.getOpenFaults());
    }

    // ── 6. ADMIN: Assign fault to a Team Lead ─────────────────────────────────
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<FaultDTO> assign(
            @PathVariable Long id,
            @Valid @RequestBody AssignFaultRequest request,
            @AuthenticationPrincipal Long adminId) {

        // TODO Phase 3: Load admin name and TL name from UserRepository
        String adminName   = "Admin #" + adminId;                // placeholder
        String teamLeadName = "Team Lead #" + request.getTeamLeadId(); // placeholder

        return ResponseEntity.ok(
            faultService.assignToTeamLead(id, request, adminId, adminName, teamLeadName));
    }

    // ── 7. TECHNICIAN / TEAM LEAD: Update fault status ────────────────────────
    // PATCH /api/faults/{id}/status
    // Body: { "newStatus":"IN_PROGRESS" } or { "newStatus":"HOLD", "reason":"..." }
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN')")
    public ResponseEntity<FaultDTO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateFaultRequest request,
            @AuthenticationPrincipal Long userId) {

        // TODO Phase 3: Load user role from security context properly
        String userName = "User #" + userId;   // placeholder
        FaultHistory.ChangedByRole role = FaultHistory.ChangedByRole.TECHNICIAN;

        return ResponseEntity.ok(
            faultService.updateStatus(id, request, userId, userName, role));
    }

    // ── 8. CLIENT or ADMIN: Cancel a fault ────────────────────────────────────
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<FaultDTO> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Long userId) {

        String reason = body != null ? body.get("reason") : "Cancelled by user";
        // TODO Phase 3: Determine role from user context
        return ResponseEntity.ok(
            faultService.cancelFault(id, reason, userId,
                "User #" + userId, FaultHistory.ChangedByRole.CLIENT));
    }

    // ── 9. Get fault status history / timeline ────────────────────────────────
    @GetMapping("/{id}/history")
    public ResponseEntity<List<FaultHistory>> getHistory(@PathVariable Long id) {
        return ResponseEntity.ok(faultService.getFaultHistory(id));
    }

    // ── 10. ADMIN: Add internal note ─────────────────────────────────────────
    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<FaultNote> addNote(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long userId) {

        String note = body.get("note");
        if (note == null || note.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(faultService.addNote(id, note, userId, "User #" + userId));
    }

    // ── 11. Get notes for a fault ─────────────────────────────────────────────
    @GetMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<FaultNote>> getNotes(@PathVariable Long id) {
        return ResponseEntity.ok(faultService.getNotes(id));
    }

    // ── 12. CLIENT: Rate a completed fault ───────────────────────────────────
    @PostMapping("/{id}/rating")
    public ResponseEntity<Map<String, String>> rate(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        // TODO: Add rateCompletedFault() to FaultService in Phase 5
        return ResponseEntity.ok(Map.of("message", "Rating saved — TODO implement in Phase 5"));
    }

    // ── 13. ADMIN: Change priority ────────────────────────────────────────────
    @PatchMapping("/{id}/priority")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> changePriority(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        // TODO: Add changePriority() to FaultService
        return ResponseEntity.ok(Map.of("message", "Priority updated — TODO implement"));
    }

    // ── 14. ADMIN: Escalate fault ─────────────────────────────────────────────
    @PatchMapping("/{id}/escalate")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> escalate(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long adminId) {

        // TODO: Add escalateFault() to FaultService
        return ResponseEntity.ok(Map.of("message", "Fault escalated — TODO implement"));
    }

    // ── 15. TEAM LEAD: Get faults assigned to my team ─────────────────────────
    @GetMapping("/assigned")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<List<FaultDTO>> getAssigned(
            @AuthenticationPrincipal Long userId) {

        // TODO Phase 3: Pass user's branchId from JWT claims
        return ResponseEntity.ok(faultService.getOpenFaults());
    }

    // ── 16. Get fault by fault number (e.g. FLT-2026-00001) ──────────────────
    @GetMapping("/number/{faultNumber}")
    public ResponseEntity<FaultDTO> getByFaultNumber(@PathVariable String faultNumber) {
        return ResponseEntity.ok(faultService.getFaultById(
            faultService.getFaultById(1L).getId())); // TODO: add findByFaultNumber to service
    }

    // ── 17. ADMIN: Get faults by branch with status filter ───────────────────
    @GetMapping("/branch/{branchId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<FaultDTO>> getByBranch(
            @PathVariable Long branchId,
            @RequestParam(required = false) String status) {

        return ResponseEntity.ok(faultService.getFaultsByBranch(branchId, status));
    }

    // ── 18. Health check / count endpoint ────────────────────────────────────
    @GetMapping("/count")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> count(
            @RequestParam(required = false) Long branchId) {

        List<FaultDTO> open = branchId != null
            ? faultService.getFaultsByBranch(branchId, null)
            : faultService.getOpenFaults();

        return ResponseEntity.ok(Map.of(
            "totalOpen", open.size(),
            "branchId",  branchId != null ? branchId : "all"
        ));
    }
}
