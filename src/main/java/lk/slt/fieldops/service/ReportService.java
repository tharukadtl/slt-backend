package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.KpiDTO;
import lk.slt.fieldops.dto.ReportRequestDTO;
import lk.slt.fieldops.dto.ReportResponseDTO;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import org.apache.poi.ss.usermodel.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.io.font.constants.StandardFonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final FaultRepository faultRepository;
    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentApprovalRepository
            paymentApprovalRepository;
    private final MaterialRepository materialRepository;
    private final MaterialUsageRepository materialUsageRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final CheckInOutRepository checkInOutRepository;
    private final FaultHistoryRepository faultHistoryRepository;
    private final UserAuditLogRepository userAuditLogRepository;
    private final KpiCalculationService kpiCalculationService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.base-url:http://localhost:5000}")
    private String aiBaseUrl;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm:ss");

    // ─── Date Range Helper ────────────────────────────────

    private LocalDate[] resolveDateRange(
            ReportRequestDTO req) {
        LocalDate start;
        LocalDate end = LocalDate.now();

        if (req.getPeriod() != null) {
            switch (req.getPeriod()) {
                case ReportRequestDTO.PERIOD_TODAY:
                    return new LocalDate[]{
                            LocalDate.now(), end};
                case ReportRequestDTO.PERIOD_THIS_WEEK:
                    start = LocalDate.now().minusDays(7);
                    return new LocalDate[]{start, end};
                case ReportRequestDTO.PERIOD_THIS_MONTH:
                    start = LocalDate.now()
                            .withDayOfMonth(1);
                    return new LocalDate[]{start, end};
                case ReportRequestDTO.PERIOD_LAST_MONTH:
                    start = LocalDate.now().minusMonths(1)
                            .withDayOfMonth(1);
                    end = LocalDate.now().minusMonths(1)
                            .withDayOfMonth(
                                    LocalDate.now()
                                            .minusMonths(1)
                                            .lengthOfMonth());
                    return new LocalDate[]{start, end};
                default:
                    break;
            }
        }

        start = req.getStartDate() != null
                ? req.getStartDate()
                : LocalDate.now().minusDays(30);
        end = req.getEndDate() != null
                ? req.getEndDate()
                : LocalDate.now();
        return new LocalDate[]{start, end};
    }

    // ─── Fault Trends ─────────────────────────────────────

    public ReportResponseDTO getFaultTrends(
            String period,
            LocalDate startDate,
            LocalDate endDate,
            Long callerOpmcId) {
        log.info("Generating fault trends report: "
                + "period={}", period);

        ReportRequestDTO req = ReportRequestDTO.builder()
                .period(period)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        LocalDate[] range = resolveDateRange(req);

        // Stage F #2 — null callerOpmcId means unscoped (Super Admin).
        List<Fault> faults = faultRepository.findAll().stream()
                .filter(f -> callerOpmcId == null || callerOpmcId.equals(f.getOpmcId()))
                .collect(Collectors.toList());

        // Group faults by date
        Map<String, List<Fault>> faultsByDate =
                faults.stream()
                        .filter(f -> {
                            if (f.getCreatedAt() == null)
                                return false;
                            LocalDate d = f.getCreatedAt()
                                    .toLocalDate();
                            return !d.isBefore(range[0])
                                    && !d.isAfter(range[1]);
                        })
                        .collect(Collectors.groupingBy(
                                f -> f.getCreatedAt()
                                        .toLocalDate()
                                        .format(DATE_FMT)));

        List<ReportResponseDTO.FaultTrendDTO> trends =
                new ArrayList<>();
        LocalDate current = range[0];
        while (!current.isAfter(range[1])) {
            String dateKey = current.format(DATE_FMT);
            List<Fault> dayFaults = faultsByDate
                    .getOrDefault(dateKey,
                            Collections.emptyList());

            long completed = dayFaults.stream()
                    .filter(f -> "COMPLETED".equals(
                            f.getStatus() != null
                                    ? f.getStatus()
                                    .name() : ""))
                    .count();
            // "Open" = not yet picked up (REPORTED/ASSIGNED/HOLD) — the Fault.FaultStatus
            // enum has no OPEN value, so this used to always be zero.
            long open = dayFaults.stream()
                    .filter(f -> f.getStatus() == Fault.FaultStatus.REPORTED
                            || f.getStatus() == Fault.FaultStatus.ASSIGNED
                            || f.getStatus() == Fault.FaultStatus.HOLD)
                    .count();
            long inProgress = dayFaults.stream()
                    .filter(f -> "IN_PROGRESS".equals(
                            f.getStatus() != null
                                    ? f.getStatus()
                                    .name() : ""))
                    .count();
            long cancelled = dayFaults.stream()
                    .filter(f -> "CANCELLED".equals(
                            f.getStatus() != null
                                    ? f.getStatus()
                                    .name() : ""))
                    .count();

            double avgResolution = dayFaults.stream()
                    .filter(f -> f.getStatus() == Fault.FaultStatus.COMPLETED
                            && f.getCompletedAt() != null
                            && f.getReportedAt() != null)
                    .mapToDouble(f -> java.time.Duration.between(
                            f.getReportedAt(), f.getCompletedAt()).toMinutes() / 60.0)
                    .average().orElse(0);

            trends.add(
                    ReportResponseDTO.FaultTrendDTO
                            .builder()
                            .date(dateKey)
                            .totalFaults(dayFaults.size())
                            .openFaults(open)
                            .inProgressFaults(inProgress)
                            .completedFaults(completed)
                            .cancelledFaults(cancelled)
                            .avgResolutionTimeHours(Math.round(avgResolution * 10.0) / 10.0)
                            .build());
            current = current.plusDays(1);
        }

        // Summary stats — sum the already date-range-filtered trend buckets, not the
        // unfiltered `faults` list (which would always show the grand total regardless of period).
        long totalFaults = trends.stream().mapToLong(ReportResponseDTO.FaultTrendDTO::getTotalFaults).sum();
        List<ReportResponseDTO.SummaryStatsDTO> summary =
                List.of(
                        ReportResponseDTO.SummaryStatsDTO
                                .builder()
                                .label("Total Faults")
                                .value(
                                        String.valueOf(
                                                totalFaults))
                                .trend("UP")
                                .build(),
                        ReportResponseDTO.SummaryStatsDTO
                                .builder()
                                .label("Avg Per Day")
                                .value(String.format("%.1f",
                                        trends.stream()
                                                .mapToLong(
                                                        ReportResponseDTO
                                                                .FaultTrendDTO
                                                                ::getTotalFaults)
                                                .average()
                                                .orElse(0)))
                                .trend("STABLE")
                                .build());

        return ReportResponseDTO.builder()
                .reportType(
                        ReportRequestDTO.TYPE_FAULT_TRENDS)
                .reportTitle("Fault Trends Report")
                .period(period)
                .startDate(range[0].format(DATE_FMT))
                .endDate(range[1].format(DATE_FMT))
                .generatedAt(LocalDateTime.now())
                .totalRecords(trends.size())
                .summaryStats(summary)
                .data(trends)
                .build();
    }

    // ─── Technician Performance ───────────────────────────

    public ReportResponseDTO getTechnicianPerformance(
            String period,
            LocalDate startDate,
            LocalDate endDate,
            String technicianId,
            Long callerOpmcId) {
        log.info("Generating technician performance report");

        ReportRequestDTO dateReq = ReportRequestDTO.builder()
                .period(period).startDate(startDate).endDate(endDate).build();
        LocalDate[] range = resolveDateRange(dateReq);

        // ANA-011 — technicianId and the date range used to be silently discarded (this method
        // never called resolveDateRange, and the controller never passed technicianId through).
        // Stage F #2 — callerOpmcId scopes to the caller's own OPMC; null (Super Admin) is unscoped.
        List<User> technicians = userRepository.findAll()
                .stream()
                .filter(u -> u.getRole() != null
                        && (u.getRole().name()
                        .equals("TECHNICIAN")
                        || u.getRole().name()
                        .equals("TEAM_LEAD")))
                .filter(u -> technicianId == null || technicianId.isBlank()
                        || technicianId.equals(String.valueOf(u.getId())))
                .filter(u -> callerOpmcId == null || callerOpmcId.equals(u.getOpmcId()))
                .collect(Collectors.toList());

        List<ReportResponseDTO.TechnicianPerformanceDTO>
                performances = new ArrayList<>();

        List<Fault> allFaults = faultRepository.findAll();
        Map<Long, Fault> faultsById = allFaults.stream()
                .filter(f -> f.getId() != null)
                .collect(Collectors.toMap(Fault::getId, f -> f, (a, b) -> a));

        for (User tech : technicians) {
            List<Job> jobs = jobRepository.findAll()
                    .stream()
                    .filter(j -> tech.getId().equals(j.getTechnicianId()))
                    .filter(j -> j.getCreatedAt() != null
                            && !j.getCreatedAt().toLocalDate().isBefore(range[0])
                            && !j.getCreatedAt().toLocalDate().isAfter(range[1]))
                    .collect(Collectors.toList());

            long completed = jobs.stream()
                    .filter(j -> "COMPLETED".equals(
                            j.getStatus() != null
                                    ? j.getStatus().name()
                                    : ""))
                    .count();
            long inProgress = jobs.stream()
                    .filter(j -> j.getStatus() == Job.JobStatus.IN_PROGRESS
                            || j.getStatus() == Job.JobStatus.TRAVELLING
                            || j.getStatus() == Job.JobStatus.ACCEPTED)
                    .count();
            long cancelled = jobs.stream()
                    .filter(j -> j.getStatus() == Job.JobStatus.CANCELLED
                            || j.getStatus() == Job.JobStatus.REJECTED)
                    .count();
            long total = jobs.size();
            double completionRate = total > 0
                    ? (completed * 100.0 / total) : 0;

            List<Job> completedJobs = jobs.stream()
                    .filter(j -> j.getStatus() == Job.JobStatus.COMPLETED
                            && j.getStartedAt() != null && j.getCompletedAt() != null)
                    .collect(Collectors.toList());
            double avgDuration = completedJobs.stream()
                    .mapToDouble(j -> java.time.Duration.between(
                            j.getStartedAt(), j.getCompletedAt()).toMinutes() / 60.0)
                    .average().orElse(0);

            List<Job> acceptedJobs = jobs.stream()
                    .filter(j -> j.getAcceptedAt() != null && j.getCreatedAt() != null)
                    .collect(Collectors.toList());
            double avgResponseMinutes = acceptedJobs.stream()
                    .mapToDouble(j -> java.time.Duration.between(
                            j.getCreatedAt(), j.getAcceptedAt()).toMinutes())
                    .average().orElse(0);

            List<Integer> ratings = jobs.stream()
                    .map(j -> {
                        Fault f = faultsById.get(j.getFaultId());
                        return f != null ? f.getCustomerRating() : null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            double satisfaction = ratings.stream()
                    .mapToInt(Integer::intValue)
                    .average().orElse(0);

            long onTimeCompletions = completedJobs.stream()
                    .filter(j -> {
                        Fault f = faultsById.get(j.getFaultId());
                        return f == null || f.getDueDate() == null
                                || !j.getCompletedAt().isAfter(f.getDueDate());
                    })
                    .count();
            double onTimeRate = !completedJobs.isEmpty()
                    ? (onTimeCompletions * 100.0 / completedJobs.size()) : 0;

            long workingDays = jobs.stream()
                    .filter(j -> j.getCreatedAt() != null)
                    .map(j -> j.getCreatedAt().toLocalDate())
                    .distinct()
                    .count();
            double avgJobsPerDay = workingDays > 0
                    ? (total * 1.0 / workingDays) : 0;

            performances.add(
                    ReportResponseDTO
                            .TechnicianPerformanceDTO
                            .builder()
                            .technicianId(
                                    tech.getId().toString())
                            .technicianName(
                                    tech.getFullName())
                            .phone(tech.getPhone())
                            .opmcName(tech.getOpmcName())
                            .totalJobsAssigned(total)
                            .jobsCompleted(completed)
                            .jobsInProgress(inProgress)
                            .jobsCancelled(cancelled)
                            .completionRate(Math.round(completionRate * 10.0) / 10.0)
                            .avgJobDurationHours(Math.round(avgDuration * 10.0) / 10.0)
                            .avgResponseTimeMinutes(Math.round(avgResponseMinutes * 10.0) / 10.0)
                            .customerSatisfactionScore(Math.round(satisfaction * 10.0) / 10.0)
                            .onTimeCompletions(onTimeCompletions)
                            .onTimeCompletionRate(Math.round(onTimeRate * 10.0) / 10.0)
                            .totalWorkingDays(workingDays)
                            .avgJobsPerDay(Math.round(avgJobsPerDay * 10.0) / 10.0)
                            .build());
        }

        return ReportResponseDTO.builder()
                .reportType(
                        ReportRequestDTO
                                .TYPE_TECHNICIAN_PERFORMANCE)
                .reportTitle(
                        "Technician Performance Report")
                .period(period)
                .generatedAt(LocalDateTime.now())
                .totalRecords(performances.size())
                .data(performances)
                .build();
    }

    // ─── Financial Summary ────────────────────────────────

    public ReportResponseDTO getFinancialSummary(
            String period,
            LocalDate startDate,
            LocalDate endDate,
            Long callerOpmcId) {
        log.info("Generating financial summary report");

        // Stage F #2 (resolved: in scope) — an Admin sees only their own OPMC's payments;
        // opmcBreakdown below then naturally reduces to a single row for a scoped caller, which
        // is the intended behavior — Super Admin (callerOpmcId == null) still gets the full
        // cross-OPMC breakdown this report was built around.
        List<Payment> payments =
                paymentRepository.findAll().stream()
                        .filter(p -> callerOpmcId == null || callerOpmcId.equals(p.getOpmcId()))
                        .collect(Collectors.toList());

        double totalFOC = payments.stream()
                .mapToDouble(p ->
                        p.getMaterialsFocTotal() != null
                                ? p.getMaterialsFocTotal()
                                .doubleValue()
                                : 0)
                .sum();
        double totalChargeable = payments.stream()
                .mapToDouble(p ->
                        p.getMaterialsChargeableTotal() != null
                                ? p.getMaterialsChargeableTotal()
                                .doubleValue()
                                : 0)
                .sum();
        double totalRevenue =
                totalFOC + totalChargeable;

        long approved = payments.stream()
                .filter(p -> p.getStatus() != null
                        && "FINAL".equals(
                        p.getStatus().name()))
                .count();
        long rejected = payments.stream()
                .filter(p -> p.getStatus() != null
                        && "NOT_APPROVED".equals(
                        p.getStatus().name()))
                .count();
        long pending = payments.stream()
                .filter(p -> p.getStatus() != null
                        && "DRAFT".equals(
                        p.getStatus().name()))
                .count();

        double approvalRate = payments.size() > 0
                ? (approved * 100.0 / payments.size())
                : 0;

        Map<Long, Fault> faultsById = faultRepository.findAll().stream()
                .filter(f -> f.getId() != null)
                .collect(Collectors.toMap(Fault::getId, f -> f, (a, b) -> a));

        Map<String, List<Payment>> byCategory = payments.stream()
                .collect(Collectors.groupingBy(p -> {
                    Fault f = p.getFaultId() != null ? faultsById.get(p.getFaultId()) : null;
                    return f != null && f.getCategory() != null ? f.getCategory().name() : "UNKNOWN";
                }));
        List<ReportResponseDTO.CategoryBreakdownDTO> categoryBreakdown = byCategory.entrySet().stream()
                .map(e -> {
                    double amt = e.getValue().stream()
                            .mapToDouble(p -> p.getTotalAmount() != null ? p.getTotalAmount().doubleValue() : 0)
                            .sum();
                    return ReportResponseDTO.CategoryBreakdownDTO.builder()
                            .category(e.getKey())
                            .count(e.getValue().size())
                            .amount(amt)
                            .percentage(totalRevenue > 0 ? Math.round(amt * 1000.0 / totalRevenue) / 10.0 : 0)
                            .build();
                })
                .collect(Collectors.toList());

        Map<String, List<Payment>> byTechnician = payments.stream()
                .collect(Collectors.groupingBy(p -> p.getTechnicianName() != null ? p.getTechnicianName() : "Unassigned"));
        List<ReportResponseDTO.CategoryBreakdownDTO> technicianBreakdown = byTechnician.entrySet().stream()
                .map(e -> {
                    double amt = e.getValue().stream()
                            .mapToDouble(p -> p.getTotalAmount() != null ? p.getTotalAmount().doubleValue() : 0)
                            .sum();
                    return ReportResponseDTO.CategoryBreakdownDTO.builder()
                            .category(e.getKey())
                            .count(e.getValue().size())
                            .amount(amt)
                            .percentage(totalRevenue > 0 ? Math.round(amt * 1000.0 / totalRevenue) / 10.0 : 0)
                            .build();
                })
                .collect(Collectors.toList());

        Map<Long, List<Payment>> byOpmc = payments.stream()
                .collect(Collectors.groupingBy(Payment::getOpmcId));
        List<ReportResponseDTO.CategoryBreakdownDTO> opmcBreakdown = byOpmc.entrySet().stream()
                .map(e -> {
                    double amt = e.getValue().stream()
                            .mapToDouble(p -> p.getTotalAmount() != null ? p.getTotalAmount().doubleValue() : 0)
                            .sum();
                    return ReportResponseDTO.CategoryBreakdownDTO.builder()
                            .category("OPMC " + e.getKey())
                            .count(e.getValue().size())
                            .amount(amt)
                            .percentage(totalRevenue > 0 ? Math.round(amt * 1000.0 / totalRevenue) / 10.0 : 0)
                            .build();
                })
                .collect(Collectors.toList());

        List<ReportResponseDTO.RejectedPaymentDTO> rejectedPayments = payments.stream()
                .filter(p -> p.getStatus() == Payment.PaymentStatus.NOT_APPROVED)
                .map(p -> ReportResponseDTO.RejectedPaymentDTO.builder()
                        .paymentNumber(p.getPaymentNumber())
                        .technicianName(p.getTechnicianName())
                        .amount(p.getTotalAmount() != null ? p.getTotalAmount().doubleValue() : 0)
                        .reason(p.getRejectionReason())
                        .rejectedAt(p.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());

        double totalMaterialCost = totalFOC + totalChargeable;
        double totalLaborCost = payments.stream()
                .mapToDouble(p -> p.getLabourCharge() != null ? p.getLabourCharge().doubleValue() : 0)
                .sum();

        ReportResponseDTO.FinancialSummaryDTO summary =
                ReportResponseDTO.FinancialSummaryDTO
                        .builder()
                        .period(period)
                        .totalRevenue(totalRevenue)
                        .totalFOCAmount(totalFOC)
                        .totalChargeableAmount(
                                totalChargeable)
                        .totalMaterialCost(totalMaterialCost)
                        .totalLaborCost(totalLaborCost)
                        .totalPaymentsSubmitted(
                                payments.size())
                        .totalPaymentsApproved(approved)
                        .totalPaymentsRejected(rejected)
                        .totalPaymentsPending(pending)
                        .approvalRate(approvalRate)
                        .categoryBreakdown(categoryBreakdown)
                        .technicianBreakdown(technicianBreakdown)
                        .opmcBreakdown(opmcBreakdown)
                        .rejectedPayments(rejectedPayments)
                        .build();

        List<ReportResponseDTO.SummaryStatsDTO> stats =
                List.of(
                        ReportResponseDTO.SummaryStatsDTO
                                .builder()
                                .label("Total Revenue")
                                .value(String.format(
                                        "LKR %.2f",
                                        totalRevenue))
                                .trend("UP")
                                .build(),
                        ReportResponseDTO.SummaryStatsDTO
                                .builder()
                                .label("FOC Amount")
                                .value(String.format(
                                        "LKR %.2f", totalFOC))
                                .trend("STABLE")
                                .build(),
                        ReportResponseDTO.SummaryStatsDTO
                                .builder()
                                .label("Approval Rate")
                                .value(String.format(
                                        "%.1f%%",
                                        approvalRate))
                                .trend("UP")
                                .build());

        return ReportResponseDTO.builder()
                .reportType(
                        ReportRequestDTO
                                .TYPE_FINANCIAL_SUMMARY)
                .reportTitle("Financial Summary Report")
                .period(period)
                .generatedAt(LocalDateTime.now())
                .totalRecords(payments.size())
                .summaryStats(stats)
                .data(summary)
                .build();
    }

    // ─── Customer Satisfaction ────────────────────────────

    public ReportResponseDTO getCustomerSatisfaction(
            String period,
            LocalDate startDate,
            LocalDate endDate,
            Long callerOpmcId) {
        log.info(
                "Generating customer satisfaction report");

        ReportRequestDTO dateReq = ReportRequestDTO.builder()
                .period(period).startDate(startDate).endDate(endDate).build();
        LocalDate[] range = resolveDateRange(dateReq);

        // Stage F #2 — null callerOpmcId means unscoped (Super Admin).
        List<Fault> rated = faultRepository.findAll().stream()
                .filter(f -> f.getCustomerRating() != null
                        && f.getCompletedAt() != null
                        && !f.getCompletedAt().toLocalDate().isBefore(range[0])
                        && !f.getCompletedAt().toLocalDate().isAfter(range[1]))
                .filter(f -> callerOpmcId == null || callerOpmcId.equals(f.getOpmcId()))
                .collect(Collectors.toList());

        long r5 = rated.stream().filter(f -> f.getCustomerRating() == 5).count();
        long r4 = rated.stream().filter(f -> f.getCustomerRating() == 4).count();
        long r3 = rated.stream().filter(f -> f.getCustomerRating() == 3).count();
        long r2 = rated.stream().filter(f -> f.getCustomerRating() == 2).count();
        long r1 = rated.stream().filter(f -> f.getCustomerRating() == 1).count();

        double overallScore = rated.stream()
                .mapToInt(Fault::getCustomerRating)
                .average().orElse(0);

        double avgResolutionHours = rated.stream()
                .filter(f -> f.getReportedAt() != null)
                .mapToDouble(f -> java.time.Duration.between(f.getReportedAt(), f.getCompletedAt()).toMinutes() / 60.0)
                .average().orElse(0);

        long onTimeCount = rated.stream()
                .filter(f -> f.getDueDate() == null || !f.getCompletedAt().isAfter(f.getDueDate()))
                .count();
        double onTimeRate = !rated.isEmpty() ? (onTimeCount * 100.0 / rated.size()) : 0;

        Map<String, List<Fault>> byTech = rated.stream()
                .filter(f -> f.getAssignedTeamLeadName() != null)
                .collect(Collectors.groupingBy(Fault::getAssignedTeamLeadName));
        List<ReportResponseDTO.TechnicianSatisfactionDTO> technicianScores = byTech.entrySet().stream()
                .map(e -> ReportResponseDTO.TechnicianSatisfactionDTO.builder()
                        .technicianName(e.getKey())
                        .avgScore(Math.round(e.getValue().stream()
                                .mapToInt(Fault::getCustomerRating).average().orElse(0) * 10.0) / 10.0)
                        .totalRatings(e.getValue().size())
                        .build())
                .collect(Collectors.toList());

        ReportResponseDTO.CustomerSatisfactionDTO
                satisfaction =
                ReportResponseDTO.CustomerSatisfactionDTO
                        .builder()
                        .period(period)
                        .overallScore(Math.round(overallScore * 10.0) / 10.0)
                        .totalResponses(rated.size())
                        .rating5Count(r5)
                        .rating4Count(r4)
                        .rating3Count(r3)
                        .rating2Count(r2)
                        .rating1Count(r1)
                        .avgResolutionTimeHours(Math.round(avgResolutionHours * 10.0) / 10.0)
                        .onTimeDeliveryRate(Math.round(onTimeRate * 10.0) / 10.0)
                        .technicianScores(technicianScores)
                        .build();

        return ReportResponseDTO.builder()
                .reportType(
                        ReportRequestDTO
                                .TYPE_CUSTOMER_SATISFACTION)
                .reportTitle(
                        "Customer Satisfaction Report")
                .period(period)
                .generatedAt(LocalDateTime.now())
                .totalRecords(rated.size())
                .data(satisfaction)
                .build();
    }

    // ─── REP-01: Daily Fault Summary ──────────────────────

    public ReportResponseDTO getDailyFaultSummary(ReportRequestDTO req) {
        log.info("Generating daily fault summary report (REP-01)");
        LocalDate[] range = resolveDateRange(req);
        Long callerOpmcId = req.getCallerOpmcId();

        // Built early (Stage F #2) so both the Fault and Job filters below can use it — Fault
        // carries opmcId directly, Job doesn't, so Job is scoped via technicianId -> User.opmcId.
        Map<Long, User> usersById = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<Fault> faults = faultRepository.findAll().stream()
                .filter(f -> f.getReportedAt() != null
                        && !f.getReportedAt().toLocalDate().isBefore(range[0])
                        && !f.getReportedAt().toLocalDate().isAfter(range[1]))
                .filter(f -> callerOpmcId == null || callerOpmcId.equals(f.getOpmcId()))
                .collect(Collectors.toList());

        long totalFaults = faults.size();

        Map<String, Long> byCategoryCount = faults.stream()
                .collect(Collectors.groupingBy(
                        f -> f.getCategory() != null ? f.getCategory().name() : "OTHER",
                        Collectors.counting()));
        List<ReportResponseDTO.CategoryBreakdownDTO> byCategory = byCategoryCount.entrySet().stream()
                .map(e -> ReportResponseDTO.CategoryBreakdownDTO.builder()
                        .category(e.getKey())
                        .count(e.getValue())
                        .percentage(totalFaults > 0 ? Math.round(e.getValue() * 1000.0 / totalFaults) / 10.0 : 0)
                        .build())
                .collect(Collectors.toList());

        Map<String, Long> byStatusCount = faults.stream()
                .collect(Collectors.groupingBy(
                        f -> f.getStatus() != null ? f.getStatus().name() : "UNKNOWN",
                        Collectors.counting()));
        List<ReportResponseDTO.CategoryBreakdownDTO> byStatus = byStatusCount.entrySet().stream()
                .map(e -> ReportResponseDTO.CategoryBreakdownDTO.builder()
                        .category(e.getKey())
                        .count(e.getValue())
                        .percentage(totalFaults > 0 ? Math.round(e.getValue() * 1000.0 / totalFaults) / 10.0 : 0)
                        .build())
                .collect(Collectors.toList());

        double avgResolutionTimeHours = faults.stream()
                .filter(f -> f.getStatus() == Fault.FaultStatus.COMPLETED
                        && f.getCompletedAt() != null && f.getReportedAt() != null)
                .mapToDouble(f -> java.time.Duration.between(f.getReportedAt(), f.getCompletedAt()).toMinutes() / 60.0)
                .average().orElse(0);

        List<Job> jobs = jobRepository.findAll().stream()
                .filter(j -> j.getCreatedAt() != null
                        && !j.getCreatedAt().toLocalDate().isBefore(range[0])
                        && !j.getCreatedAt().toLocalDate().isAfter(range[1])
                        && j.getTechnicianId() != null)
                .filter(j -> callerOpmcId == null
                        || (usersById.get(j.getTechnicianId()) != null
                            && callerOpmcId.equals(usersById.get(j.getTechnicianId()).getOpmcId())))
                .collect(Collectors.toList());
        Map<Long, List<Job>> jobsByTech = jobs.stream()
                .collect(Collectors.groupingBy(Job::getTechnicianId));
        List<ReportResponseDTO.WorkloadDTO> technicianWorkload = jobsByTech.entrySet().stream()
                .map(e -> {
                    User u = usersById.get(e.getKey());
                    return ReportResponseDTO.WorkloadDTO.builder()
                            .technicianId(e.getKey().toString())
                            .technicianName(u != null ? u.getFullName() : "Unknown")
                            .assignedFaults(e.getValue().size())
                            .build();
                })
                .sorted((a, b) -> Long.compare(b.getAssignedFaults(), a.getAssignedFaults()))
                .collect(Collectors.toList());

        Map<String, Long> byRegionCount = faults.stream()
                .collect(Collectors.groupingBy(
                        f -> f.getLocationDistrict() != null ? f.getLocationDistrict() : "Unknown",
                        Collectors.counting()));
        List<ReportResponseDTO.GeoDistributionDTO> geoDistribution = byRegionCount.entrySet().stream()
                .map(e -> ReportResponseDTO.GeoDistributionDTO.builder()
                        .region(e.getKey())
                        .faultCount(e.getValue())
                        .build())
                .sorted((a, b) -> Long.compare(b.getFaultCount(), a.getFaultCount()))
                .collect(Collectors.toList());

        ReportResponseDTO.DailyFaultSummaryDTO summary = ReportResponseDTO.DailyFaultSummaryDTO.builder()
                .totalFaults(totalFaults)
                .byCategory(byCategory)
                .byStatus(byStatus)
                .avgResolutionTimeHours(Math.round(avgResolutionTimeHours * 10.0) / 10.0)
                .technicianWorkload(technicianWorkload)
                .geographicDistribution(geoDistribution)
                .build();

        return ReportResponseDTO.builder()
                .reportType(ReportRequestDTO.TYPE_DAILY_FAULT_SUMMARY)
                .reportTitle("Daily Fault Summary Report")
                .period(req.getPeriod())
                .startDate(range[0].format(DATE_FMT))
                .endDate(range[1].format(DATE_FMT))
                .generatedAt(LocalDateTime.now())
                .totalRecords(totalFaults)
                .data(summary)
                .build();
    }

    // ─── REP-03: Fault Aging ───────────────────────────────

    public ReportResponseDTO getFaultAging(ReportRequestDTO req) {
        log.info("Generating fault aging report (REP-03)");
        Long callerOpmcId = req.getCallerOpmcId();

        // Stage F #2 — null callerOpmcId means unscoped (Super Admin).
        List<Fault> openFaults = faultRepository.findAllOpen().stream()
                .filter(f -> callerOpmcId == null || callerOpmcId.equals(f.getOpmcId()))
                .collect(Collectors.toList());
        LocalDateTime now = LocalDateTime.now();

        Map<Long, User> usersById = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<ReportResponseDTO.FaultAgingItemDTO> items = openFaults.stream()
                .filter(f -> req.getPriority() == null || req.getPriority().isBlank()
                        || (f.getPriority() != null && f.getPriority().name().equalsIgnoreCase(req.getPriority())))
                .map(f -> {
                    double ageHours = f.getReportedAt() != null
                            ? java.time.Duration.between(f.getReportedAt(), now).toMinutes() / 60.0 : 0;
                    String bucket;
                    if (ageHours <= 24) bucket = "0-24H";
                    else if (ageHours <= 48) bucket = "24-48H";
                    else if (ageHours <= 72) bucket = "48-72H";
                    else bucket = "72H+";

                    User tech = f.getAssignedTeamLeadId() != null ? usersById.get(f.getAssignedTeamLeadId()) : null;

                    return ReportResponseDTO.FaultAgingItemDTO.builder()
                            .faultId(f.getId())
                            .faultNumber(f.getFaultNumber())
                            .category(f.getCategory() != null ? f.getCategory().name() : null)
                            .status(f.getStatus() != null ? f.getStatus().name() : null)
                            .priority(f.getPriority() != null ? f.getPriority().name() : null)
                            .ageHours(Math.round(ageHours * 10.0) / 10.0)
                            .ageBucket(bucket)
                            .assignedTechnician(tech != null ? tech.getFullName()
                                    : (f.getAssignedTeamLeadName() != null ? f.getAssignedTeamLeadName() : "Unassigned"))
                            .lastStatusUpdate(f.getUpdatedAt())
                            .escalated(Boolean.TRUE.equals(f.getIsEscalated()))
                            .build();
                })
                .sorted((a, b) -> Double.compare(b.getAgeHours(), a.getAgeHours()))
                .collect(Collectors.toList());

        return ReportResponseDTO.builder()
                .reportType(ReportRequestDTO.TYPE_FAULT_AGING)
                .reportTitle("Fault Aging Report")
                .period(req.getPeriod())
                .generatedAt(LocalDateTime.now())
                .totalRecords(items.size())
                .data(items)
                .build();
    }

    // ─── REP-05: Material Cost Report ──────────────────────

    public ReportResponseDTO getMaterialCostReport(ReportRequestDTO req) {
        log.info("Generating material cost report (REP-05)");
        LocalDate[] range = resolveDateRange(req);
        LocalDateTime startDt = range[0].atStartOfDay();
        LocalDateTime endDt = range[1].atTime(23, 59, 59);
        Long callerOpmcId = req.getCallerOpmcId();

        // Stage F #2 — MaterialUsage/StockTransaction only carry materialId, not opmcId, so
        // scoping goes through Material.opmcId via this map. null callerOpmcId is unscoped.
        Map<Long, Material> materialsById = materialRepository.findAll().stream()
                .collect(Collectors.toMap(Material::getId, m -> m, (a, b) -> a));

        List<MaterialUsage> usages = materialUsageRepository.findAll().stream()
                .filter(mu -> mu.getCreatedAt() != null
                        && !mu.getCreatedAt().isBefore(startDt) && !mu.getCreatedAt().isAfter(endDt))
                .filter(mu -> callerOpmcId == null
                        || (materialsById.get(mu.getMaterialId()) != null
                            && callerOpmcId.equals(materialsById.get(mu.getMaterialId()).getOpmcId())))
                .collect(Collectors.toList());

        double totalFocCost = usages.stream()
                .filter(mu -> mu.getChargeType() == MaterialUsage.ChargeType.FOC)
                .mapToDouble(mu -> mu.getTotalCost() != null ? mu.getTotalCost().doubleValue() : 0)
                .sum();
        double totalChargeableCost = usages.stream()
                .filter(mu -> mu.getChargeType() == MaterialUsage.ChargeType.CHARGEABLE)
                .mapToDouble(mu -> mu.getTotalCost() != null ? mu.getTotalCost().doubleValue() : 0)
                .sum();

        Map<Long, List<MaterialUsage>> byMaterial = usages.stream()
                .collect(Collectors.groupingBy(MaterialUsage::getMaterialId));
        List<ReportResponseDTO.MaterialUsageSummaryDTO> mostUsedMaterials = byMaterial.entrySet().stream()
                .map(e -> ReportResponseDTO.MaterialUsageSummaryDTO.builder()
                        .materialId(e.getKey())
                        .materialName(e.getValue().get(0).getMaterialName())
                        .totalQuantity(e.getValue().stream()
                                .mapToDouble(mu -> mu.getQuantityUsed() != null ? mu.getQuantityUsed().doubleValue() : 0)
                                .sum())
                        .totalCost(e.getValue().stream()
                                .mapToDouble(mu -> mu.getTotalCost() != null ? mu.getTotalCost().doubleValue() : 0)
                                .sum())
                        .usageCount(e.getValue().size())
                        .build())
                .sorted((a, b) -> Double.compare(b.getTotalQuantity(), a.getTotalQuantity()))
                .collect(Collectors.toList());

        List<StockTransaction> damageTx = stockTransactionRepository.findByType(StockTransaction.TransactionType.DAMAGE)
                .stream()
                .filter(st -> st.getCreatedAt() != null
                        && !st.getCreatedAt().isBefore(startDt) && !st.getCreatedAt().isAfter(endDt))
                .filter(st -> callerOpmcId == null
                        || (materialsById.get(st.getMaterialId()) != null
                            && callerOpmcId.equals(materialsById.get(st.getMaterialId()).getOpmcId())))
                .collect(Collectors.toList());
        Map<Long, List<StockTransaction>> damageByMaterial = damageTx.stream()
                .collect(Collectors.groupingBy(StockTransaction::getMaterialId));
        List<ReportResponseDTO.MaterialUsageSummaryDTO> wastageAndDamage = damageByMaterial.entrySet().stream()
                .map(e -> ReportResponseDTO.MaterialUsageSummaryDTO.builder()
                        .materialId(e.getKey())
                        .materialName(e.getValue().get(0).getMaterialName())
                        .totalQuantity(e.getValue().stream()
                                .mapToDouble(st -> st.getQuantity() != null ? st.getQuantity().doubleValue() : 0)
                                .sum())
                        .totalCost(e.getValue().stream()
                                .mapToDouble(st -> st.getTotalCost() != null ? st.getTotalCost().doubleValue() : 0)
                                .sum())
                        .usageCount(e.getValue().size())
                        .build())
                .collect(Collectors.toList());

        List<ReportResponseDTO.ReorderEstimateDTO> reorderEstimates = materialRepository.findLowStock().stream()
                .filter(m -> callerOpmcId == null || callerOpmcId.equals(m.getOpmcId()))
                .map(m -> ReportResponseDTO.ReorderEstimateDTO.builder()
                        .materialId(m.getId())
                        .materialName(m.getName())
                        .currentStock(m.getCurrentStock() != null ? m.getCurrentStock().doubleValue() : 0)
                        .minimumThreshold(m.getMinimumThreshold() != null ? m.getMinimumThreshold().doubleValue() : 0)
                        .reorderQuantity(m.getReorderQuantity() != null ? m.getReorderQuantity() : 0)
                        .estimatedCost((m.getReorderQuantity() != null ? m.getReorderQuantity() : 0)
                                * (m.getUnitPrice() != null ? m.getUnitPrice().doubleValue() : 0))
                        .build())
                .collect(Collectors.toList());

        ReportResponseDTO.MaterialCostReportDTO summary = ReportResponseDTO.MaterialCostReportDTO.builder()
                .totalFocCost(totalFocCost)
                .totalChargeableCost(totalChargeableCost)
                .mostUsedMaterials(mostUsedMaterials)
                .wastageAndDamage(wastageAndDamage)
                .reorderEstimates(reorderEstimates)
                .build();

        return ReportResponseDTO.builder()
                .reportType(ReportRequestDTO.TYPE_MATERIAL_COST)
                .reportTitle("Material Cost Report")
                .period(req.getPeriod())
                .startDate(range[0].format(DATE_FMT))
                .endDate(range[1].format(DATE_FMT))
                .generatedAt(LocalDateTime.now())
                .totalRecords(usages.size())
                .data(summary)
                .build();
    }

    // ─── REP-06: AI Forecast Report ────────────────────────

    @SuppressWarnings("unchecked")
    public ReportResponseDTO getAiForecastReport(ReportRequestDTO req) {
        log.info("Generating AI forecast report (REP-06)");
        int horizon = req.getHorizonDays() != null ? req.getHorizonDays() : 30;

        ReportResponseDTO.AiForecastReportDTO summary;
        try {
            String url = aiBaseUrl + "/api/ai/predictions?horizon=" + horizon;
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            Map<String, Object> data = resp != null && resp.get("data") instanceof Map
                    ? (Map<String, Object>) resp.get("data") : resp;

            List<ReportResponseDTO.ForecastPointDTO> historical = toForecastPoints(
                    (List<Map<String, Object>>) data.get("historical"), false);
            List<ReportResponseDTO.ForecastPointDTO> forecast = toForecastPoints(
                    (List<Map<String, Object>>) data.get("forecast"), true);

            Map<String, Object> metrics = (Map<String, Object>) data.get("metrics");
            double accuracy = metrics != null && metrics.get("accuracy") != null
                    ? ((Number) metrics.get("accuracy")).doubleValue() : 0;
            double mae = metrics != null && metrics.get("mae") != null
                    ? ((Number) metrics.get("mae")).doubleValue() : 0;
            double rmse = metrics != null && metrics.get("rmse") != null
                    ? ((Number) metrics.get("rmse")).doubleValue() : 0;

            summary = ReportResponseDTO.AiForecastReportDTO.builder()
                    .historical(historical)
                    .forecast(forecast)
                    .accuracy(accuracy)
                    .mae(mae)
                    .rmse(rmse)
                    .dataSource(data.get("dataSource") != null ? data.get("dataSource").toString() : "unknown")
                    .build();
        } catch (RestClientException e) {
            log.warn("AI module unavailable for forecast report: {}", e.getMessage());
            summary = ReportResponseDTO.AiForecastReportDTO.builder()
                    .historical(List.of())
                    .forecast(List.of())
                    .dataSource("unavailable")
                    .build();
        }

        return ReportResponseDTO.builder()
                .reportType(ReportRequestDTO.TYPE_AI_FORECAST)
                .reportTitle("AI Fault Volume Forecast Report")
                .period(req.getPeriod())
                .generatedAt(LocalDateTime.now())
                .totalRecords(summary.getForecast().size())
                .data(summary)
                .build();
    }

    private List<ReportResponseDTO.ForecastPointDTO> toForecastPoints(List<Map<String, Object>> raw, boolean forecast) {
        if (raw == null) return new ArrayList<>();
        List<ReportResponseDTO.ForecastPointDTO> points = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            points.add(ReportResponseDTO.ForecastPointDTO.builder()
                    .date(row.get("ds") != null ? row.get("ds").toString() : null)
                    .predictedFaults(numOrZero(row.get(forecast ? "yhat" : "y")))
                    .lowerBound(numOrZero(row.get("yhat_lower")))
                    .upperBound(numOrZero(row.get("yhat_upper")))
                    .isForecast(forecast)
                    .build());
        }
        return points;
    }

    private double numOrZero(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    // ─── REP-07: Geographic Demand Report ──────────────────

    @SuppressWarnings("unchecked")
    public ReportResponseDTO getGeographicDemandReport(ReportRequestDTO req) {
        log.info("Generating geographic demand report (REP-07)");

        ReportResponseDTO.GeographicDemandReportDTO summary;
        try {
            String url = aiBaseUrl + "/api/ai/clusters";
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            Map<String, Object> data = resp != null && resp.get("data") instanceof Map
                    ? (Map<String, Object>) resp.get("data") : resp;

            List<Map<String, Object>> rawClusters = (List<Map<String, Object>>) data.get("clusters");
            List<ReportResponseDTO.GeoClusterDTO> clusters = new ArrayList<>();
            if (rawClusters != null) {
                for (Map<String, Object> c : rawClusters) {
                    Map<String, Object> centroid = (Map<String, Object>) c.get("centroid");
                    clusters.add(ReportResponseDTO.GeoClusterDTO.builder()
                            .clusterId(c.get("clusterId") != null ? ((Number) c.get("clusterId")).intValue() : 0)
                            .regionName(c.get("regionName") != null ? c.get("regionName").toString() : "Unknown")
                            .faultCount(c.get("faultCount") != null ? ((Number) c.get("faultCount")).longValue() : 0)
                            .riskLevel(c.get("riskLevel") != null ? c.get("riskLevel").toString() : null)
                            .centroidLat(centroid != null ? numOrZero(centroid.get("lat")) : null)
                            .centroidLng(centroid != null ? numOrZero(centroid.get("lng")) : null)
                            .topCategory(c.get("topCategory") != null ? c.get("topCategory").toString() : null)
                            .build());
                }
            }

            summary = ReportResponseDTO.GeographicDemandReportDTO.builder()
                    .clusters(clusters)
                    .totalFaults(data.get("totalFaults") != null ? ((Number) data.get("totalFaults")).longValue() : 0)
                    .dataSource(data.get("dataSource") != null ? data.get("dataSource").toString() : "unknown")
                    .build();
        } catch (RestClientException e) {
            log.warn("AI module unavailable for geographic demand report: {}", e.getMessage());
            summary = ReportResponseDTO.GeographicDemandReportDTO.builder()
                    .clusters(List.of())
                    .dataSource("unavailable")
                    .build();
        }

        return ReportResponseDTO.builder()
                .reportType(ReportRequestDTO.TYPE_GEOGRAPHIC_DEMAND)
                .reportTitle("Geographic Demand Report")
                .period(req.getPeriod())
                .generatedAt(LocalDateTime.now())
                .totalRecords(summary.getClusters().size())
                .data(summary)
                .build();
    }

    // ─── REP-08: KPI Performance Report ────────────────────

    public ReportResponseDTO getKpiPerformanceReport(ReportRequestDTO req) {
        log.info("Generating KPI performance report (REP-08)");
        String period = req.getPeriod() != null && !req.getPeriod().isBlank() ? req.getPeriod() : "MONTHLY";

        // Stage F #2 — currentUserId is null (no "is this you" concept in a report context);
        // callerOpmcId is the real scoping value, null meaning unscoped (Super Admin).
        List<KpiDTO.LeaderboardEntryDTO> leaderboard =
                kpiCalculationService.getLeaderboard(period, null, req.getCallerOpmcId());

        List<ReportResponseDTO.KpiReportEntryDTO> entries = leaderboard.stream()
                .map(l -> {
                    List<KpiDTO.TargetResponseDTO> targets =
                            kpiCalculationService.getTechnicianTargets(l.getTechnicianId());
                    long achieved = targets.stream()
                            .filter(t -> "ACHIEVED".equals(t.getStatus()))
                            .count();
                    String badge = l.getRank() == 1 ? "GOLD" : l.getRank() == 2 ? "SILVER" : l.getRank() == 3 ? "BRONZE" : null;
                    return ReportResponseDTO.KpiReportEntryDTO.builder()
                            .technicianId(l.getTechnicianId().toString())
                            .technicianName(l.getTechnicianName())
                            .overallScore(l.getOverallScore())
                            .starRating(l.getStarRating())
                            .rank(l.getRank())
                            .badge(badge)
                            .targetsAchieved((int) achieved)
                            .totalTargets(targets.size())
                            .build();
                })
                .collect(Collectors.toList());

        return ReportResponseDTO.builder()
                .reportType(ReportRequestDTO.TYPE_KPI_PERFORMANCE)
                .reportTitle("KPI Performance Report")
                .period(period)
                .generatedAt(LocalDateTime.now())
                .totalRecords(entries.size())
                .data(entries)
                .build();
    }

    // ─── REP-09: Attendance Report ─────────────────────────

    public ReportResponseDTO getAttendanceReport(ReportRequestDTO req) {
        log.info("Generating attendance report (REP-09)");
        LocalDate[] range = resolveDateRange(req);
        LocalDateTime startDt = range[0].atStartOfDay();
        LocalDateTime endDt = range[1].atTime(23, 59, 59);

        // Stage F #2 — this filter used to trust a client-supplied opmcId request param; it now
        // uses the caller-resolved value only (null = unscoped, Super Admin).
        Long callerOpmcId = req.getCallerOpmcId();
        List<User> users = userRepository.findAll().stream()
                .filter(u -> u.getRole() != null
                        && ("TECHNICIAN".equals(u.getRole().name()) || "TEAM_LEAD".equals(u.getRole().name())))
                .filter(u -> callerOpmcId == null || callerOpmcId.equals(u.getOpmcId()))
                .collect(Collectors.toList());

        List<ReportResponseDTO.AttendanceReportItemDTO> items = new ArrayList<>();
        List<ReportResponseDTO.AttendanceSummaryDTO> summaries = new ArrayList<>();

        long totalDays = range[0].datesUntil(range[1].plusDays(1)).count();

        for (User u : users) {
            List<CheckInOut> records = checkInOutRepository.findByUserIdAndDateRange(u.getId(), startDt, endDt);
            for (CheckInOut c : records) {
                Double workingHours = null;
                if (c.getCheckInTime() != null && c.getCheckOutTime() != null) {
                    workingHours = java.time.Duration.between(c.getCheckInTime(), c.getCheckOutTime()).toMinutes() / 60.0;
                }
                items.add(ReportResponseDTO.AttendanceReportItemDTO.builder()
                        .userId(u.getId().toString())
                        .userName(u.getFullName())
                        .date(c.getCheckInTime() != null ? c.getCheckInTime().toLocalDate().format(DATE_FMT) : null)
                        .checkInTime(c.getCheckInTime())
                        .checkInLatitude(c.getCheckInLatitude())
                        .checkInLongitude(c.getCheckInLongitude())
                        .checkOutTime(c.getCheckOutTime())
                        .checkOutLatitude(c.getCheckOutLatitude())
                        .checkOutLongitude(c.getCheckOutLongitude())
                        .workingHours(workingHours != null ? Math.round(workingHours * 10.0) / 10.0 : null)
                        .absent(false)
                        .build());
            }

            long presentDays = records.stream()
                    .map(c -> c.getCheckInTime() != null ? c.getCheckInTime().toLocalDate() : null)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .count();
            long absentDays = Math.max(0, totalDays - presentDays);
            double avgWorkingHours = records.stream()
                    .filter(c -> c.getCheckInTime() != null && c.getCheckOutTime() != null)
                    .mapToDouble(c -> java.time.Duration.between(c.getCheckInTime(), c.getCheckOutTime()).toMinutes() / 60.0)
                    .average().orElse(0);
            double attendanceRate = totalDays > 0 ? (presentDays * 100.0 / totalDays) : 0;

            summaries.add(ReportResponseDTO.AttendanceSummaryDTO.builder()
                    .userId(u.getId().toString())
                    .userName(u.getFullName())
                    .presentDays(presentDays)
                    .absentDays(absentDays)
                    .attendanceRate(Math.round(attendanceRate * 10.0) / 10.0)
                    .avgWorkingHours(Math.round(avgWorkingHours * 10.0) / 10.0)
                    .build());
        }

        List<ReportResponseDTO.SummaryStatsDTO> summaryStats = summaries.stream()
                .map(s -> ReportResponseDTO.SummaryStatsDTO.builder()
                        .label(s.getUserName())
                        .value(s.getAttendanceRate() + "%")
                        .trend(s.getAttendanceRate() >= 90 ? "UP" : s.getAttendanceRate() >= 70 ? "STABLE" : "DOWN")
                        .build())
                .collect(Collectors.toList());

        return ReportResponseDTO.builder()
                .reportType(ReportRequestDTO.TYPE_ATTENDANCE)
                .reportTitle("Attendance Report")
                .period(req.getPeriod())
                .startDate(range[0].format(DATE_FMT))
                .endDate(range[1].format(DATE_FMT))
                .generatedAt(LocalDateTime.now())
                .totalRecords(items.size())
                .summaryStats(summaryStats)
                .data(items)
                .build();
    }

    // ─── REP-10: Audit Trail Report ────────────────────────

    public ReportResponseDTO getAuditTrailReport(ReportRequestDTO req) {
        log.info("Generating audit trail report (REP-10)");
        LocalDate[] range = resolveDateRange(req);
        LocalDateTime startDt = range[0].atStartOfDay();
        LocalDateTime endDt = range[1].atTime(23, 59, 59);
        // Stage F #3 — same root cause as Stage F #2 (RES-023's pattern never generalized), not a
        // separate bug: none of the four audit sources below carry opmcId directly, each is scoped
        // through a lookup map to the entity it references. null callerOpmcId is unscoped.
        Long callerOpmcId = req.getCallerOpmcId();

        List<ReportResponseDTO.AuditTrailItemDTO> items = new ArrayList<>();

        boolean wantFault = req.getEntityType() == null || req.getEntityType().isBlank()
                || "FAULT".equalsIgnoreCase(req.getEntityType());
        boolean wantPayment = req.getEntityType() == null || req.getEntityType().isBlank()
                || "PAYMENT".equalsIgnoreCase(req.getEntityType());
        boolean wantStock = req.getEntityType() == null || req.getEntityType().isBlank()
                || "STOCK".equalsIgnoreCase(req.getEntityType());
        boolean wantUser = req.getEntityType() == null || req.getEntityType().isBlank()
                || "USER".equalsIgnoreCase(req.getEntityType());

        if (wantFault) {
            Map<Long, Fault> faultsById = faultRepository.findAll().stream()
                    .filter(f -> f.getId() != null)
                    .collect(Collectors.toMap(Fault::getId, f -> f, (a, b) -> a));
            faultHistoryRepository.findAllRecent().stream()
                    .filter(h -> h.getCreatedAt() != null && !h.getCreatedAt().isBefore(startDt) && !h.getCreatedAt().isAfter(endDt))
                    .filter(h -> callerOpmcId == null
                            || (h.getFault() != null && faultsById.get(h.getFault().getId()) != null
                                && callerOpmcId.equals(faultsById.get(h.getFault().getId()).getOpmcId())))
                    .forEach(h -> {
                        // Actor identity is embedded in the description as "{name} [{role}]: {reason}"
                        // rather than the (always-null) `actor` relation — see FaultService.writeHistory().
                        String actorName = null;
                        String actorRole = null;
                        if (h.getDescription() != null && h.getDescription().contains(" [")) {
                            int bracketStart = h.getDescription().indexOf(" [");
                            int bracketEnd = h.getDescription().indexOf(']', bracketStart);
                            actorName = h.getDescription().substring(0, bracketStart);
                            if (bracketEnd > bracketStart) {
                                actorRole = h.getDescription().substring(bracketStart + 2, bracketEnd);
                            }
                        }
                        items.add(ReportResponseDTO.AuditTrailItemDTO.builder()
                                .entityType("FAULT")
                                .entityId(h.getFaultNumber())
                                .action(h.getEventType())
                                .actorName(actorName)
                                .actorRole(actorRole)
                                .ipAddress(h.getIpAddress())
                                .previousValue(h.getPreviousValue())
                                .newValue(h.getNewValue())
                                .details(h.getTitle())
                                .timestamp(h.getCreatedAt())
                                .build());
                    });
        }

        if (wantPayment) {
            Map<Long, Payment> paymentsById = paymentRepository.findAll().stream()
                    .filter(p -> p.getId() != null)
                    .collect(Collectors.toMap(Payment::getId, p -> p, (a, b) -> a));
            paymentApprovalRepository.findAll().stream()
                    .filter(a -> a.getCreatedAt() != null && !a.getCreatedAt().isBefore(startDt) && !a.getCreatedAt().isAfter(endDt))
                    .filter(a -> callerOpmcId == null
                            || (paymentsById.get(a.getPaymentId()) != null
                                && callerOpmcId.equals(paymentsById.get(a.getPaymentId()).getOpmcId())))
                    .forEach(a -> items.add(ReportResponseDTO.AuditTrailItemDTO.builder()
                            .entityType("PAYMENT")
                            .entityId(String.valueOf(a.getPaymentId()))
                            .action(a.getAction() != null ? a.getAction().name() : null)
                            .actorName(a.getAdminName())
                            .actorRole(a.getAdminRole())
                            .ipAddress(a.getIpAddress())
                            .previousValue(a.getOriginalAmount() != null ? a.getOriginalAmount().toString() : null)
                            .newValue(a.getAdjustedAmount() != null ? a.getAdjustedAmount().toString() : null)
                            .details(a.getReason())
                            .timestamp(a.getCreatedAt())
                            .build()));
        }

        if (wantStock) {
            Map<Long, Material> materialsById = materialRepository.findAll().stream()
                    .collect(Collectors.toMap(Material::getId, m -> m, (a, b) -> a));
            stockTransactionRepository.findByDateRange(startDt, endDt).stream()
                    .filter(st -> callerOpmcId == null
                            || (materialsById.get(st.getMaterialId()) != null
                                && callerOpmcId.equals(materialsById.get(st.getMaterialId()).getOpmcId())))
                    .forEach(st -> items.add(ReportResponseDTO.AuditTrailItemDTO.builder()
                            .entityType("STOCK")
                            .entityId(st.getMaterialName())
                            .action(st.getTransactionType() != null ? st.getTransactionType().name() : null)
                            .actorName(st.getPerformedByName())
                            .actorRole(st.getPerformedByRole())
                            .ipAddress(st.getIpAddress())
                            .previousValue(st.getStockBefore() != null ? st.getStockBefore().toString() : null)
                            .newValue(st.getStockAfter() != null ? st.getStockAfter().toString() : null)
                            .details(st.getReason())
                            .timestamp(st.getCreatedAt())
                            .build()));
        }

        if (wantUser) {
            Map<Long, User> targetUsersById = userRepository.findAll().stream()
                    .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
            userAuditLogRepository.findByDateRange(startDt, endDt).stream()
                    .filter(u -> callerOpmcId == null
                            || (targetUsersById.get(u.getTargetUserId()) != null
                                && callerOpmcId.equals(targetUsersById.get(u.getTargetUserId()).getOpmcId())))
                    .forEach(u -> items.add(ReportResponseDTO.AuditTrailItemDTO.builder()
                            .entityType("USER")
                            .entityId(u.getTargetUserName())
                            .action(u.getAction() != null ? u.getAction().name() : null)
                            .actorName(u.getPerformedByName())
                            .actorRole(u.getPerformedByRole())
                            .ipAddress(u.getIpAddress())
                            .previousValue(u.getPreviousValue())
                            .newValue(u.getNewValue())
                            .details(null)
                            .timestamp(u.getCreatedAt())
                            .build()));
        }

        items.sort((a, b) -> {
            if (a.getTimestamp() == null || b.getTimestamp() == null) return 0;
            return b.getTimestamp().compareTo(a.getTimestamp());
        });

        return ReportResponseDTO.builder()
                .reportType(ReportRequestDTO.TYPE_AUDIT_TRAIL)
                .reportTitle("Audit Trail Report")
                .period(req.getPeriod())
                .startDate(range[0].format(DATE_FMT))
                .endDate(range[1].format(DATE_FMT))
                .generatedAt(LocalDateTime.now())
                .totalRecords(items.size())
                .data(items)
                .build();
    }

    // ─── Report Dispatch (used by CSV/PDF/Excel export) ────

    private ReportResponseDTO generateReportData(ReportRequestDTO req) {
        switch (req.getReportType()) {
            case ReportRequestDTO.TYPE_FAULT_TRENDS:
                return getFaultTrends(req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getCallerOpmcId());
            case ReportRequestDTO.TYPE_TECHNICIAN_PERFORMANCE:
                return getTechnicianPerformance(req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getTechnicianId(), req.getCallerOpmcId());
            case ReportRequestDTO.TYPE_FINANCIAL_SUMMARY:
                return getFinancialSummary(req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getCallerOpmcId());
            case ReportRequestDTO.TYPE_CUSTOMER_SATISFACTION:
                return getCustomerSatisfaction(req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getCallerOpmcId());
            case ReportRequestDTO.TYPE_DAILY_FAULT_SUMMARY:
                return getDailyFaultSummary(req);
            case ReportRequestDTO.TYPE_FAULT_AGING:
                return getFaultAging(req);
            case ReportRequestDTO.TYPE_MATERIAL_COST:
                return getMaterialCostReport(req);
            case ReportRequestDTO.TYPE_AI_FORECAST:
                return getAiForecastReport(req);
            case ReportRequestDTO.TYPE_GEOGRAPHIC_DEMAND:
                return getGeographicDemandReport(req);
            case ReportRequestDTO.TYPE_KPI_PERFORMANCE:
                return getKpiPerformanceReport(req);
            case ReportRequestDTO.TYPE_ATTENDANCE:
                return getAttendanceReport(req);
            case ReportRequestDTO.TYPE_AUDIT_TRAIL:
                return getAuditTrailReport(req);
            default:
                throw new RuntimeException("Unknown report type: " + req.getReportType());
        }
    }

    // ─── Generic row flattening (used by CSV export and by the ──
    // ─── generic PDF/Excel table builders for the newer report types) ──

    @SuppressWarnings("unchecked")
    private List<LinkedHashMap<String, Object>> resolveDetailRows(ReportRequestDTO req, ReportResponseDTO resp) {
        Object data = resp.getData();
        Object rowsSource = data;

        switch (req.getReportType()) {
            case ReportRequestDTO.TYPE_DAILY_FAULT_SUMMARY:
                rowsSource = ((ReportResponseDTO.DailyFaultSummaryDTO) data).getTechnicianWorkload();
                break;
            case ReportRequestDTO.TYPE_MATERIAL_COST:
                rowsSource = ((ReportResponseDTO.MaterialCostReportDTO) data).getMostUsedMaterials();
                break;
            case ReportRequestDTO.TYPE_AI_FORECAST:
                rowsSource = ((ReportResponseDTO.AiForecastReportDTO) data).getForecast();
                break;
            case ReportRequestDTO.TYPE_GEOGRAPHIC_DEMAND:
                rowsSource = ((ReportResponseDTO.GeographicDemandReportDTO) data).getClusters();
                break;
            case ReportRequestDTO.TYPE_FINANCIAL_SUMMARY:
                rowsSource = ((ReportResponseDTO.FinancialSummaryDTO) data).getCategoryBreakdown();
                break;
            case ReportRequestDTO.TYPE_CUSTOMER_SATISFACTION:
                rowsSource = ((ReportResponseDTO.CustomerSatisfactionDTO) data).getTechnicianScores();
                break;
            default:
                // Already a flat List (FAULT_TRENDS, TECHNICIAN_PERFORMANCE, FAULT_AGING,
                // KPI_PERFORMANCE, ATTENDANCE, AUDIT_TRAIL)
                break;
        }

        List<?> list = (rowsSource instanceof List) ? (List<?>) rowsSource
                : (rowsSource != null ? List.of(rowsSource) : List.of());
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (Object o : list) {
            rows.add(objectMapper.convertValue(o, new TypeReference<LinkedHashMap<String, Object>>() {}));
        }
        return rows;
    }

    // ─── Export CSV ────────────────────────────────────────

    public byte[] exportCsv(ReportRequestDTO req) throws IOException {
        log.info("Exporting CSV report: type={}", req.getReportType());
        ReportResponseDTO resp = generateReportData(req);
        List<LinkedHashMap<String, Object>> rows = resolveDetailRows(req, resp);

        StringBuilder sb = new StringBuilder();
        if (rows.isEmpty()) {
            sb.append("No data available for the selected period\n");
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        LinkedHashSet<String> headers = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) headers.addAll(row.keySet());

        sb.append(String.join(",", headers)).append("\n");
        for (Map<String, Object> row : rows) {
            List<String> vals = new ArrayList<>();
            for (String h : headers) {
                Object v = row.get(h);
                String s = v == null ? "" : v.toString();
                if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
                    s = "\"" + s.replace("\"", "\"\"") + "\"";
                }
                vals.add(s);
            }
            sb.append(String.join(",", vals)).append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ─── Export PDF ───────────────────────────────────────

    public byte[] exportPdf(ReportRequestDTO req)
            throws IOException {
        log.info("Exporting PDF report: type={}",
                req.getReportType());

        ByteArrayOutputStream baos =
                new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);

        try {
            PdfFont boldFont =
                    PdfFontFactory.createFont(
                            StandardFonts.HELVETICA_BOLD);
            PdfFont regularFont =
                    PdfFontFactory.createFont(
                            StandardFonts.HELVETICA);

            DeviceRgb primaryColor =
                    new DeviceRgb(0, 48, 135);
            DeviceRgb headerBgColor =
                    new DeviceRgb(0, 48, 135);
            DeviceRgb lightGray =
                    new DeviceRgb(245, 245, 245);

            // ── Header ────────────────────────────────
            Paragraph header = new Paragraph(
                    "SLT Field Operations System")
                    .setFont(boldFont)
                    .setFontSize(20)
                    .setFontColor(primaryColor)
                    .setTextAlignment(
                            TextAlignment.CENTER)
                    .setMarginBottom(5);
            document.add(header);

            // Report title
            String title = req.getTitle() != null
                    ? req.getTitle()
                    : getReportTitle(req.getReportType());
            Paragraph titlePara =
                    new Paragraph(title)
                            .setFont(boldFont)
                            .setFontSize(16)
                            .setTextAlignment(
                                    TextAlignment.CENTER)
                            .setMarginBottom(5);
            document.add(titlePara);

            // Generated date
            Paragraph datePara = new Paragraph(
                    "Generated: "
                            + LocalDateTime.now()
                            .format(DATETIME_FMT))
                    .setFont(regularFont)
                    .setFontSize(9)
                    .setFontColor(
                            ColorConstants.GRAY)
                    .setTextAlignment(
                            TextAlignment.CENTER)
                    .setMarginBottom(20);
            document.add(datePara);

            // ── Build table based on report type ──────
            switch (req.getReportType()) {

                case ReportRequestDTO
                        .TYPE_FAULT_TRENDS:
                    addFaultTrendsPdfTable(
                            document,
                            boldFont,
                            regularFont,
                            headerBgColor,
                            lightGray,
                            req);
                    break;

                case ReportRequestDTO
                        .TYPE_TECHNICIAN_PERFORMANCE:
                    addTechnicianPerformancePdfTable(
                            document,
                            boldFont,
                            regularFont,
                            headerBgColor,
                            lightGray,
                            req);
                    break;

                case ReportRequestDTO
                        .TYPE_FINANCIAL_SUMMARY:
                    addFinancialSummaryPdfTable(
                            document,
                            boldFont,
                            regularFont,
                            headerBgColor,
                            lightGray,
                            req);
                    break;

                case ReportRequestDTO
                        .TYPE_CUSTOMER_SATISFACTION:
                    addCustomerSatisfactionPdfTable(
                            document,
                            boldFont,
                            regularFont,
                            headerBgColor,
                            lightGray,
                            req);
                    break;

                case ReportRequestDTO.TYPE_DAILY_FAULT_SUMMARY:
                case ReportRequestDTO.TYPE_FAULT_AGING:
                case ReportRequestDTO.TYPE_MATERIAL_COST:
                case ReportRequestDTO.TYPE_AI_FORECAST:
                case ReportRequestDTO.TYPE_GEOGRAPHIC_DEMAND:
                case ReportRequestDTO.TYPE_KPI_PERFORMANCE:
                case ReportRequestDTO.TYPE_ATTENDANCE:
                case ReportRequestDTO.TYPE_AUDIT_TRAIL:
                    addGenericPdfTable(
                            document,
                            boldFont,
                            regularFont,
                            headerBgColor,
                            lightGray,
                            req);
                    break;

                default:
                    document.add(new Paragraph(
                            "Report data not available")
                            .setFont(regularFont));
            }

            // ── Footer ────────────────────────────────
            document.add(new Paragraph(
                    "\n─────────────────────────────"
                            + "─────────────────────────"
                            + "────────────────────\n"
                            + "SLT After-Service Issue "
                            + "Management System  |  "
                            + "Confidential  |  "
                            + LocalDate.now()
                            .format(DATE_FMT))
                    .setFont(regularFont)
                    .setFontSize(8)
                    .setFontColor(ColorConstants.GRAY)
                    .setTextAlignment(
                            TextAlignment.CENTER)
                    .setMarginTop(20));

        } finally {
            document.close();
        }

        return baos.toByteArray();
    }

    // ─── Export Excel ─────────────────────────────────────

    public byte[] exportExcel(ReportRequestDTO req)
            throws IOException {
        log.info("Exporting Excel report: type={}",
                req.getReportType());

        try (Workbook workbook = new XSSFWorkbook()) {

            // ── Styles ────────────────────────────────
            CellStyle headerStyle =
                    createHeaderStyle(workbook);
            CellStyle dataStyle =
                    createDataStyle(workbook);
            CellStyle titleStyle =
                    createTitleStyle(workbook);
            CellStyle altRowStyle =
                    createAltRowStyle(workbook);

            String sheetName = getReportTitle(
                    req.getReportType());
            Sheet sheet = workbook.createSheet(
                    sheetName.length() > 31
                            ? sheetName.substring(0, 31)
                            : sheetName);

            int rowNum = 0;

            // ── Title Row ─────────────────────────────
            Row titleRow = sheet.createRow(rowNum++);
            titleRow.setHeightInPoints(30);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(
                    "SLT Field Operations System — "
                            + getReportTitle(
                            req.getReportType()));
            titleCell.setCellStyle(titleStyle);

            // ── Subtitle Row ──────────────────────────
            Row subtitleRow = sheet.createRow(rowNum++);
            Cell subtitleCell =
                    subtitleRow.createCell(0);
            subtitleCell.setCellValue(
                    "Generated: "
                            + LocalDateTime.now()
                            .format(DATETIME_FMT)
                            + "  |  Period: "
                            + (req.getPeriod() != null
                            ? req.getPeriod()
                            : "Custom"));
            rowNum++; // blank row

            // ── Build sheet based on report type ──────
            switch (req.getReportType()) {

                case ReportRequestDTO
                        .TYPE_FAULT_TRENDS:
                    buildFaultTrendsSheet(
                            sheet, rowNum,
                            headerStyle, dataStyle,
                            altRowStyle, req);
                    sheet.setColumnWidth(0, 4000);
                    sheet.setColumnWidth(1, 3500);
                    sheet.setColumnWidth(2, 3500);
                    sheet.setColumnWidth(3, 4000);
                    sheet.setColumnWidth(4, 4000);
                    sheet.setColumnWidth(5, 4500);
                    break;

                case ReportRequestDTO
                        .TYPE_TECHNICIAN_PERFORMANCE:
                    buildTechnicianPerformanceSheet(
                            sheet, rowNum,
                            headerStyle, dataStyle,
                            altRowStyle, req);
                    for (int i = 0; i < 10; i++) {
                        sheet.setColumnWidth(
                                i, 5000);
                    }
                    break;

                case ReportRequestDTO
                        .TYPE_FINANCIAL_SUMMARY:
                    buildFinancialSummarySheet(
                            sheet, rowNum,
                            headerStyle, dataStyle,
                            altRowStyle, req);
                    sheet.setColumnWidth(0, 6000);
                    sheet.setColumnWidth(1, 4000);
                    break;

                case ReportRequestDTO
                        .TYPE_CUSTOMER_SATISFACTION:
                    buildCustomerSatisfactionSheet(
                            sheet, rowNum,
                            headerStyle, dataStyle,
                            altRowStyle, req);
                    sheet.setColumnWidth(0, 6000);
                    sheet.setColumnWidth(1, 4000);
                    break;

                case ReportRequestDTO.TYPE_DAILY_FAULT_SUMMARY:
                case ReportRequestDTO.TYPE_FAULT_AGING:
                case ReportRequestDTO.TYPE_MATERIAL_COST:
                case ReportRequestDTO.TYPE_AI_FORECAST:
                case ReportRequestDTO.TYPE_GEOGRAPHIC_DEMAND:
                case ReportRequestDTO.TYPE_KPI_PERFORMANCE:
                case ReportRequestDTO.TYPE_ATTENDANCE:
                case ReportRequestDTO.TYPE_AUDIT_TRAIL:
                    buildGenericSheet(
                            sheet, rowNum,
                            headerStyle, dataStyle,
                            altRowStyle, req);
                    for (int i = 0; i < 8; i++) {
                        sheet.setColumnWidth(i, 5000);
                    }
                    break;

                default:
                    Row noDataRow =
                            sheet.createRow(rowNum);
                    noDataRow.createCell(0)
                            .setCellValue(
                                    "No data available");
            }

            // Merge title cells
            sheet.addMergedRegion(
                    new CellRangeAddress(0, 0, 0, 7));
            sheet.addMergedRegion(
                    new CellRangeAddress(1, 1, 0, 7));

            ByteArrayOutputStream baos =
                    new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    // ─── PDF Table Builders ───────────────────────────────

    private void addFaultTrendsPdfTable(
            Document document,
            PdfFont boldFont,
            PdfFont regularFont,
            DeviceRgb headerBg,
            DeviceRgb lightGray,
            ReportRequestDTO req) throws IOException {

        float[] colWidths = {2f, 1.5f, 1.5f,
                1.5f, 1.5f, 2f};
        Table table = new Table(
                UnitValue.createPercentArray(colWidths))
                .useAllAvailableWidth();

        String[] headers = {"Date", "Total",
                "Open", "In Progress",
                "Completed", "Avg Resolution (hrs)"};
        for (String h : headers) {
            table.addHeaderCell(
                    createPdfHeaderCell(
                            h, boldFont, headerBg));
        }

        @SuppressWarnings("unchecked")
        List<ReportResponseDTO.FaultTrendDTO> trends =
                (List<ReportResponseDTO.FaultTrendDTO>) getFaultTrends(
                        req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getCallerOpmcId()).getData();

        boolean alternate = false;
        for (ReportResponseDTO.FaultTrendDTO t : trends) {
            DeviceRgb bg = alternate ? lightGray : null;
            String[] row = {
                    t.getDate(),
                    String.valueOf(t.getTotalFaults()),
                    String.valueOf(t.getOpenFaults()),
                    String.valueOf(t.getInProgressFaults()),
                    String.valueOf(t.getCompletedFaults()),
                    String.format("%.1f", t.getAvgResolutionTimeHours())
            };

            for (String val : row) {
                table.addCell(createPdfDataCell(
                        val, regularFont, bg));
            }
            alternate = !alternate;
        }

        document.add(table);
    }

    private void addTechnicianPerformancePdfTable(
            Document document,
            PdfFont boldFont,
            PdfFont regularFont,
            DeviceRgb headerBg,
            DeviceRgb lightGray,
            ReportRequestDTO req) throws IOException {

        float[] colWidths =
                {2.5f, 2f, 1.5f, 1.5f, 1.5f, 1.5f};
        Table table = new Table(
                UnitValue.createPercentArray(colWidths))
                .useAllAvailableWidth();

        String[] headers = {
                "Technician", "Phone",
                "Total Jobs", "Completed",
                "Completion %", "Satisfaction"};
        for (String h : headers) {
            table.addHeaderCell(
                    createPdfHeaderCell(
                            h, boldFont, headerBg));
        }

        @SuppressWarnings("unchecked")
        List<ReportResponseDTO.TechnicianPerformanceDTO> performances =
                (List<ReportResponseDTO.TechnicianPerformanceDTO>) getTechnicianPerformance(
                        req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getTechnicianId(), req.getCallerOpmcId()).getData();

        boolean alternate = false;
        for (ReportResponseDTO.TechnicianPerformanceDTO p : performances) {
            DeviceRgb bg = alternate
                    ? lightGray : null;
            String[] row = {
                    p.getTechnicianName(),
                    p.getPhone() != null
                            ? p.getPhone() : "N/A",
                    String.valueOf(p.getTotalJobsAssigned()),
                    String.valueOf(p.getJobsCompleted()),
                    String.format("%.1f%%", p.getCompletionRate()),
                    p.getCustomerSatisfactionScore() > 0
                            ? String.format("%.1f ★", p.getCustomerSatisfactionScore())
                            : "N/A"
            };

            for (String val : row) {
                table.addCell(createPdfDataCell(
                        val, regularFont, bg));
            }
            alternate = !alternate;
        }

        document.add(table);
    }

    private void addFinancialSummaryPdfTable(
            Document document,
            PdfFont boldFont,
            PdfFont regularFont,
            DeviceRgb headerBg,
            DeviceRgb lightGray,
            ReportRequestDTO req) throws IOException {

        List<Payment> payments =
                paymentRepository.findAll();

        double totalFOC = payments.stream()
                .mapToDouble(p ->
                        p.getMaterialsFocTotal() != null
                                ? p.getMaterialsFocTotal()
                                .doubleValue()
                                : 0)
                .sum();
        double totalChargeable = payments.stream()
                .mapToDouble(p ->
                        p.getMaterialsChargeableTotal()
                                != null
                                ? p.getMaterialsChargeableTotal()
                                .doubleValue()
                                : 0)
                .sum();

        float[] colWidths = {3f, 3f};
        Table table = new Table(
                UnitValue.createPercentArray(colWidths))
                .useAllAvailableWidth();

        table.addHeaderCell(createPdfHeaderCell(
                "Metric", boldFont, headerBg));
        table.addHeaderCell(createPdfHeaderCell(
                "Value", boldFont, headerBg));

        String[][] rows = {
                {"Total Payments",
                        String.valueOf(payments.size())},
                {"Total FOC Amount",
                        String.format(
                                "LKR %.2f", totalFOC)},
                {"Total Chargeable",
                        String.format("LKR %.2f",
                                totalChargeable)},
                {"Total Revenue",
                        String.format("LKR %.2f",
                                totalFOC
                                        + totalChargeable)},
                {"Approved", String.valueOf(
                        payments.stream()
                                .filter(p ->
                                        p.getStatus()
                                                != null
                                                && "FINAL"
                                                .equals(p
                                                        .getStatus()
                                                        .name()))
                                .count())},
                {"Pending", String.valueOf(
                        payments.stream()
                                .filter(p ->
                                        p.getStatus()
                                                != null
                                                && "DRAFT"
                                                .equals(p
                                                        .getStatus()
                                                        .name()))
                                .count())},
        };

        boolean alternate = false;
        for (String[] row : rows) {
            DeviceRgb bg = alternate
                    ? lightGray : null;
            table.addCell(createPdfDataCell(
                    row[0], boldFont, bg));
            table.addCell(createPdfDataCell(
                    row[1], regularFont, bg));
            alternate = !alternate;
        }

        document.add(table);
    }

    private void addCustomerSatisfactionPdfTable(
            Document document,
            PdfFont boldFont,
            PdfFont regularFont,
            DeviceRgb headerBg,
            DeviceRgb lightGray,
            ReportRequestDTO req) throws IOException {

        float[] colWidths = {3f, 3f};
        Table table = new Table(
                UnitValue.createPercentArray(colWidths))
                .useAllAvailableWidth();

        table.addHeaderCell(createPdfHeaderCell(
                "Metric", boldFont, headerBg));
        table.addHeaderCell(createPdfHeaderCell(
                "Value", boldFont, headerBg));

        ReportResponseDTO.CustomerSatisfactionDTO cs =
                (ReportResponseDTO.CustomerSatisfactionDTO) getCustomerSatisfaction(
                        req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getCallerOpmcId()).getData();
        long totalResp = cs.getTotalResponses();
        String[][] rows = {
                {"Overall Score", String.format("%.1f / 5.0 ★", cs.getOverallScore())},
                {"Total Responses", String.valueOf(totalResp)},
                {"5 Star Ratings", cs.getRating5Count() + pct(cs.getRating5Count(), totalResp)},
                {"4 Star Ratings", cs.getRating4Count() + pct(cs.getRating4Count(), totalResp)},
                {"3 Star Ratings", cs.getRating3Count() + pct(cs.getRating3Count(), totalResp)},
                {"2 Star Ratings", cs.getRating2Count() + pct(cs.getRating2Count(), totalResp)},
                {"1 Star Ratings", cs.getRating1Count() + pct(cs.getRating1Count(), totalResp)},
                {"Avg Resolution Time", String.format("%.1f hours", cs.getAvgResolutionTimeHours())},
                {"On-Time Delivery", String.format("%.1f%%", cs.getOnTimeDeliveryRate())},
        };

        boolean alternate = false;
        for (String[] row : rows) {
            DeviceRgb bg = alternate
                    ? lightGray : null;
            table.addCell(createPdfDataCell(
                    row[0], boldFont, bg));
            table.addCell(createPdfDataCell(
                    row[1], regularFont, bg));
            alternate = !alternate;
        }

        document.add(table);
    }

    /**
     * Generic table renderer used by the newer report types (REP-01, 03, 05-10) —
     * builds headers from the keys of the first flattened row instead of a
     * bespoke per-type layout like the original four report builders above.
     */
    private void addGenericPdfTable(
            Document document,
            PdfFont boldFont,
            PdfFont regularFont,
            DeviceRgb headerBg,
            DeviceRgb lightGray,
            ReportRequestDTO req) throws IOException {

        ReportResponseDTO resp = generateReportData(req);
        List<LinkedHashMap<String, Object>> rows = resolveDetailRows(req, resp);

        if (rows.isEmpty()) {
            document.add(new Paragraph("No data available for the selected period")
                    .setFont(regularFont));
            return;
        }

        List<String> headers = new ArrayList<>(rows.get(0).keySet());
        float[] colWidths = new float[headers.size()];
        Arrays.fill(colWidths, 1f);
        Table table = new Table(UnitValue.createPercentArray(colWidths))
                .useAllAvailableWidth();

        for (String h : headers) {
            table.addHeaderCell(createPdfHeaderCell(h, boldFont, headerBg));
        }

        boolean alternate = false;
        for (Map<String, Object> row : rows) {
            DeviceRgb bg = alternate ? lightGray : null;
            for (String h : headers) {
                Object v = row.get(h);
                table.addCell(createPdfDataCell(v == null ? "" : v.toString(), regularFont, bg));
            }
            alternate = !alternate;
        }

        document.add(table);
    }

    // ─── Excel Sheet Builders ─────────────────────────────

    private void buildFaultTrendsSheet(
            Sheet sheet, int startRow,
            CellStyle headerStyle,
            CellStyle dataStyle,
            CellStyle altRowStyle,
            ReportRequestDTO req) {

        Row headerRow = sheet.createRow(startRow++);
        String[] headers = {
                "Date", "Total Faults",
                "Open", "In Progress",
                "Completed", "Avg Resolution (hrs)"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        @SuppressWarnings("unchecked")
        List<ReportResponseDTO.FaultTrendDTO> trends =
                (List<ReportResponseDTO.FaultTrendDTO>) getFaultTrends(
                        req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getCallerOpmcId()).getData();
        boolean alternate = false;

        for (ReportResponseDTO.FaultTrendDTO t : trends) {
            Row row = sheet.createRow(startRow++);
            CellStyle style = alternate
                    ? altRowStyle : dataStyle;

            row.createCell(0).setCellValue(t.getDate());
            row.createCell(1).setCellValue(t.getTotalFaults());
            row.createCell(2).setCellValue(t.getOpenFaults());
            row.createCell(3).setCellValue(t.getInProgressFaults());
            row.createCell(4).setCellValue(t.getCompletedFaults());
            row.createCell(5).setCellValue(t.getAvgResolutionTimeHours());

            for (int i = 0; i < 6; i++) {
                row.getCell(i).setCellStyle(style);
            }
            alternate = !alternate;
        }
    }

    private void buildTechnicianPerformanceSheet(
            Sheet sheet, int startRow,
            CellStyle headerStyle,
            CellStyle dataStyle,
            CellStyle altRowStyle,
            ReportRequestDTO req) {

        Row headerRow = sheet.createRow(startRow++);
        String[] headers = {
                "Technician", "Phone",
                "Total Jobs", "Completed",
                "Completion %", "Avg Duration (hrs)",
                "Response Time (mins)",
                "Satisfaction", "On-Time %"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        @SuppressWarnings("unchecked")
        List<ReportResponseDTO.TechnicianPerformanceDTO> performances =
                (List<ReportResponseDTO.TechnicianPerformanceDTO>) getTechnicianPerformance(
                        req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getTechnicianId(), req.getCallerOpmcId()).getData();

        boolean alternate = false;
        for (ReportResponseDTO.TechnicianPerformanceDTO p : performances) {
            Row row = sheet.createRow(startRow++);
            CellStyle style = alternate
                    ? altRowStyle : dataStyle;

            row.createCell(0).setCellValue(p.getTechnicianName());
            row.createCell(1).setCellValue(
                    p.getPhone() != null ? p.getPhone() : "N/A");
            row.createCell(2).setCellValue(p.getTotalJobsAssigned());
            row.createCell(3).setCellValue(p.getJobsCompleted());
            row.createCell(4).setCellValue(p.getCompletionRate());
            row.createCell(5).setCellValue(p.getAvgJobDurationHours());
            row.createCell(6).setCellValue(p.getAvgResponseTimeMinutes());
            row.createCell(7).setCellValue(p.getCustomerSatisfactionScore());
            row.createCell(8).setCellValue(p.getOnTimeCompletionRate());

            for (int i = 0; i < 9; i++) {
                if (row.getCell(i) != null) {
                    row.getCell(i)
                            .setCellStyle(style);
                }
            }
            alternate = !alternate;
        }
    }

    private void buildFinancialSummarySheet(
            Sheet sheet, int startRow,
            CellStyle headerStyle,
            CellStyle dataStyle,
            CellStyle altRowStyle,
            ReportRequestDTO req) {

        Row headerRow = sheet.createRow(startRow++);
        Cell h0 = headerRow.createCell(0);
        h0.setCellValue("Metric");
        h0.setCellStyle(headerStyle);
        Cell h1 = headerRow.createCell(1);
        h1.setCellValue("Value (LKR)");
        h1.setCellStyle(headerStyle);

        List<Payment> payments =
                paymentRepository.findAll();
        double totalFOC = payments.stream()
                .mapToDouble(p ->
                        p.getMaterialsFocTotal() != null
                                ? p.getMaterialsFocTotal()
                                .doubleValue()
                                : 0)
                .sum();
        double totalChargeable = payments.stream()
                .mapToDouble(p ->
                        p.getMaterialsChargeableTotal()
                                != null
                                ? p.getMaterialsChargeableTotal()
                                .doubleValue()
                                : 0)
                .sum();

        Object[][] rows = {
                {"Total Payments Submitted",
                        (double) payments.size()},
                {"Total FOC Amount", totalFOC},
                {"Total Chargeable Amount",
                        totalChargeable},
                {"Total Revenue",
                        totalFOC + totalChargeable},
        };

        boolean alternate = false;
        for (Object[] rowData : rows) {
            Row row = sheet.createRow(startRow++);
            CellStyle style = alternate
                    ? altRowStyle : dataStyle;
            Cell c0 = row.createCell(0);
            c0.setCellValue(
                    rowData[0].toString());
            c0.setCellStyle(style);
            Cell c1 = row.createCell(1);
            c1.setCellValue(
                    (Double) rowData[1]);
            c1.setCellStyle(style);
            alternate = !alternate;
        }
    }

    private void buildCustomerSatisfactionSheet(
            Sheet sheet, int startRow,
            CellStyle headerStyle,
            CellStyle dataStyle,
            CellStyle altRowStyle,
            ReportRequestDTO req) {

        Row headerRow = sheet.createRow(startRow++);
        Cell h0 = headerRow.createCell(0);
        h0.setCellValue("Metric");
        h0.setCellStyle(headerStyle);
        Cell h1 = headerRow.createCell(1);
        h1.setCellValue("Value");
        h1.setCellStyle(headerStyle);

        ReportResponseDTO.CustomerSatisfactionDTO cs =
                (ReportResponseDTO.CustomerSatisfactionDTO) getCustomerSatisfaction(
                        req.getPeriod(), req.getStartDate(), req.getEndDate(),
                        req.getCallerOpmcId()).getData();
        Object[][] rows = {
                {"Overall Score", cs.getOverallScore()},
                {"Total Responses", (double) cs.getTotalResponses()},
                {"5 Star", (double) cs.getRating5Count()},
                {"4 Star", (double) cs.getRating4Count()},
                {"3 Star", (double) cs.getRating3Count()},
                {"2 Star", (double) cs.getRating2Count()},
                {"1 Star", (double) cs.getRating1Count()},
                {"Avg Resolution (hrs)", cs.getAvgResolutionTimeHours()},
                {"On-Time Delivery %", cs.getOnTimeDeliveryRate()},
        };

        boolean alternate = false;
        for (Object[] rowData : rows) {
            Row row = sheet.createRow(startRow++);
            CellStyle style = alternate
                    ? altRowStyle : dataStyle;
            Cell c0 = row.createCell(0);
            c0.setCellValue((String) rowData[0]);
            c0.setCellStyle(style);
            Cell c1 = row.createCell(1);
            c1.setCellValue((Double) rowData[1]);
            c1.setCellStyle(style);
            alternate = !alternate;
        }
    }

    /** Generic sheet renderer for the newer report types — mirrors addGenericPdfTable(). */
    private void buildGenericSheet(
            Sheet sheet, int startRow,
            CellStyle headerStyle,
            CellStyle dataStyle,
            CellStyle altRowStyle,
            ReportRequestDTO req) {

        ReportResponseDTO resp = generateReportData(req);
        List<LinkedHashMap<String, Object>> rows = resolveDetailRows(req, resp);

        if (rows.isEmpty()) {
            Row noDataRow = sheet.createRow(startRow);
            noDataRow.createCell(0).setCellValue("No data available for the selected period");
            return;
        }

        List<String> headers = new ArrayList<>(rows.get(0).keySet());
        Row headerRow = sheet.createRow(startRow++);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
        }

        boolean alternate = false;
        for (Map<String, Object> rowData : rows) {
            Row row = sheet.createRow(startRow++);
            CellStyle style = alternate ? altRowStyle : dataStyle;
            for (int i = 0; i < headers.size(); i++) {
                Object v = rowData.get(headers.get(i));
                Cell cell = row.createCell(i);
                if (v instanceof Number) {
                    cell.setCellValue(((Number) v).doubleValue());
                } else if (v instanceof Boolean) {
                    cell.setCellValue((Boolean) v);
                } else {
                    cell.setCellValue(v == null ? "" : v.toString());
                }
                cell.setCellStyle(style);
            }
            alternate = !alternate;
        }
    }

    // ─── Excel Style Helpers ──────────────────────────────

    private CellStyle createHeaderStyle(
            Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(
                IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(
                FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(
                HorizontalAlignment.CENTER);
        style.setVerticalAlignment(
                VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createDataStyle(
            Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setVerticalAlignment(
                VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createAltRowStyle(
            Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setFillForegroundColor(
                IndexedColors.LIGHT_TURQUOISE
                        .getIndex());
        style.setFillPattern(
                FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setVerticalAlignment(
                VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createTitleStyle(
            Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        font.setColor(IndexedColors.DARK_BLUE
                .getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(
                VerticalAlignment.CENTER);
        return style;
    }

    // ─── PDF Cell Helpers ─────────────────────────────────

    private com.itextpdf.layout.element.Cell createPdfHeaderCell(
            String text,
            PdfFont font,
            DeviceRgb bgColor) throws IOException {
        return new com.itextpdf.layout.element.Cell()
                .add(new Paragraph(text)
                        .setFont(font)
                        .setFontSize(10)
                        .setFontColor(
                                ColorConstants.WHITE))
                .setBackgroundColor(bgColor)
                .setPadding(6)
                .setTextAlignment(
                        TextAlignment.CENTER);
    }

    private com.itextpdf.layout.element.Cell createPdfDataCell(
            String text,
            PdfFont font,
            DeviceRgb bgColor) throws IOException {
        com.itextpdf.layout.element.Cell cell =
                new com.itextpdf.layout.element.Cell()
                .add(new Paragraph(text)
                        .setFont(font)
                        .setFontSize(9))
                .setPadding(4);
        if (bgColor != null) {
            cell.setBackgroundColor(bgColor);
        }
        return cell;
    }

    // ─── Utility ──────────────────────────────────────────

    private String pct(long count, long total) {
        return total > 0 ? String.format(" (%.1f%%)", count * 100.0 / total) : " (0.0%)";
    }

    private String getReportTitle(String reportType) {
        switch (reportType) {
            case ReportRequestDTO.TYPE_FAULT_TRENDS:
                return "Fault Trends Report";
            case ReportRequestDTO
                    .TYPE_TECHNICIAN_PERFORMANCE:
                return "Technician Performance Report";
            case ReportRequestDTO
                    .TYPE_FINANCIAL_SUMMARY:
                return "Financial Summary Report";
            case ReportRequestDTO
                    .TYPE_CUSTOMER_SATISFACTION:
                return "Customer Satisfaction Report";
            case ReportRequestDTO.TYPE_DAILY_FAULT_SUMMARY:
                return "Daily Fault Summary Report";
            case ReportRequestDTO.TYPE_FAULT_AGING:
                return "Fault Aging Report";
            case ReportRequestDTO.TYPE_MATERIAL_COST:
                return "Material Cost Report";
            case ReportRequestDTO.TYPE_AI_FORECAST:
                return "AI Fault Volume Forecast Report";
            case ReportRequestDTO.TYPE_GEOGRAPHIC_DEMAND:
                return "Geographic Demand Report";
            case ReportRequestDTO.TYPE_KPI_PERFORMANCE:
                return "KPI Performance Report";
            case ReportRequestDTO.TYPE_ATTENDANCE:
                return "Attendance Report";
            case ReportRequestDTO.TYPE_AUDIT_TRAIL:
                return "Audit Trail Report";
            default:
                return "SLT Report";
        }
    }
}