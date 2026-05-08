package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.AttendanceDTO;
import lk.slt.fieldops.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/today/{userId}")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<AttendanceDTO.TodaySummaryDTO> getTodaySummary(
            @PathVariable Long userId) {
        log.info("GET /api/attendance/today/{}", userId);
        return ResponseEntity.ok(attendanceService.getTodaySummary(userId));
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
