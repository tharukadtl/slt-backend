package lk.slt.fieldops.kpi.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.kpi.dto.KpiScoreDTO;
import lk.slt.fieldops.kpi.dto.KpiSummaryDTO;
import lk.slt.fieldops.kpi.dto.KpiTargetRequest;
import lk.slt.fieldops.kpi.entity.KpiTarget;
import lk.slt.fieldops.kpi.service.KpiService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * KpiController — KPI dashboard endpoints.
 *
 * GET   /api/kpi/leaderboard                      Today's technician rankings
 * GET   /api/kpi/my                               My own KPI scores (Technician)
 * GET   /api/kpi/technician/{id}                  Admin: specific technician
 * GET   /api/kpi/technician/{id}/summary          Aggregated summary with period
 * GET   /api/kpi/branch/{id}                      Branch performance on a date
 * POST  /api/kpi/calculate                        Manually trigger nightly calc
 * POST  /api/kpi/targets                          Admin: set targets
 */
@RestController
@RequestMapping("/api/kpi")
public class KpiController {

    private final KpiService kpiService;

    public KpiController(KpiService kpiService) {
        this.kpiService = kpiService;
    }

    // ── Leaderboard ───────────────────────────────────────────────────────────
    @GetMapping("/leaderboard")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<KpiScoreDTO>> getLeaderboard(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(kpiService.getLeaderboard(date));
    }

    // ── Technician: my own scores ─────────────────────────────────────────────
    @GetMapping("/my")
    @PreAuthorize("hasRole('TECHNICIAN')")
    public ResponseEntity<List<KpiScoreDTO>> getMy(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(kpiService.getScoresForTechnician(userId));
    }

    // ── Admin: specific technician scores ─────────────────────────────────────
    @GetMapping("/technician/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<KpiScoreDTO>> getTechnicianScores(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (from != null && to != null) {
            return ResponseEntity.ok(kpiService.getScoresInRange(id, from, to));
        }
        return ResponseEntity.ok(kpiService.getScoresForTechnician(id));
    }

    // ── Aggregated summary for a technician ───────────────────────────────────
    @GetMapping("/technician/{id}/summary")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<KpiSummaryDTO> getTechnicianSummary(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate t = to   != null ? to   : LocalDate.now();
        return ResponseEntity.ok(kpiService.getSummary(id, f, t));
    }

    // ── Branch performance ────────────────────────────────────────────────────
    @GetMapping("/branch/{branchId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<KpiScoreDTO>> getBranchScores(
            @PathVariable Long branchId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now().minusDays(1);
        return ResponseEntity.ok(kpiService.getBranchScores(branchId, target));
    }

    // ── Manually trigger nightly calculation ──────────────────────────────────
    @PostMapping("/calculate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> triggerCalculation() {
        kpiService.calculateDailyKpis();
        return ResponseEntity.ok(Map.of(
            "message", "KPI calculation triggered for yesterday.",
            "date",    LocalDate.now().minusDays(1).toString()
        ));
    }

    // ── Set KPI targets (Admin) ───────────────────────────────────────────────
    @PostMapping("/targets")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<KpiTarget> setTarget(
            @Valid @RequestBody KpiTargetRequest request) {
        return ResponseEntity.ok(kpiService.setTarget(request));
    }
}
