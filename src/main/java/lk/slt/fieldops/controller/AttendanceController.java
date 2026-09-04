package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.AttendanceDTO;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.AttendanceService;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final UserRepository    userRepository;
    private final OpmcAccessGuard   opmcGuard;

    @PostMapping("/check-in")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<AttendanceDTO.AttendanceResponse> checkIn(
            @Valid @RequestBody AttendanceDTO.CheckInRequest request,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/attendance/check-in userId={}, lat={}, lng={}",
                userId, request.getLatitude(), request.getLongitude());
        return ResponseEntity.ok(attendanceService.checkIn(userId, request));
    }

    @PostMapping("/check-out")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<AttendanceDTO.AttendanceResponse> checkOut(
            @Valid @RequestBody AttendanceDTO.CheckOutRequest request,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/attendance/check-out userId={}", userId);
        return ResponseEntity.ok(attendanceService.checkOut(userId, request));
    }

    // QA_Compliance_Consolidated_Report.md Stage G Minor — this endpoint had only a role-level
    // @PreAuthorize, so any TECHNICIAN could read any other TECHNICIAN's daily attendance summary
    // just by passing their userId — the role gate never checked whose id was being requested.
    @GetMapping("/today/{userId}")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<AttendanceDTO.TodaySummaryDTO> getTodaySummary(
            @PathVariable Long userId,
            @AuthenticationPrincipal Long callerId) {
        log.info("GET /api/attendance/today/{}", userId);
        assertCanViewAttendance(userId, callerId);
        return ResponseEntity.ok(attendanceService.getTodaySummary(userId));
    }

    /**
     * SRS 5.4.3 (FR-16) — "Team Leads can monitor all **team members'**" (Work Group, per 5.5.0's
     * hierarchy, not the whole OPMC) status; 5.5.0 — "Super Admin has unscoped visibility...
     * OPMC-level Admin accounts are scoped to their own OPMC only." Confirmed against the spec
     * text before implementing, per instruction, rather than assumed:
     * <ul>
     *   <li>Anyone may always view their own attendance.</li>
     *   <li>TECHNICIAN may view no one else's.</li>
     *   <li>TEAM_LEAD may view any Technician in their own Work Group (narrower than OPMC —
     *       "team" in 5.4.3 means Work Group, the same distinction Stage F #1 drew for OPMC
     *       CRUD vs. OPMC-wide visibility).</li>
     *   <li>ADMIN may view anyone in their own OPMC, via the same {@link OpmcAccessGuard} used
     *       everywhere else in this codebase for this exact boundary.</li>
     *   <li>SUPER_ADMIN is unscoped.</li>
     * </ul>
     */
    private void assertCanViewAttendance(Long targetUserId, Long callerId) {
        if (callerId.equals(targetUserId)) return;

        User caller = userRepository.findById(callerId)
            .orElseThrow(() -> new ResourceNotFoundException("User", callerId));
        if (caller.getRole() == User.Role.SUPER_ADMIN) return;

        if (caller.getRole() == User.Role.TECHNICIAN) {
            throw new AccessDeniedException("You may only view your own attendance.");
        }

        User target = userRepository.findById(targetUserId)
            .orElseThrow(() -> new ResourceNotFoundException("User", targetUserId));

        if (caller.getRole() == User.Role.TEAM_LEAD) {
            Long callerWorkGroupId = caller.getWorkgroup() != null ? caller.getWorkgroup().getId() : null;
            Long targetWorkGroupId = target.getWorkgroup() != null ? target.getWorkgroup().getId() : null;
            if (callerWorkGroupId == null || !callerWorkGroupId.equals(targetWorkGroupId)) {
                throw new AccessDeniedException("You may only view your own Work Group's attendance.");
            }
            return;
        }

        // ADMIN falls through to here — scoped to their own OPMC, same as everywhere else.
        opmcGuard.assertSameOpmc(target.getOpmcId(), callerId);
    }

    @GetMapping("/me/today")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<AttendanceDTO.TodaySummaryDTO> getMyTodaySummary(
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/attendance/me/today userId={}", userId);
        return ResponseEntity.ok(attendanceService.getTodaySummary(userId));
    }

    @GetMapping("/history/{userId}")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<AttendanceDTO.AttendanceHistorySummaryDTO> getHistory(
            @PathVariable Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        log.info("GET /api/attendance/history/{}", userId);
        return ResponseEntity.ok(attendanceService.getHistory(userId, startDate, endDate));
    }

    @GetMapping("/me/history")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<AttendanceDTO.AttendanceHistorySummaryDTO> getMyHistory(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        log.info("GET /api/attendance/me/history userId={}", userId);
        return ResponseEntity.ok(attendanceService.getHistory(userId, startDate, endDate));
    }

    @GetMapping("/team/{teamId}/today")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<AttendanceDTO.TeamAttendanceDTO> getTeamToday(
            @PathVariable Long teamId) {
        log.info("GET /api/attendance/team/{}/today", teamId);
        return ResponseEntity.ok(attendanceService.getTeamToday(teamId));
    }
}
