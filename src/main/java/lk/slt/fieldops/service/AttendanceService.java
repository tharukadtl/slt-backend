package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.AttendanceDTO;
import lk.slt.fieldops.entity.CheckInOut;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CheckInOutRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.websocket
        .WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation
        .Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final CheckInOutRepository checkInOutRepository;
    private final UserRepository       userRepository;
    private final JobRepository        jobRepository;
    private final WebSocketEventPublisher webSocketEventPublisher;

    private static final DateTimeFormatter
            DATE_FMT =
            DateTimeFormatter
                    .ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter
            TIME_FMT =
            DateTimeFormatter
                    .ofPattern("hh:mm a");

    // ─── BOD Check-In ─────────────────────────────────────

    @Transactional
    public AttendanceDTO.AttendanceResponse
    checkIn(
            Long userId,
            AttendanceDTO.CheckInRequest request) {

        log.info(
                "BOD Check-In for userId={}",
                userId);

        User user = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "User not found: "
                                        + userId));

        // Check if already checked in today
        LocalDateTime startOfDay =
                LocalDate.now().atStartOfDay();

        boolean alreadyCheckedIn =
                checkInOutRepository
                        .existsTodayCheckIn(
                                userId, startOfDay);

        if (alreadyCheckedIn) {
            // Return existing check-in record
            Optional<CheckInOut> existing =
                    checkInOutRepository
                            .findTodayByUserId(
                                    userId, startOfDay);
            if (existing.isPresent()) {
                log.warn(
                        "User {} already checked "
                                + "in today",
                        userId);
                return mapToResponse(existing.get());
            }
        }

        // Create new check-in record
        CheckInOut checkIn = new CheckInOut();
        checkIn.setUser(user);
        checkIn.setCheckType("ATTENDANCE");
        checkIn.setCheckInTime(LocalDateTime.now());
        checkIn.setCheckInLatitude(
                request.getLatitude());
        checkIn.setCheckInLongitude(
                request.getLongitude());
        checkIn.setCheckInAddress(
                request.getAddress());
        checkIn.setNotes(request.getNotes());
        checkIn.setStatus("CHECKED_IN");

        CheckInOut saved =
                checkInOutRepository.save(checkIn);

        // Notify admin via WebSocket
        webSocketEventPublisher.sendToRole(
                "admin",
                "Staff Checked In",
                user.getFullName()
                        + " checked in at "
                        + LocalDateTime.now()
                        .format(TIME_FMT),
                "ATTENDANCE_CHECK_IN");

        log.info(
                "BOD Check-In successful "
                        + "for userId={}",
                userId);

        return mapToResponse(saved);
    }

    // ─── EOD Check-Out ────────────────────────────────────

    @Transactional
    public AttendanceDTO.AttendanceResponse
    checkOut(
            Long userId,
            AttendanceDTO.CheckOutRequest request) {

        log.info(
                "EOD Check-Out for userId={}",
                userId);

        User user = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "User not found: "
                                        + userId));

        // Find active check-in for today
        CheckInOut checkIn =
                checkInOutRepository
                        .findActiveCheckInByUserId(
                                userId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "No active check-in "
                                                + "found for user: "
                                                + userId));

        // Check if already checked out
        if (checkIn.getCheckOutTime() != null) {
            throw new RuntimeException(
                    "User already checked out today");
        }

        // Update check-out details
        checkIn.setCheckOutTime(
                LocalDateTime.now());
        checkIn.setCheckOutLatitude(
                request.getLatitude());
        checkIn.setCheckOutLongitude(
                request.getLongitude());
        checkIn.setCheckOutAddress(
                request.getAddress());
        checkIn.setStatus("CHECKED_OUT");

        if (request.getJobsCompleted() != null) {
            checkIn.setJobsCompleted(
                    request.getJobsCompleted());
        }

        if (request.getNotes() != null) {
            String existing =
                    checkIn.getNotes() != null
                            ? checkIn.getNotes()
                            + " | " : "";
            checkIn.setNotes(
                    existing + request.getNotes());
        }

        CheckInOut saved =
                checkInOutRepository.save(checkIn);

        // Return technician's open jobs to team lead pool
        int returnedJobs = jobRepository
                .returnJobsOnTechnicianCheckout(userId, LocalDate.now());
        log.info("Checkout: returned {} open jobs to team lead for technicianId={}", returnedJobs, userId);

        // Notify admin via WebSocket
        long workMinutes =
                ChronoUnit.MINUTES.between(
                        checkIn.getCheckInTime(),
                        LocalDateTime.now());
        long hours = workMinutes / 60;
        long mins = workMinutes % 60;

        webSocketEventPublisher.sendToRole(
                "admin",
                "Staff Checked Out",
                user.getFullName()
                        + " checked out after "
                        + hours + "h " + mins + "m",
                "ATTENDANCE_CHECK_OUT");

        log.info(
                "EOD Check-Out successful "
                        + "for userId={}, "
                        + "duration={}h{}m",
                userId, hours, mins);

        return mapToResponse(saved);
    }

    // ─── Today's Attendance ───────────────────────────────

    public AttendanceDTO.TodaySummaryDTO
    getTodaySummary(Long userId) {
        log.debug(
                "Getting today summary for "
                        + "userId={}",
                userId);

        User user = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "User not found: "
                                        + userId));

        LocalDateTime startOfDay =
                LocalDate.now().atStartOfDay();

        Optional<CheckInOut> record =
                checkInOutRepository
                        .findTodayByUserId(
                                userId, startOfDay);

        if (record.isEmpty()) {
            return AttendanceDTO.TodaySummaryDTO
                    .builder()
                    .userId(userId)
                    .userName(user.getFullName())
                    .isCheckedIn(false)
                    .currentStatus("NOT_CHECKED_IN")
                    .date(LocalDate.now()
                            .format(DATE_FMT))
                    .build();
        }

        CheckInOut checkIn = record.get();
        boolean checkedOut =
                checkIn.getCheckOutTime() != null;

        long elapsedMinutes = 0;
        if (checkIn.getCheckInTime() != null) {
            LocalDateTime endTime = checkedOut
                    ? checkIn.getCheckOutTime()
                    : LocalDateTime.now();
            elapsedMinutes =
                    ChronoUnit.MINUTES.between(
                            checkIn.getCheckInTime(),
                            endTime);
        }

        String status = checkedOut
                ? "CHECKED_OUT"
                : "CHECKED_IN";

        return AttendanceDTO.TodaySummaryDTO
                .builder()
                .userId(userId)
                .userName(user.getFullName())
                .isCheckedIn(true)
                .checkInTime(
                        checkIn.getCheckInTime())
                .checkInAddress(
                        checkIn.getCheckInAddress())
                .checkOutTime(
                        checkIn.getCheckOutTime())
                .checkOutAddress(
                        checkIn.getCheckOutAddress())
                .currentStatus(status)
                .elapsedMinutes(elapsedMinutes)
                .elapsedFormatted(
                        formatDuration(elapsedMinutes))
                .jobsCompleted(
                        checkIn.getJobsCompleted())
                .date(LocalDate.now()
                        .format(DATE_FMT))
                .build();
    }

    // ─── Attendance History ───────────────────────────────

    public AttendanceDTO
            .AttendanceHistorySummaryDTO
    getHistory(
            Long userId,
            LocalDateTime startDate,
            LocalDateTime endDate) {

        log.debug(
                "Getting attendance history "
                        + "for userId={}",
                userId);

        User user = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "User not found: "
                                        + userId));

        List<CheckInOut> records;

        if (startDate != null && endDate != null) {
            records = checkInOutRepository
                    .findByUserIdAndDateRange(
                            userId,
                            startDate,
                            endDate);
        } else {
            records = checkInOutRepository
                    .findByUserIdOrderByDesc(userId);
        }

        List<AttendanceDTO.AttendanceResponse>
                responses = records.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        // Calculate summary stats
        int presentDays = records.size();
        double totalWorkHours = records.stream()
                .filter(r ->
                        r.getCheckInTime() != null
                                && r.getCheckOutTime()
                                != null)
                .mapToLong(r ->
                        ChronoUnit.MINUTES.between(
                                r.getCheckInTime(),
                                r.getCheckOutTime()))
                .sum() / 60.0;

        double avgWorkHours = presentDays > 0
                ? totalWorkHours / presentDays
                : 0;

        int totalJobsCompleted = records.stream()
                .filter(r ->
                        r.getJobsCompleted() != null)
                .mapToInt(CheckInOut::getJobsCompleted)
                .sum();

        return AttendanceDTO
                .AttendanceHistorySummaryDTO
                .builder()
                .userId(userId)
                .userName(user.getFullName())
                .totalDays(presentDays)
                .presentDays(presentDays)
                .absentDays(0)
                .attendanceRate(
                        presentDays > 0
                                ? 100.0 : 0)
                .avgWorkingHours(
                        Math.round(
                                avgWorkHours * 10.0)
                                / 10.0)
                .totalJobsCompleted(
                        totalJobsCompleted)
                .records(responses)
                .build();
    }

    // ─── Team Today ───────────────────────────────────────

    public AttendanceDTO.TeamAttendanceDTO
    getTeamToday(Long branchId) {
        log.debug(
                "Getting team attendance "
                        + "for branchId={}",
                branchId);

        LocalDateTime startOfDay =
                LocalDate.now().atStartOfDay();

        // Get all team members for branch
        List<User> teamMembers =
                userRepository.findAll()
                        .stream()
                        .filter(u ->
                                 u.getBranchId()
                                        .equals(branchId)
                                        && u.getRole()
                                        != null
                                        && (u.getRole()
                                        .name()
                                        .equals(
                                                "TECHNICIAN")
                                        || u.getRole()
                                        .name()
                                        .equals(
                                                "TEAM_LEAD")))
                        .collect(Collectors.toList());

        // Get today's check-ins for this branch
        List<CheckInOut> todayRecords =
                checkInOutRepository
                        .findTeamTodayByBranchId(
                                branchId, startOfDay);

        // Build member attendance DTOs
        List<AttendanceDTO.MemberAttendanceDTO>
                memberDTOs = teamMembers.stream()
                .map(member -> {
                    Optional<CheckInOut> memberRecord =
                            todayRecords.stream()
                                    .filter(r ->
                                            r.getUser()
                                                    .getId()
                                                    .equals(
                                                            member.getId()))
                                    .findFirst();

                    boolean checkedIn =
                            memberRecord.isPresent();
                    boolean checkedOut =
                            checkedIn
                                    && memberRecord.get()
                                    .getCheckOutTime()
                                    != null;

                    long workMinutes = 0;
                    if (checkedIn) {
                        LocalDateTime endTime =
                                checkedOut
                                        ? memberRecord.get()
                                        .getCheckOutTime()
                                        : LocalDateTime
                                        .now();
                        workMinutes =
                                ChronoUnit.MINUTES
                                        .between(
                                                memberRecord
                                                        .get()
                                                        .getCheckInTime(),
                                                endTime);
                    }

                    String memberStatus;
                    if (!checkedIn) {
                        memberStatus = "ABSENT";
                    } else if (checkedOut) {
                        memberStatus = "CHECKED_OUT";
                    } else {
                        memberStatus = "CHECKED_IN";
                    }

                    String initial =
                            member.getFullName() != null
                                    && !member.getFullName()
                                    .isEmpty()
                                    ? String.valueOf(
                                            member.getFullName()
                                                    .charAt(0))
                                    .toUpperCase()
                                    : "T";

                    return AttendanceDTO
                            .MemberAttendanceDTO
                            .builder()
                            .userId(member.getId())
                            .userName(
                                    member.getFullName())
                            .userRole(
                                    member.getRole()
                                            != null
                                            ? member.getRole()
                                            .name()
                                            : "TECHNICIAN")
                            .phone(member.getPhone())
                            .avatarInitial(initial)
                            .isCheckedIn(checkedIn)
                            .isCheckedOut(checkedOut)
                            .checkInTime(
                                    checkedIn
                                            ? memberRecord
                                            .get()
                                            .getCheckInTime()
                                            : null)
                            .checkOutTime(
                                    checkedOut
                                            ? memberRecord
                                            .get()
                                            .getCheckOutTime()
                                            : null)
                            .workingDurationMinutes(
                                    workMinutes)
                            .workingDurationFormatted(
                                    formatDuration(
                                            workMinutes))
                            .jobsCompleted(
                                    checkedIn
                                            ? memberRecord
                                            .get()
                                            .getJobsCompleted()
                                            : 0)
                            .status(memberStatus)
                            .build();
                })
                .collect(Collectors.toList());

        // Calculate summary counts
        long checkedInCount = memberDTOs.stream()
                .filter(m ->
                        "CHECKED_IN"
                                .equals(m.getStatus())
                                || "CHECKED_OUT"
                                .equals(m.getStatus()))
                .count();
        long checkedOutCount = memberDTOs.stream()
                .filter(m ->
                        "CHECKED_OUT"
                                .equals(m.getStatus()))
                .count();
        long absentCount = memberDTOs.stream()
                .filter(m ->
                        "ABSENT"
                                .equals(m.getStatus()))
                .count();

        double attendanceRate =
                teamMembers.size() > 0
                        ? (checkedInCount * 100.0
                        / teamMembers.size())
                        : 0;

        return AttendanceDTO.TeamAttendanceDTO
                .builder()
                .teamId(branchId)
                .teamName("Branch " + branchId)
                .date(LocalDate.now()
                        .format(DATE_FMT))
                .totalMembers(teamMembers.size())
                .checkedIn((int) checkedInCount)
                .checkedOut((int) checkedOutCount)
                .absent((int) absentCount)
                .attendanceRate(Math.round(
                        attendanceRate * 10.0)
                        / 10.0)
                .members(memberDTOs)
                .build();
    }

    // ─── Map Entity to DTO ────────────────────────────────

    private AttendanceDTO.AttendanceResponse
    mapToResponse(CheckInOut record) {
        User user = record.getUser();

        long workMinutes = 0;
        if (record.getCheckInTime() != null
                && record.getCheckOutTime() != null) {
            workMinutes = ChronoUnit.MINUTES.between(
                    record.getCheckInTime(),
                    record.getCheckOutTime());
        } else if (record.getCheckInTime() != null
                && "CHECKED_IN"
                .equals(record.getStatus())) {
            workMinutes = ChronoUnit.MINUTES.between(
                    record.getCheckInTime(),
                    LocalDateTime.now());
        }

        return AttendanceDTO.AttendanceResponse
                .builder()
                .id(record.getId())
                .userId(user.getId())
                .userName(user.getFullName())
                .userRole(user.getRole() != null
                        ? user.getRole().name()
                        : null)
                .userPhone(user.getPhone())
                .checkInTime(record.getCheckInTime())
                .checkInLatitude(
                        record.getCheckInLatitude())
                .checkInLongitude(
                        record.getCheckInLongitude())
                .checkInAddress(
                        record.getCheckInAddress())
                .checkOutTime(record.getCheckOutTime())
                .checkOutLatitude(
                        record.getCheckOutLatitude())
                .checkOutLongitude(
                        record.getCheckOutLongitude())
                .checkOutAddress(
                        record.getCheckOutAddress())
                .status(record.getStatus())
                .workingDurationMinutes(workMinutes)
                .workingDurationFormatted(
                        formatDuration(workMinutes))
                .jobsCompleted(record.getJobsCompleted())
                .notes(record.getNotes())
                .date(record.getCheckInTime() != null
                        ? record.getCheckInTime()
                        .toLocalDate()
                        .format(DATE_FMT)
                        : null)
                .createdAt(record.getCheckInTime())
                .build();
    }

    // ─── Format Duration ──────────────────────────────────

    private String formatDuration(long minutes) {
        if (minutes <= 0) return "0m";
        long hours = minutes / 60;
        long mins = minutes % 60;
        if (hours == 0) return mins + "m";
        if (mins == 0) return hours + "h";
        return hours + "h " + mins + "m";
    }
}