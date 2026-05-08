package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.KpiDTO;
import lk.slt.fieldops.service.KpiCalculationService;
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
            @RequestParam(defaultValue = "MONTHLY") String period) {
        log.info("GET /api/kpi/score/{} period={}", userId, period);
        return ResponseEntity.ok(kpiCalculationService.getPersonalKpi(userId, period));
    }

    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<KpiDTO.TeamKpiDTO> getTeamKpi(
            @RequestParam(defaultValue = "MONTHLY") String period,
            @RequestParam(defaultValue = "1") Long branchId) {
        log.info("GET /api/kpi/team period={}, branchId={}", period, branchId);
        return ResponseEntity.ok(kpiCalculationService.getTeamKpi(branchId, period));
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
            @PathVariable Long id) {
        log.info("GET /api/kpi/targets/technician/{}", id);
        return ResponseEntity.ok(kpiCalculationService.getTechnicianTargets(id));
    }

    @GetMapping("/leaderboard")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<KpiDTO.LeaderboardEntryDTO>> getLeaderboard(
            @RequestParam(defaultValue = "MONTHLY") String period,
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/kpi/leaderboard period={}", period);
        return ResponseEntity.ok(kpiCalculationService.getLeaderboard(period, userId));
    }
}
