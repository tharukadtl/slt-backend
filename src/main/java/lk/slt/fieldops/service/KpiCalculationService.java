package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.KpiDTO;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation
        .Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KpiCalculationService {

    private final UserRepository
            userRepository;
    private final JobRepository
            jobRepository;
    private final PaymentRepository
            paymentRepository;
    private final KpiTargetRepository
            kpiTargetRepository;
    private final KpiScoreRepository
            kpiScoreRepository;
    private final CheckInOutRepository
            checkInOutRepository;

    private static final DateTimeFormatter
            DATE_FMT =
            DateTimeFormatter
                    .ofPattern("yyyy-MM-dd");

    // ─── Score Weights ────────────────────────────────────
    private static final double
            WEIGHT_COMPLETION = 0.35;
    private static final double
            WEIGHT_SATISFACTION = 0.25;
    private static final double
            WEIGHT_ON_TIME = 0.20;
    private static final double
            WEIGHT_RESPONSE_TIME = 0.10;
    private static final double
            WEIGHT_ATTENDANCE = 0.10;

    // ─── Get Personal KPI Score ───────────────────────────

    @Transactional
    public KpiDTO.PersonalKpiDTO
    getPersonalKpi(
            Long userId, String period) {
        log.info(
                "Calculating KPI for "
                        + "userId={}, period={}",
                userId, period);

        User user = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "User not found: "
                                        + userId));

        LocalDate[] range =
                getDateRange(period);
        LocalDate startDate = range[0];
        LocalDate endDate = range[1];

        // ── Fetch all jobs ──────────────────────
        List<Job> allJobs =
                jobRepository.findAll().stream()
                        .filter(j -> userId.equals(j.getTechnicianId()))
                        .collect(Collectors.toList());

        List<Job> periodJobs = allJobs.stream()
                .filter(j ->
                        j.getCreatedAt() != null
                                && !j.getCreatedAt()
                                .toLocalDate()
                                .isBefore(startDate)
                                && !j.getCreatedAt()
                                .toLocalDate()
                                .isAfter(endDate))
                .collect(Collectors.toList());

        // ── Core metrics ────────────────────────
        long totalJobs = periodJobs.size();
        long completed = periodJobs.stream()
                .filter(j ->
                        j.getStatus() != null
                                && "COMPLETED"
                                .equals(j.getStatus()
                                        .name()))
                .count();
        long inProgress = periodJobs.stream()
                .filter(j ->
                        j.getStatus() != null
                                && ("IN_PROGRESS"
                                .equals(j.getStatus()
                                        .name())
                                || "TRAVELLING"
                                .equals(j.getStatus()
                                        .name())))
                .count();
        long cancelled = periodJobs.stream()
                .filter(j ->
                        j.getStatus() != null
                                && "CANCELLED"
                                .equals(j.getStatus()
                                        .name()))
                .count();

        double completionRate = totalJobs > 0
                ? (completed * 100.0 / totalJobs)
                : 0;

        // ── Revenue ─────────────────────────────
        double revenue = paymentRepository
                .findAll().stream()
                .filter(p ->
                        userId.equals(p.getTeamLeadId())
                                && p.getStatus() != null
                                && "APPROVED"
                                .equals(p.getStatus()
                                        .name())
                                && p.getCreatedAt()
                                != null
                                && !p.getCreatedAt()
                                .toLocalDate()
                                .isBefore(startDate)
                                && !p.getCreatedAt()
                                .toLocalDate()
                                .isAfter(endDate))
                .mapToDouble(p ->
                        p.getTotalAmount() != null
                                ? p.getTotalAmount().doubleValue()
                                : 0)
                .sum();

        // ── Attendance ──────────────────────────
        LocalDateTime startDateTime =
                startDate.atStartOfDay();
        LocalDateTime endDateTime =
                endDate.atTime(23, 59, 59);

        List<CheckInOut> attendanceRecords =
                checkInOutRepository
                        .findByUserIdAndDateRange(
                                userId,
                                startDateTime,
                                endDateTime);

        int presentDays =
                attendanceRecords.size();
        long totalWorkMinutes =
                attendanceRecords.stream()
                        .filter(r ->
                                r.getCheckInTime()
                                        != null
                                        && r.getCheckOutTime()
                                        != null)
                        .mapToLong(r -> {
                            long mins =
                                    java.time.temporal
                                            .ChronoUnit
                                            .MINUTES
                                            .between(
                                                    r.getCheckInTime(),
                                                    r.getCheckOutTime());
                            return Math.max(0, mins);
                        })
                        .sum();

        double avgWorkHours = presentDays > 0
                ? (totalWorkMinutes / 60.0
                / presentDays)
                : 0;

        int expectedDays =
                getExpectedWorkingDays(
                        period);
        double attendanceRate =
                expectedDays > 0
                        ? Math.min(
                        presentDays * 100.0
                                / expectedDays,
                        100.0)
                        : 0;

        // ── Mock satisfaction scores ─────────────
        // Replace with actual rating data
        // when implemented
        double satisfaction = 4.5;
        double onTimeRate = completionRate > 0
                ? Math.min(
                completionRate * 0.9, 100)
                : 0;
        double avgDuration = totalJobs > 0
                ? 2.3 : 0;
        double avgResponseTime = 22.0;

        // ── Calculate overall score ──────────────
        double overallScore =
                calculateOverallScore(
                        completionRate,
                        satisfaction,
                        onTimeRate,
                        avgResponseTime,
                        attendanceRate);

        String perfLevel =
                getPerformanceLevel(overallScore);
        String perfColor =
                getPerformanceColor(overallScore);
        double starRating =
                overallScore / 20.0;

        // ── Fetch targets ────────────────────────
        List<KpiTarget> targets =
                kpiTargetRepository
                        .findActiveByUserIdAndPeriod(
                                userId, period);

        List<KpiDTO.TargetResponseDTO>
                targetDTOs = targets.stream()
                .map(this::mapTargetToDTO)
                .collect(Collectors.toList());

        long achievedTargets = targetDTOs.stream()
                .filter(t ->
                        "ACHIEVED"
                                .equals(t.getStatus()))
                .count();
        long onTrackTargets = targetDTOs.stream()
                .filter(t ->
                        "ON_TRACK"
                                .equals(t.getStatus()))
                .count();
        long atRiskTargets = targetDTOs.stream()
                .filter(t ->
                        "AT_RISK"
                                .equals(t.getStatus()))
                .count();
        long behindTargets = targetDTOs.stream()
                .filter(t ->
                        "BEHIND"
                                .equals(t.getStatus()))
                .count();

        // ── Save score to DB ─────────────────────
        saveKpiScore(user, period,
                totalJobs, completed,
                completionRate, avgDuration,
                avgResponseTime, satisfaction,
                onTimeRate, revenue,
                attendanceRate, overallScore,
                perfLevel,
                Math.round(starRating * 10.0)
                        / 10.0);

        String initial =
                user.getFullName() != null
                        && !user.getFullName().isEmpty()
                        ? String.valueOf(
                                user.getFullName().charAt(0))
                        .toUpperCase()
                        : "T";

        return KpiDTO.PersonalKpiDTO.builder()
                .technicianId(userId)
                .technicianName(user.getFullName())
                .phone(user.getPhone())
                .avatarInitial(initial)
                .period(period)
                .startDate(startDate.format(DATE_FMT))
                .endDate(endDate.format(DATE_FMT))
                .totalJobs(totalJobs)
                .completedJobs(completed)
                .inProgressJobs(inProgress)
                .cancelledJobs(cancelled)
                .completionRate(round(completionRate))
                .avgJobDurationHours(
                        round(avgDuration))
                .avgResponseTimeMinutes(
                        round(avgResponseTime))
                .customerSatisfactionScore(
                        satisfaction)
                .onTimeCompletionRate(
                        round(onTimeRate))
                .totalRevenue(
                        round(revenue))
                .presentDays(presentDays)
                .avgWorkingHours(round(avgWorkHours))
                .attendanceRate(
                        round(attendanceRate))
                .overallScore(round(overallScore))
                .performanceLevel(perfLevel)
                .performanceColor(perfColor)
                .starRating(
                        Math.round(
                                starRating * 10.0)
                                / 10.0)
                .totalTargets(targets.size())
                .achievedTargets(
                        (int) achievedTargets)
                .onTrackTargets(
                        (int) onTrackTargets)
                .atRiskTargets((int) atRiskTargets)
                .behindTargets((int) behindTargets)
                .targets(targetDTOs)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ─── Get Team KPI ─────────────────────────────────────

    public KpiDTO.TeamKpiDTO getTeamKpi(
            Long branchId, String period) {
        log.info(
                "Calculating team KPI for "
                        + "branchId={}, period={}",
                branchId, period);

        LocalDate[] range = getDateRange(period);

        List<User> members =
                userRepository.findAll().stream()
                        .filter(u ->
                                branchId.equals(
                                        u.getBranchId())
                                        && u.getRole()
                                        != null
                                        && ("TECHNICIAN"
                                        .equals(u.getRole()
                                                .name())
                                        || "TEAM_LEAD"
                                        .equals(u.getRole()
                                                .name())))
                        .collect(Collectors.toList());

        List<KpiDTO.PersonalKpiDTO> memberKpis =
                members.stream()
                        .map(m -> getPersonalKpi(
                                m.getId(), period))
                        .collect(Collectors.toList());

        // ── Aggregate team metrics ───────────────
        double teamCompletionRate =
                memberKpis.stream()
                        .mapToDouble(
                                KpiDTO.PersonalKpiDTO
                                        ::getCompletionRate)
                        .average()
                        .orElse(0);

        double teamSatisfaction =
                memberKpis.stream()
                        .mapToDouble(
                                KpiDTO.PersonalKpiDTO
                                        ::getCustomerSatisfactionScore)
                        .average()
                        .orElse(0);

        double teamOnTimeRate =
                memberKpis.stream()
                        .mapToDouble(
                                KpiDTO.PersonalKpiDTO
                                        ::getOnTimeCompletionRate)
                        .average()
                        .orElse(0);

        double teamRevenue =
                memberKpis.stream()
                        .mapToDouble(
                                KpiDTO.PersonalKpiDTO
                                        ::getTotalRevenue)
                        .sum();

        double teamAttendance =
                memberKpis.stream()
                        .mapToDouble(
                                KpiDTO.PersonalKpiDTO
                                        ::getAttendanceRate)
                        .average()
                        .orElse(0);

        double teamOverallScore =
                memberKpis.stream()
                        .mapToDouble(
                                KpiDTO.PersonalKpiDTO
                                        ::getOverallScore)
                        .average()
                        .orElse(0);

        long totalJobs = memberKpis.stream()
                .mapToLong(
                        KpiDTO.PersonalKpiDTO
                                ::getTotalJobs)
                .sum();

        long totalCompleted = memberKpis.stream()
                .mapToLong(
                        KpiDTO.PersonalKpiDTO
                                ::getCompletedJobs)
                .sum();

        double avgDuration = memberKpis.stream()
                .mapToDouble(
                        KpiDTO.PersonalKpiDTO
                                ::getAvgJobDurationHours)
                .average()
                .orElse(0);

        double avgResponse = memberKpis.stream()
                .mapToDouble(
                        KpiDTO.PersonalKpiDTO
                                ::getAvgResponseTimeMinutes)
                .average()
                .orElse(0);

        return KpiDTO.TeamKpiDTO.builder()
                .branchId(branchId)
                .branchName("Branch " + branchId)
                .period(period)
                .startDate(
                        range[0].format(DATE_FMT))
                .endDate(range[1].format(DATE_FMT))
                .totalMembers(members.size())
                .activeMembers(members.size())
                .totalJobsAssigned(totalJobs)
                .totalJobsCompleted(totalCompleted)
                .teamCompletionRate(
                        round(teamCompletionRate))
                .avgJobDurationHours(
                        round(avgDuration))
                .avgResponseTimeMinutes(
                        round(avgResponse))
                .teamSatisfactionScore(
                        round(teamSatisfaction))
                .teamOnTimeRate(
                        round(teamOnTimeRate))
                .totalTeamRevenue(
                        round(teamRevenue))
                .teamAttendanceRate(
                        round(teamAttendance))
                .teamOverallScore(
                        round(teamOverallScore))
                .memberKpis(memberKpis)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ─── Assign Target ────────────────────────────────────

    @Transactional
    public KpiDTO.TargetResponseDTO assignTarget(
            Long adminId,
            KpiDTO.AssignTargetRequest request) {
        log.info(
                "Assigning target to "
                        + "technicianId={}",
                request.getTechnicianId());

        User technician = userRepository
                .findById(request.getTechnicianId())
                .orElseThrow(() ->
                        new RuntimeException(
                                "Technician not found: "
                                        + request
                                        .getTechnicianId()));

        User admin = userRepository
                .findById(adminId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Admin not found: "
                                        + adminId));

        KpiTarget target = KpiTarget.builder()
                .user(technician)
                .assignedBy(admin)
                .title(request.getTitle())
                .description(request.getDescription())
                .targetValue(request.getTargetValue())
                .currentValue(0.0)
                .unit(request.getUnit())
                .period(request.getPeriod())
                .category(request.getCategory())
                .dueDate(request.getDueDate())
                .startDate(LocalDate.now())
                .status(KpiDTO.STATUS_ON_TRACK)
                .isGroupTarget(
                        request.isGroupTarget())
                .isActive(true)
                .build();

        KpiTarget saved =
                kpiTargetRepository.save(target);
        log.info(
                "Target assigned: id={}",
                saved.getId());

        return mapTargetToDTO(saved);
    }

    // ─── Get My Targets ───────────────────────────────────

    public List<KpiDTO.TargetResponseDTO>
    getMyTargets(Long userId, String period) {
        log.debug(
                "Getting targets for userId={}",
                userId);

        List<KpiTarget> targets;
        if (period != null
                && !period.isEmpty()) {
            targets = kpiTargetRepository
                    .findActiveByUserIdAndPeriod(
                            userId, period);
        } else {
            targets = kpiTargetRepository
                    .findActiveByUserId(userId);
        }

        return targets.stream()
                .map(this::mapTargetToDTO)
                .collect(Collectors.toList());
    }

    // ─── Get Technician Targets ───────────────────────────

    public List<KpiDTO.TargetResponseDTO>
    getTechnicianTargets(
            Long technicianId) {
        log.debug(
                "Getting targets for "
                        + "technicianId={}",
                technicianId);

        return kpiTargetRepository
                .findActiveByUserId(technicianId)
                .stream()
                .map(this::mapTargetToDTO)
                .collect(Collectors.toList());
    }

    // ─── Get Leaderboard ──────────────────────────────────

    public List<KpiDTO.LeaderboardEntryDTO>
    getLeaderboard(
            String period, Long currentUserId) {
        log.info(
                "Generating leaderboard "
                        + "for period={}",
                period);

        List<User> technicians =
                userRepository.findAll().stream()
                        .filter(u ->
                                u.getRole() != null
                                        && ("TECHNICIAN"
                                        .equals(u.getRole()
                                                .name())
                                        || "TEAM_LEAD"
                                        .equals(u.getRole()
                                                .name())))
                        .collect(Collectors.toList());

        List<KpiDTO.LeaderboardEntryDTO>
                leaderboard = new ArrayList<>();

        for (User tech : technicians) {
            List<Job> jobs =
                    jobRepository.findAll().stream()
                            .filter(j -> tech.getId().equals(j.getTechnicianId()))
                            .collect(Collectors.toList());

            long completed = jobs.stream()
                    .filter(j ->
                            j.getStatus() != null
                                    && "COMPLETED"
                                    .equals(j.getStatus()
                                            .name()))
                    .count();

            double compRate = jobs.size() > 0
                    ? (completed * 100.0
                    / jobs.size())
                    : 0;
            double satisfaction = 4.5;
            double onTime = compRate * 0.9;
            double attendance = 90.0;

            double score =
                    calculateOverallScore(
                            compRate,
                            satisfaction,
                            onTime, 22.0,
                            attendance);

            String perfLevel =
                    getPerformanceLevel(score);
            String perfColor =
                    getPerformanceColor(score);
            double starRating =
                    Math.round(
                            (score / 20.0) * 10.0)
                            / 10.0;

            String initial =
                    tech.getFullName() != null
                            && !tech.getFullName()
                            .isEmpty()
                            ? String.valueOf(
                                    tech.getFullName()
                                            .charAt(0))
                            .toUpperCase()
                            : "T";

            leaderboard.add(
                    KpiDTO.LeaderboardEntryDTO
                            .builder()
                            .technicianId(tech.getId())
                            .technicianName(
                                    tech.getFullName())
                            .phone(tech.getPhone())
                            .avatarInitial(initial)
                            .branchName(
                                    tech.getBranchName()
                                            != null
                                            ? tech.getBranchName()
                                            : "N/A")
                            .overallScore(
                                    round(score))
                            .completionRate(
                                    round(compRate))
                            .satisfactionScore(
                                    satisfaction)
                            .completedJobs(completed)
                            .performanceLevel(
                                    perfLevel)
                            .performanceColor(
                                    perfColor)
                            .starRating(starRating)
                            .trend("STABLE")
                            .isCurrentUser(
                                    tech.getId()
                                            .equals(
                                                    currentUserId))
                            .build());
        }

        // Sort by score descending and add rank
        leaderboard.sort((a, b) ->
                Double.compare(
                        b.getOverallScore(),
                        a.getOverallScore()));

        for (int i = 0;
             i < leaderboard.size(); i++) {
            leaderboard.get(i).setRank(i + 1);
            String badge = getBadge(i + 1);
            leaderboard.get(i).setBadge(badge);
        }

        return leaderboard;
    }

    // ─── Save KPI Score ───────────────────────────────────

    @Transactional
    private void saveKpiScore(
            User user,
            String period,
            long totalJobs,
            long completedJobs,
            double completionRate,
            double avgDuration,
            double avgResponse,
            double satisfaction,
            double onTimeRate,
            double revenue,
            double attendanceRate,
            double overallScore,
            String perfLevel,
            double starRating) {
        try {
            KpiScore score = KpiScore.builder()
                    .user(user)
                    .period(period)
                    .scoreDate(LocalDate.now())
                    .totalJobs(totalJobs)
                    .completedJobs(completedJobs)
                    .completionRate(
                            round(completionRate))
                    .avgJobDurationHours(
                            round(avgDuration))
                    .avgResponseTimeMinutes(
                            round(avgResponse))
                    .customerSatisfactionScore(
                            satisfaction)
                    .onTimeCompletionRate(
                            round(onTimeRate))
                    .totalRevenue(round(revenue))
                    .attendanceRate(
                            round(attendanceRate))
                    .overallScore(round(overallScore))
                    .performanceLevel(perfLevel)
                    .starRating(starRating)
                    .calculatedAt(
                            LocalDateTime.now())
                    .build();

            kpiScoreRepository.save(score);
        } catch (Exception e) {
            log.error(
                    "Error saving KPI score: {}",
                    e.getMessage());
        }
    }

    // ─── Map Target to DTO ────────────────────────────────

    public KpiDTO.TargetResponseDTO
    mapTargetToDTO(KpiTarget target) {
        double progress =
                target.getTargetValue() != null
                        && target.getTargetValue() > 0
                        && target.getCurrentValue()
                        != null
                        ? Math.min(
                        (target.getCurrentValue()
                                / target.getTargetValue())
                                * 100,
                        100)
                        : 0;

        String status =
                calculateTargetStatus(
                        progress,
                        target.getDueDate());

        return KpiDTO.TargetResponseDTO.builder()
                .id(target.getId())
                .technicianId(
                        target.getUser() != null
                                ? target.getUser().getId()
                                : null)
                .technicianName(
                        target.getUser() != null
                                ? target.getUser()
                                .getFullName()
                                : null)
                .title(target.getTitle())
                .description(target.getDescription())
                .targetValue(target.getTargetValue())
                .currentValue(
                        target.getCurrentValue())
                .unit(target.getUnit())
                .period(target.getPeriod())
                .category(target.getCategory())
                .categoryIcon(getCategoryIcon(
                        target.getCategory()))
                .dueDate(target.getDueDate())
                .status(status)
                .statusIcon(getStatusIcon(status))
                .progressPercent(round(progress))
                .assignedBy(
                        target.getAssignedBy() != null
                                ? target.getAssignedBy()
                                .getFullName()
                                : "Admin")
                .assignedAt(target.getCreatedAt())
                .isGroupTarget(
                        target.getIsGroupTarget()
                                != null
                                && target.getIsGroupTarget())
                .branchId(
                        target.getBranch() != null
                                ? target.getBranch()
                                .getId()
                                : null)
                .branchName(
                        target.getBranch() != null
                                ? target.getBranch()
                                .getName()
                                : null)
                .build();
    }

    // ─── Private Calculation Helpers ──────────────────────

    private double calculateOverallScore(
            double completionRate,
            double satisfaction,
            double onTimeRate,
            double avgResponseTime,
            double attendanceRate) {

        // Response time score (lower = better)
        // 10 mins = 100, 60 mins = 0
        double responseScore =
                Math.max(0,
                        100 - (avgResponseTime - 10)
                                * 2);

        // Satisfaction score (0-5 → 0-100)
        double satisfactionScore =
                (satisfaction / 5.0) * 100;

        double overall =
                (completionRate
                        * WEIGHT_COMPLETION)
                        + (satisfactionScore
                        * WEIGHT_SATISFACTION)
                        + (onTimeRate
                        * WEIGHT_ON_TIME)
                        + (responseScore
                        * WEIGHT_RESPONSE_TIME)
                        + (attendanceRate
                        * WEIGHT_ATTENDANCE);

        return Math.min(100,
                Math.max(0, overall));
    }

    private String calculateTargetStatus(
            double progress,
            LocalDate dueDate) {
        if (progress >= 100) {
            return KpiDTO.STATUS_ACHIEVED;
        }

        if (dueDate == null) {
            return progress >= 75
                    ? KpiDTO.STATUS_ON_TRACK
                    : KpiDTO.STATUS_AT_RISK;
        }

        long daysRemaining =
                java.time.temporal.ChronoUnit
                        .DAYS.between(
                                LocalDate.now(), dueDate);

        if (daysRemaining < 0) {
            return KpiDTO.STATUS_BEHIND;
        }

        if (progress >= 75) {
            return KpiDTO.STATUS_ON_TRACK;
        } else if (progress >= 50) {
            return KpiDTO.STATUS_AT_RISK;
        } else {
            return KpiDTO.STATUS_BEHIND;
        }
    }

    private LocalDate[] getDateRange(
            String period) {
        LocalDate end = LocalDate.now();
        LocalDate start;

        switch (period.toUpperCase()) {
            case "DAILY":
                start = end;
                break;
            case "WEEKLY":
                start = end.minusDays(6);
                break;
            case "MONTHLY":
                start = end.withDayOfMonth(1);
                break;
            default:
                start = end.withDayOfMonth(1);
        }

        return new LocalDate[]{start, end};
    }

    private int getExpectedWorkingDays(
            String period) {
        switch (period.toUpperCase()) {
            case "DAILY": return 1;
            case "WEEKLY": return 5;
            case "MONTHLY": return 22;
            default: return 22;
        }
    }

    private String getPerformanceLevel(
            double score) {
        if (score >= 90) return "EXCELLENT";
        if (score >= 75) return "GOOD";
        if (score >= 60) return "AVERAGE";
        if (score >= 40) return "BELOW_AVERAGE";
        return "NEEDS_IMPROVEMENT";
    }

    private String getPerformanceColor(
            double score) {
        if (score >= 90) return "#4CAF50";
        if (score >= 75) return "#2196F3";
        if (score >= 60) return "#FF9800";
        if (score >= 40) return "#FF5722";
        return "#F44336";
    }

    private String getBadge(int rank) {
        switch (rank) {
            case 1: return "🥇";
            case 2: return "🥈";
            case 3: return "🥉";
            default: return rank <= 10
                    ? "⭐" : "";
        }
    }

    private String getCategoryIcon(
            String category) {
        if (category == null) return "📊";
        switch (category.toUpperCase()) {
            case "JOBS": return "📋";
            case "TIME": return "⏱️";
            case "SATISFACTION": return "⭐";
            case "REVENUE": return "💰";
            case "ATTENDANCE": return "📅";
            default: return "📊";
        }
    }

    private String getStatusIcon(String status) {
        if (status == null) return "📊";
        switch (status) {
            case "ACHIEVED": return "🏆";
            case "ON_TRACK": return "✅";
            case "AT_RISK": return "⚠️";
            case "BEHIND": return "❌";
            default: return "📊";
        }
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}