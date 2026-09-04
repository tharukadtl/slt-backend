package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.KpiDTO;
import lk.slt.fieldops.service.KpiCalculationService;
import lk.slt.fieldops.service.UserService;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/kpi")
@RequiredArgsConstructor
public class KpiController {

    private final KpiCalculationService kpiCalculationService;
    private final OpmcAccessGuard opmcGuard;
    private final UserService userService;

    @GetMapping("/my-score")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<KpiDTO.PersonalKpiDTO> getMyScore(
            @RequestParam(defaultValue = "MONTHLY") String period,
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/kpi/my-score userId={}, period={}", userId, period);
        return ResponseEntity.ok(kpiCalculationService.getPersonalKpi(userId, period));
    }

    @GetMapping("/score/{userId}")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<KpiDTO.PersonalKpiDTO> getTechnicianScore(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "MONTHLY") String period,
            @AuthenticationPrincipal Long callerId) {
        log.info("GET /api/kpi/score/{} period={}", userId, period);
        // QA_Compliance_Consolidated_Report.md Stage G Major — this method and getTechnicianTargets
        // below had no OpmcAccessGuard check at all, unlike their siblings getTeamKpi/getLeaderboard
        // in this same file. The target here is a userId, not an opmcId directly, so it's resolved
        // via UserService.getById first — same shape as ResourceAllocationController/PaymentController's
        // own assertSameOpmc(someService.getById(id).getOpmcId(), callerId) call sites.
        opmcGuard.assertSameOpmc(userService.getById(userId).getOpmcId(), callerId);
        return ResponseEntity.ok(kpiCalculationService.getPersonalKpi(userId, period));
    }

    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<KpiDTO.TeamKpiDTO> getTeamKpi(
            @RequestParam(defaultValue = "MONTHLY") String period,
            @RequestParam(defaultValue = "1") Long opmcId,
            @AuthenticationPrincipal Long callerId) {
        log.info("GET /api/kpi/team period={}, opmcId={}", period, opmcId);
        // Stage F #2 — opmcId was previously trusted straight from the client with no check
        // that it was the caller's own OPMC.
        opmcGuard.assertSameOpmc(opmcId, callerId);
        return ResponseEntity.ok(kpiCalculationService.getTeamKpi(opmcId, period));
    }

    @GetMapping("/targets/my-targets")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<KpiDTO.TargetResponseDTO>> getMyTargets(
            @RequestParam(required = false) String period,
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/kpi/targets/my-targets userId={}, period={}", userId, period);
        return ResponseEntity.ok(kpiCalculationService.getMyTargets(userId, period));
    }

    @PostMapping("/targets/assign")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<KpiDTO.TargetResponseDTO> assignTarget(
            @Valid @RequestBody KpiDTO.AssignTargetRequest request,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/kpi/targets/assign adminId={}, technicianId={}",
                userId, request.getTechnicianId());
        return ResponseEntity.ok(kpiCalculationService.assignTarget(userId, request));
    }

    @GetMapping("/targets/technician/{id}")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<KpiDTO.TargetResponseDTO>> getTechnicianTargets(
            @PathVariable Long id,
            @AuthenticationPrincipal Long callerId) {
        log.info("GET /api/kpi/targets/technician/{}", id);
        // Same gap and same fix shape as getTechnicianScore above.
        opmcGuard.assertSameOpmc(userService.getById(id).getOpmcId(), callerId);
        return ResponseEntity.ok(kpiCalculationService.getTechnicianTargets(id));
    }

    @GetMapping("/leaderboard")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<KpiDTO.LeaderboardEntryDTO>> getLeaderboard(
            @RequestParam(defaultValue = "MONTHLY") String period,
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/kpi/leaderboard period={}", period);
        // Stage F #2 — uniform across all four calling roles: Super Admin unscoped,
        // Admin/Team Lead/Technician each see only their own OPMC's leaderboard.
        Long opmcFilter = opmcGuard.resolveOpmcFilter(userId);
        return ResponseEntity.ok(kpiCalculationService.getLeaderboard(period, userId, opmcFilter));
    }
}
