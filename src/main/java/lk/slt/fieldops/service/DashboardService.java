package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.DashboardDTO;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final FaultRepository
            faultRepository;
    private final UserRepository
            userRepository;
    private final JobRepository
            jobRepository;
    private final PaymentRepository
            paymentRepository;
    private final TechnicianLocationRepository
            locationRepository;
    private final NotificationRepository
            notificationRepository;

    private static final DateTimeFormatter
            DATE_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter
            DAY_FMT = DateTimeFormatter
            .ofPattern("EEE");

    // ─── KPI Summary ──────────────────────────────────────

    public DashboardDTO.KpiSummaryDTO
    getKpiSummary() {
        log.info("Generating KPI summary");

        LocalDateTime todayStart =
                LocalDate.now()
                        .atStartOfDay();
        LocalDateTime monthStart =
                LocalDate.now()
                        .withDayOfMonth(1)
                        .atStartOfDay();
        LocalDateTime prevMonthStart =
                LocalDate.now()
                        .minusMonths(1)
                        .withDayOfMonth(1)
                        .atStartOfDay();
        LocalDateTime prevMonthEnd =
                LocalDate.now()
                        .withDayOfMonth(1)
                        .atStartOfDay()
                        .minusSeconds(1);

        List<Fault> allFaults =
                faultRepository.findAll();
        List<Payment> allPayments =
                paymentRepository.findAll();
        List<User> allUsers =
                userRepository.findAll();

        // Fault counts
        long totalFaults = allFaults.size();
        long openFaults = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "OPEN".equals(
                        f.getStatus().name()))
                .count();
        long inProgress = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "IN_PROGRESS".equals(
                        f.getStatus().name()))
                .count();
        long completedToday = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "COMPLETED".equals(
                        f.getStatus().name())
                        && f.getUpdatedAt() != null
                        && f.getUpdatedAt()
                        .isAfter(todayStart))
                .count();
        long completedMonth = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "COMPLETED".equals(
                        f.getStatus().name())
                        && f.getUpdatedAt() != null
                        && f.getUpdatedAt()
                        .isAfter(monthStart))
                .count();
        long cancelled = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "CANCELLED".equals(
                        f.getStatus().name()))
                .count();

        // Payment counts
        long pendingPayments = allPayments.stream()
                .filter(p -> p.getStatus() != null
                        && "PENDING".equals(
                        p.getStatus().name()))
                .count();
        long approvedPayments = allPayments.stream()
                .filter(p -> p.getStatus() != null
                        && "APPROVED".equals(
                        p.getStatus().name()))
                .count();

        // Revenue this month
        double revenueThisMonth =
                allPayments.stream()
                        .filter(p ->
                                p.getStatus() != null
                                        && "APPROVED"
                                        .equals(p.getStatus()
                                                .name())
                                        && p.getCreatedAt()
                                        != null
                                        && p.getCreatedAt()
                                        .isAfter(
                                                monthStart))
                        .mapToDouble(p ->
                                p.getTotalAmount()
                                        != null
                                        ? p.getTotalAmount()
                                        .doubleValue()
                                        : 0)
                        .sum();

        // User counts
        long totalTechnicians =
                allUsers.stream()
                        .filter(u ->
                                u.getRole() != null
                                        && ("TECHNICIAN"
                                        .equals(u.getRole()
                                                .name())
                                        || "TEAM_LEAD"
                                        .equals(u.getRole()
                                                .name())))
                        .count();

        // Active technicians (location updated
        // in last 30 mins)
        LocalDateTime activeSince =
                LocalDateTime.now()
                        .minusMinutes(30);
        long activeTechnicians =
                locationRepository
                        .countRecentlyActive(
                                activeSince);

        // Completion rate
        double completionRate = totalFaults > 0
                ? (completedMonth * 100.0
                / totalFaults)
                : 0;

        return DashboardDTO.KpiSummaryDTO.builder()
                .totalFaults(totalFaults)
                .openFaults(openFaults)
                .inProgressFaults(inProgress)
                .completedToday(completedToday)
                .completedThisMonth(completedMonth)
                .cancelledFaults(cancelled)
                .pendingPayments(pendingPayments)
                .approvedPayments(approvedPayments)
                .totalTechnicians(totalTechnicians)
                .activeTechnicians(activeTechnicians)
                .totalUsers(allUsers.size())
                .avgResolutionTimeHours(2.8)
                .customerSatisfactionScore(4.5)
                .completionRate(Math.round(
                        completionRate * 10.0)
                        / 10.0)
                .onTimeCompletionRate(87.5)
                .totalRevenueThisMonth(
                        revenueThisMonth)
                .generatedAt(LocalDateTime.now())
                .faultsTrend(
                        openFaults > 10
                                ? "UP" : "DOWN")
                .completionTrend(
                        completionRate > 80
                                ? "UP" : "DOWN")
                .revenueTrend("UP")
                .satisfactionTrend("STABLE")
                .build();
    }

    // ─── Fault Distribution ───────────────────────────────

    public DashboardDTO.FaultDistributionDTO
    getFaultDistribution() {
        log.info("Generating fault distribution");

        List<Fault> allFaults =
                faultRepository.findAll();
        long total = allFaults.size();

        // By status
        long open = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "OPEN".equals(
                        f.getStatus().name()))
                .count();
        long inProgress = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "IN_PROGRESS".equals(
                        f.getStatus().name()))
                .count();
        long completed = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "COMPLETED".equals(
                        f.getStatus().name()))
                .count();
        long cancelled = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "CANCELLED".equals(
                        f.getStatus().name()))
                .count();
        long pending = allFaults.stream()
                .filter(f -> f.getStatus() != null
                        && "PENDING".equals(
                        f.getStatus().name()))
                .count();

        // By category
        Map<String, Long> byCat =
                allFaults.stream()
                        .collect(Collectors.groupingBy(
                                f -> f.getCategory()
                                        != null
                                        ? f.getCategory()
                                        .toString()
                                        : "OTHER",
                                Collectors.counting()));

        List<DashboardDTO.CategoryCountDTO>
                categoryList = byCat.entrySet()
                .stream()
                .map(e ->
                        DashboardDTO.CategoryCountDTO
                                .builder()
                                .category(e.getKey())
                                .count(e.getValue())
                                .percentage(total > 0
                                        ? Math.round(
                                        e.getValue()
                                                * 1000.0
                                                / total)
                                        / 10.0
                                        : 0)
                                .color(
                                        getCategoryColor(
                                                e.getKey()))
                                .build())
                .sorted((a, b) -> Long.compare(
                        b.getCount(),
                        a.getCount()))
                .collect(Collectors.toList());

        // By priority
        Map<String, Long> byPri =
                allFaults.stream()
                        .collect(Collectors.groupingBy(
                                f -> f.getPriority()
                                        != null
                                        ? f.getPriority()
                                        .toString()
                                        : "MEDIUM",
                                Collectors.counting()));

        List<DashboardDTO.PriorityCountDTO>
                priorityList = byPri.entrySet()
                .stream()
                .map(e ->
                        DashboardDTO.PriorityCountDTO
                                .builder()
                                .priority(e.getKey())
                                .count(e.getValue())
                                .percentage(total > 0
                                        ? Math.round(
                                        e.getValue()
                                                * 1000.0
                                                / total)
                                        / 10.0
                                        : 0)
                                .color(
                                        getPriorityColor(
                                                e.getKey()))
                                .build())
                .collect(Collectors.toList());

        return DashboardDTO.FaultDistributionDTO
                .builder()
                .open(open)
                .inProgress(inProgress)
                .completed(completed)
                .cancelled(cancelled)
                .pending(pending)
                .total(total)
                .openPercent(total > 0
                        ? Math.round(
                        open * 1000.0 / total)
                        / 10.0 : 0)
                .inProgressPercent(total > 0
                        ? Math.round(
                        inProgress * 1000.0 / total)
                        / 10.0 : 0)
                .completedPercent(total > 0
                        ? Math.round(
                        completed * 1000.0 / total)
                        / 10.0 : 0)
                .cancelledPercent(total > 0
                        ? Math.round(
                        cancelled * 1000.0 / total)
                        / 10.0 : 0)
                .byCategory(categoryList)
                .byPriority(priorityList)
                .build();
    }

    // ─── Fault Trends ─────────────────────────────────────

    public List<DashboardDTO.FaultTrendPointDTO>
    getFaultTrends(int days) {
        log.info(
                "Generating fault trends "
                        + "for {} days",
                days);

        List<Fault> allFaults =
                faultRepository.findAll();

        List<DashboardDTO.FaultTrendPointDTO>
                trends = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate =
                endDate.minusDays(days - 1);

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            final LocalDate date = current;

            List<Fault> dayFaults =
                    allFaults.stream()
                            .filter(f ->
                                    f.getCreatedAt()
                                            != null
                                            && f.getCreatedAt()
                                            .toLocalDate()
                                            .equals(date))
                            .collect(
                                    Collectors.toList());

            long completedDay = allFaults.stream()
                    .filter(f ->
                            f.getStatus() != null
                                    && "COMPLETED"
                                    .equals(f.getStatus()
                                            .name())
                                    && f.getUpdatedAt()
                                    != null
                                    && f.getUpdatedAt()
                                    .toLocalDate()
                                    .equals(date))
                    .count();

            long cancelledDay = allFaults.stream()
                    .filter(f ->
                            f.getStatus() != null
                                    && "CANCELLED"
                                    .equals(f.getStatus()
                                            .name())
                                    && f.getUpdatedAt()
                                    != null
                                    && f.getUpdatedAt()
                                    .toLocalDate()
                                    .equals(date))
                    .count();

            trends.add(
                    DashboardDTO.FaultTrendPointDTO
                            .builder()
                            .date(date.format(DATE_FMT))
                            .dayOfWeek(
                                    date.format(DAY_FMT))
                            .total(dayFaults.size())
                            .opened(dayFaults.size())
                            .completed(completedDay)
                            .cancelled(cancelledDay)
                            .avgResolutionHours(2.5)
                            .build());

            current = current.plusDays(1);
        }

        return trends;
    }

    // ─── Technician Performance ───────────────────────────

    public List<DashboardDTO
            .TechnicianPerformanceDTO>
    getTechnicianPerformance() {
        log.info(
                "Generating technician "
                        + "performance summary");

        List<User> technicians =
                userRepository.findAll()
                        .stream()
                        .filter(u ->
                                u.getRole() != null
                                        && ("TECHNICIAN"
                                        .equals(u.getRole()
                                                .name())
                                        || "TEAM_LEAD"
                                        .equals(u.getRole()
                                                .name())))
                        .collect(Collectors.toList());

        List<Job> allJobs =
                jobRepository.findAll();

        LocalDateTime activeSince =
                LocalDateTime.now()
                        .minusMinutes(30);

        return technicians.stream().map(tech -> {
                    List<Job> techJobs = allJobs.stream()
                            .filter(j -> tech.getId().equals(j.getTechnicianId()))
                            .collect(Collectors.toList());

                    long completed = techJobs.stream()
                            .filter(j ->
                                    j.getStatus() != null
                                            && "COMPLETED"
                                            .equals(j.getStatus()
                                                    .name()))
                            .count();
                    long active = techJobs.stream()
                            .filter(j ->
                                    j.getStatus() != null
                                            && ("IN_PROGRESS"
                                            .equals(j.getStatus()
                                                    .name())
                                            || "TRAVELLING"
                                            .equals(j.getStatus()
                                                    .name())))
                            .count();

                    double rate = techJobs.size() > 0
                            ? (completed * 100.0
                            / techJobs.size())
                            : 0;

                    boolean isOnline = locationRepository
                            .countRecentlyActive(
                                    activeSince) > 0;

                    String perfLevel =
                            rate >= 90 ? "EXCELLENT"
                                    : rate >= 75 ? "GOOD"
                                    : rate >= 60 ? "AVERAGE"
                                    : "NEEDS_IMPROVEMENT";

                    return DashboardDTO
                            .TechnicianPerformanceDTO
                            .builder()
                            .technicianId(tech.getId())
                            .name(tech.getFullName())
                            .phone(tech.getPhone())
                            .avatarInitial(
                                    tech.getFullName() != null
                                            && !tech.getFullName()
                                            .isEmpty()
                                            ? String.valueOf(
                                                    tech.getFullName()
                                                            .charAt(0))
                                            .toUpperCase()
                                            : "T")
                            .totalJobs(techJobs.size())
                            .completedJobs(completed)
                            .activeJobs(active)
                            .completionRate(
                                    Math.round(
                                            rate * 10.0) / 10.0)
                            .avgDurationHours(2.3)
                            .satisfactionScore(4.5)
                            .onTimeRate(87.5)
                            .performanceLevel(perfLevel)
                            .isOnline(isOnline)
                            .currentStatus(
                                    active > 0
                                            ? "ON_JOB"
                                            : "AVAILABLE")
                            .build();
                })
                .sorted((a, b) ->
                        Double.compare(
                                b.getCompletionRate(),
                                a.getCompletionRate()))
                .collect(Collectors.toList());
    }

    // ─── Recent Activity ──────────────────────────────────

    public List<DashboardDTO.ActivityItemDTO>
    getRecentActivity(int limit) {
        log.info(
                "Generating recent activity "
                        + "feed limit={}",
                limit);

        List<DashboardDTO.ActivityItemDTO>
                activities = new ArrayList<>();

        // Faults — recent ones
        List<Fault> recentFaults =
                faultRepository.findAll()
                        .stream()
                        .filter(f ->
                                f.getCreatedAt() != null)
                        .sorted((a, b) ->
                                b.getCreatedAt()
                                        .compareTo(
                                                a.getCreatedAt()))
                        .limit(10)
                        .collect(Collectors.toList());

        for (Fault fault : recentFaults) {
            activities.add(
                    DashboardDTO.ActivityItemDTO
                            .builder()
                            .id(fault.getId())
                            .type("FAULT_CREATED")
                            .icon("🔧")
                            .title("New Fault Reported")
                            .description(
                                    "Fault #"
                                            + fault.getId()
                                            + " reported"
                                            + (fault.getCategory()
                                            != null
                                            ? " — "
                                            + fault.getCategory()
                                            : ""))
                            .actorName(
                                    fault.getCustomerName() != null
                                            ? fault.getCustomerName()
                                            : "Client")
                            .actorRole("CLIENT")
                            .entityId(
                                    fault.getId()
                                            .toString())
                            .entityType("FAULT")
                            .timestamp(
                                    fault.getCreatedAt())
                            .timeAgo(getTimeAgo(
                                    fault.getCreatedAt()))
                            .color("#003087")
                            .build());
        }

        // Payments — recent submissions
        List<Payment> recentPayments =
                paymentRepository.findAll()
                        .stream()
                        .filter(p ->
                                p.getCreatedAt() != null)
                        .sorted((a, b) ->
                                b.getCreatedAt()
                                        .compareTo(
                                                a.getCreatedAt()))
                        .limit(5)
                        .collect(Collectors.toList());

        for (Payment payment : recentPayments) {
            String statusIcon =
                    "FINAL".equals(
                            payment.getStatus() != null
                                    ? payment.getStatus()
                                    .name()
                                    : "")
                            ? "✅" : "⏳";
            String statusColor =
                    "FINAL".equals(
                            payment.getStatus() != null
                                    ? payment.getStatus()
                                    .name()
                                    : "")
                            ? "#4CAF50" : "#FF9800";

            activities.add(
                    DashboardDTO.ActivityItemDTO
                            .builder()
                            .id(payment.getId())
                            .type("PAYMENT_"
                                    + (payment.getStatus()
                                    != null
                                    ? payment.getStatus()
                                    .name()
                                    : "SUBMITTED"))
                            .icon(statusIcon)
                            .title("Payment "
                                    + (payment.getStatus()
                                    != null
                                    ? payment.getStatus()
                                    .name()
                                    : "Submitted"))
                            .description(
                                    "Payment #"
                                            + payment.getId()
                                            + " — LKR "
                                            + String.format(
                                            "%.0f",
                                            payment
                                                    .getTotalAmount()
                                                    != null
                                                    ? payment
                                                    .getTotalAmount()
                                                    .doubleValue()
                                                    : 0))
                            .actorName("Team Lead")
                            .actorRole("TEAM_LEAD")
                            .entityId(
                                    payment.getId()
                                            .toString())
                            .entityType("PAYMENT")
                            .timestamp(
                                    payment.getCreatedAt())
                            .timeAgo(getTimeAgo(
                                    payment.getCreatedAt()))
                            .color(statusColor)
                            .build());
        }

        // Sort all by timestamp descending
        activities.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp()
                    .compareTo(a.getTimestamp());
        });

        return activities.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ─── Geographic Data ──────────────────────────────────

    public DashboardDTO.GeographicDataDTO
    getGeographicData() {
        log.info("Generating geographic data");

        List<Fault> allFaults =
                faultRepository.findAll();
        List<TechnicianLocation>
                activeLocations =
                locationRepository
                        .findAllByIsActiveTrue();

        // Fault heat map points
        List<DashboardDTO.GeoPointDTO>
                heatMapPoints = allFaults.stream()
                .filter(f ->
                        f.getLatitude() != null
                                && f.getLongitude()
                                != null)
                .map(f ->
                        DashboardDTO.GeoPointDTO
                                .builder()
                                .latitude(
                                        f.getLatitude())
                                .longitude(
                                        f.getLongitude())
                                .label("Fault #"
                                        + f.getId())
                                .type("FAULT")
                                .count(1)
                                .intensity(
                                        getIntensity(
                                                f.getStatus()
                                                        != null
                                                        ? f.getStatus()
                                                        .name()
                                                        : ""))
                                .status(
                                        f.getStatus()
                                                != null
                                                ? f.getStatus()
                                                .name()
                                                : "UNKNOWN")
                                .faultId(
                                        f.getId()
                                                .toString())
                                .build())
                .collect(Collectors.toList());

        // Technician location points
        List<DashboardDTO.GeoPointDTO>
                techPoints = activeLocations.stream()
                .map(loc ->
                        DashboardDTO.GeoPointDTO
                                .builder()
                                .latitude(
                                        loc.getLatitude())
                                .longitude(
                                        loc.getLongitude())
                                .label(loc.getUser()
                                        .getFullName())
                                .type("TECHNICIAN")
                                .count(1)
                                .intensity(1.0)
                                .status(
                                        loc.getTechnicianStatus()
                                                != null
                                                ? loc.getTechnicianStatus()
                                                .name()
                                                : "AVAILABLE")
                                .technicianName(
                                        loc.getUser()
                                                .getFullName())
                                .build())
                .collect(Collectors.toList());

        // Sri Lanka regions
        List<DashboardDTO.GeoBoundaryDTO>
                regions = buildSriLankaRegions(
                allFaults);

        return DashboardDTO.GeographicDataDTO
                .builder()
                .faultHeatMap(heatMapPoints)
                .technicianLocations(techPoints)
                .regions(regions)
                .build();
    }

    // ─── Private Helpers ──────────────────────────────────

    private List<DashboardDTO.GeoBoundaryDTO>
    buildSriLankaRegions(List<Fault> faults) {
        // Sri Lanka main regions with
        // approximate boundaries
        Map<String, double[][]> regionBounds =
                new LinkedHashMap<>();
        regionBounds.put("Colombo",
                new double[][]{
                        {6.85, 79.82},
                        {6.97, 79.82},
                        {6.97, 80.01},
                        {6.85, 80.01}});
        regionBounds.put("Kandy",
                new double[][]{
                        {7.20, 80.55},
                        {7.35, 80.55},
                        {7.35, 80.70},
                        {7.20, 80.70}});
        regionBounds.put("Galle",
                new double[][]{
                        {6.00, 80.17},
                        {6.15, 80.17},
                        {6.15, 80.32},
                        {6.00, 80.32}});
        regionBounds.put("Jaffna",
                new double[][]{
                        {9.60, 80.00},
                        {9.75, 80.00},
                        {9.75, 80.15},
                        {9.60, 80.15}});

        List<DashboardDTO.GeoBoundaryDTO>
                regions = new ArrayList<>();

        for (Map.Entry<String, double[][]>
                entry : regionBounds.entrySet()) {
            long regionFaults =
                    faults.stream()
                            .filter(f ->
                                    f.getLocationAddress() != null
                                            && f.getLocationAddress()
                                            .contains(
                                                    entry.getKey()))
                            .count();

            String risk =
                    regionFaults > 20 ? "HIGH"
                            : regionFaults > 10
                            ? "MEDIUM"
                            : "LOW";

            List<double[]> coords = Arrays
                    .asList(entry.getValue());

            regions.add(
                    DashboardDTO.GeoBoundaryDTO
                            .builder()
                            .regionName(entry.getKey())
                            .faultCount(regionFaults)
                            .density(regionFaults / 100.0)
                            .riskLevel(risk)
                            .coordinates(coords)
                            .build());
        }

        return regions;
    }

    private String getTimeAgo(
            LocalDateTime dateTime) {
        if (dateTime == null) return "Unknown";
        long seconds = ChronoUnit.SECONDS.between(
                dateTime, LocalDateTime.now());
        if (seconds < 60)
            return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60)
            return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24)
            return hours + "h ago";
        long days = hours / 24;
        if (days < 7)
            return days + "d ago";
        return dateTime.format(DATE_FMT);
    }

    private double getIntensity(String status) {
        switch (status) {
            case "OPEN": return 1.0;
            case "IN_PROGRESS": return 0.7;
            case "COMPLETED": return 0.3;
            default: return 0.5;
        }
    }

    private String getCategoryColor(
            String category) {
        switch (category.toUpperCase()) {
            case "BROADBAND": return "#003087";
            case "FIBER": return "#0099CC";
            case "TELEPHONE": return "#FF6600";
            case "TELEVISION": return "#4CAF50";
            default: return "#9E9E9E";
        }
    }

    private String getPriorityColor(
            String priority) {
        switch (priority.toUpperCase()) {
            case "HIGH": return "#F44336";
            case "MEDIUM": return "#FF9800";
            case "LOW": return "#4CAF50";
            default: return "#9E9E9E";
        }
    }
}