package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.ReportRequestDTO;
import lk.slt.fieldops.dto.ReportResponseDTO;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

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
            LocalDate endDate) {
        log.info("Generating fault trends report: "
                + "period={}", period);

        ReportRequestDTO req = ReportRequestDTO.builder()
                .period(period)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        LocalDate[] range = resolveDateRange(req);

        List<Fault> faults = faultRepository.findAll();

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
            long open = dayFaults.stream()
                    .filter(f -> "OPEN".equals(
                            f.getStatus() != null
                                    ? f.getStatus()
                                    .name() : ""))
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

            trends.add(
                    ReportResponseDTO.FaultTrendDTO
                            .builder()
                            .date(dateKey)
                            .totalFaults(dayFaults.size())
                            .openFaults(open)
                            .inProgressFaults(inProgress)
                            .completedFaults(completed)
                            .cancelledFaults(cancelled)
                            .avgResolutionTimeHours(2.5)
                            .build());
            current = current.plusDays(1);
        }

        // Summary stats
        long totalFaults = faults.size();
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
            LocalDate endDate) {
        log.info("Generating technician performance report");

        List<User> technicians = userRepository.findAll()
                .stream()
                .filter(u -> u.getRole() != null
                        && (u.getRole().name()
                        .equals("TECHNICIAN")
                        || u.getRole().name()
                        .equals("TEAM_LEAD")))
                .collect(Collectors.toList());

        List<ReportResponseDTO.TechnicianPerformanceDTO>
                performances = new ArrayList<>();

        for (User tech : technicians) {
            List<Job> jobs = jobRepository.findAll()
                    .stream()
                    .filter(j -> tech.getId().equals(j.getTechnicianId()))
                    .collect(Collectors.toList());

            long completed = jobs.stream()
                    .filter(j -> "COMPLETED".equals(
                            j.getStatus() != null
                                    ? j.getStatus().name()
                                    : ""))
                    .count();
            long total = jobs.size();
            double completionRate = total > 0
                    ? (completed * 100.0 / total) : 0;

            performances.add(
                    ReportResponseDTO
                            .TechnicianPerformanceDTO
                            .builder()
                            .technicianId(
                                    tech.getId().toString())
                            .technicianName(
                                    tech.getFullName())
                            .phone(tech.getPhone())
                            .totalJobsAssigned(total)
                            .jobsCompleted(completed)
                            .completionRate(completionRate)
                            .avgJobDurationHours(2.3)
                            .avgResponseTimeMinutes(22.0)
                            .customerSatisfactionScore(4.5)
                            .onTimeCompletionRate(85.0)
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
            LocalDate endDate) {
        log.info("Generating financial summary report");

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

        ReportResponseDTO.FinancialSummaryDTO summary =
                ReportResponseDTO.FinancialSummaryDTO
                        .builder()
                        .period(period)
                        .totalRevenue(totalRevenue)
                        .totalFOCAmount(totalFOC)
                        .totalChargeableAmount(
                                totalChargeable)
                        .totalPaymentsSubmitted(
                                payments.size())
                        .totalPaymentsApproved(approved)
                        .totalPaymentsRejected(rejected)
                        .totalPaymentsPending(pending)
                        .approvalRate(approvalRate)
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
            LocalDate endDate) {
        log.info(
                "Generating customer satisfaction report");

        // Mock satisfaction data
        // Replace with actual rating repository
        // when ratings feature is implemented
        ReportResponseDTO.CustomerSatisfactionDTO
                satisfaction =
                ReportResponseDTO.CustomerSatisfactionDTO
                        .builder()
                        .period(period)
                        .overallScore(4.5)
                        .totalResponses(150)
                        .rating5Count(80)
                        .rating4Count(40)
                        .rating3Count(20)
                        .rating2Count(7)
                        .rating1Count(3)
                        .avgResolutionTimeHours(2.8)
                        .onTimeDeliveryRate(87.5)
                        .technicianScores(List.of(
                                ReportResponseDTO
                                        .TechnicianSatisfactionDTO
                                        .builder()
                                        .technicianName(
                                                "Kasun Perera")
                                        .avgScore(4.8)
                                        .totalRatings(30)
                                        .build(),
                                ReportResponseDTO
                                        .TechnicianSatisfactionDTO
                                        .builder()
                                        .technicianName(
                                                "Nimal Silva")
                                        .avgScore(4.3)
                                        .totalRatings(25)
                                        .build()))
                        .build();

        return ReportResponseDTO.builder()
                .reportType(
                        ReportRequestDTO
                                .TYPE_CUSTOMER_SATISFACTION)
                .reportTitle(
                        "Customer Satisfaction Report")
                .period(period)
                .generatedAt(LocalDateTime.now())
                .totalRecords(150)
                .data(satisfaction)
                .build();
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

        ReportRequestDTO dateReq =
                ReportRequestDTO.builder()
                        .period(req.getPeriod())
                        .startDate(req.getStartDate())
                        .endDate(req.getEndDate())
                        .build();
        LocalDate[] range = resolveDateRange(dateReq);

        List<Fault> faults = faultRepository.findAll();
        LocalDate current = range[0];
        boolean alternate = false;

        while (!current.isAfter(range[1])) {
            String dateKey =
                    current.format(DATE_FMT);
            final LocalDate finalCurrent = current;

            List<Fault> dayFaults = faults.stream()
                    .filter(f -> f.getCreatedAt() != null
                            && f.getCreatedAt()
                            .toLocalDate()
                            .equals(finalCurrent))
                    .collect(Collectors.toList());

            DeviceRgb bg = alternate
                    ? lightGray : null;
            String[] row = {
                    dateKey,
                    String.valueOf(dayFaults.size()),
                    String.valueOf(dayFaults.stream()
                            .filter(f ->
                                    "OPEN".equals(
                                            f.getStatus() != null
                                                    ? f.getStatus().name()
                                                    : ""))
                            .count()),
                    String.valueOf(dayFaults.stream()
                            .filter(f ->
                                    "IN_PROGRESS".equals(
                                            f.getStatus() != null
                                                    ? f.getStatus().name()
                                                    : ""))
                            .count()),
                    String.valueOf(dayFaults.stream()
                            .filter(f ->
                                    "COMPLETED".equals(
                                            f.getStatus() != null
                                                    ? f.getStatus().name()
                                                    : ""))
                            .count()),
                    "2.5"
            };

            for (String val : row) {
                table.addCell(createPdfDataCell(
                        val, regularFont, bg));
            }
            alternate = !alternate;
            current = current.plusDays(1);
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

        List<User> technicians =
                userRepository.findAll().stream()
                        .filter(u -> u.getRole() != null
                                && (u.getRole().name()
                                .equals("TECHNICIAN")
                                || u.getRole().name()
                                .equals("TEAM_LEAD")))
                        .collect(Collectors.toList());

        boolean alternate = false;
        for (User tech : technicians) {
            List<Job> jobs =
                    jobRepository.findAll().stream()
                            .filter(j -> tech.getId().equals(j.getTechnicianId()))
                            .collect(Collectors.toList());

            long completed = jobs.stream()
                    .filter(j -> "COMPLETED".equals(
                            j.getStatus() != null
                                    ? j.getStatus().name()
                                    : ""))
                    .count();
            double rate = jobs.size() > 0
                    ? (completed * 100.0 / jobs.size())
                    : 0;

            DeviceRgb bg = alternate
                    ? lightGray : null;
            String[] row = {
                    tech.getFullName(),
                    tech.getPhone() != null
                            ? tech.getPhone() : "N/A",
                    String.valueOf(jobs.size()),
                    String.valueOf(completed),
                    String.format("%.1f%%", rate),
                    "4.5 ★"
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

        String[][] rows = {
                {"Overall Score", "4.5 / 5.0 ★"},
                {"Total Responses", "150"},
                {"5 Star Ratings", "80 (53.3%)"},
                {"4 Star Ratings", "40 (26.7%)"},
                {"3 Star Ratings", "20 (13.3%)"},
                {"Avg Resolution Time", "2.8 hours"},
                {"On-Time Delivery", "87.5%"},
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

        List<Fault> faults = faultRepository.findAll();
        LocalDate[] range = resolveDateRange(req);
        LocalDate current = range[0];
        boolean alternate = false;

        while (!current.isAfter(range[1])) {
            final LocalDate finalDate = current;
            List<Fault> dayFaults = faults.stream()
                    .filter(f ->
                            f.getCreatedAt() != null
                                    && f.getCreatedAt()
                                    .toLocalDate()
                                    .equals(finalDate))
                    .collect(Collectors.toList());

            Row row = sheet.createRow(startRow++);
            CellStyle style = alternate
                    ? altRowStyle : dataStyle;

            row.createCell(0).setCellValue(
                    finalDate.format(DATE_FMT));
            row.createCell(1).setCellValue(
                    dayFaults.size());
            row.createCell(2).setCellValue(
                    dayFaults.stream()
                            .filter(f -> "OPEN"
                                    .equals(f.getStatus()
                                            != null
                                            ? f.getStatus()
                                            .name()
                                            : ""))
                            .count());
            row.createCell(3).setCellValue(
                    dayFaults.stream()
                            .filter(f -> "IN_PROGRESS"
                                    .equals(f.getStatus()
                                            != null
                                            ? f.getStatus()
                                            .name()
                                            : ""))
                            .count());
            row.createCell(4).setCellValue(
                    dayFaults.stream()
                            .filter(f -> "COMPLETED"
                                    .equals(f.getStatus()
                                            != null
                                            ? f.getStatus()
                                            .name()
                                            : ""))
                            .count());
            row.createCell(5).setCellValue(2.5);

            for (int i = 0; i < 6; i++) {
                row.getCell(i).setCellStyle(style);
            }
            alternate = !alternate;
            current = current.plusDays(1);
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

        List<User> technicians =
                userRepository.findAll().stream()
                        .filter(u ->
                                u.getRole() != null
                                        && (u.getRole()
                                        .name()
                                        .equals(
                                                "TECHNICIAN")
                                        || u.getRole()
                                        .name()
                                        .equals(
                                                "TEAM_LEAD")))
                        .collect(Collectors.toList());

        boolean alternate = false;
        for (User tech : technicians) {
            List<Job> jobs =
                    jobRepository.findAll().stream()
                            .filter(j -> tech.getId().equals(j.getTechnicianId()))
                            .collect(Collectors.toList());

            long completed = jobs.stream()
                    .filter(j -> "COMPLETED"
                            .equals(j.getStatus() != null
                                    ? j.getStatus().name()
                                    : ""))
                    .count();
            double rate = jobs.size() > 0
                    ? (completed * 100.0 / jobs.size())
                    : 0;

            Row row = sheet.createRow(startRow++);
            CellStyle style = alternate
                    ? altRowStyle : dataStyle;

            row.createCell(0).setCellValue(
                    tech.getFullName());
            row.createCell(1).setCellValue(
                    tech.getPhone() != null
                            ? tech.getPhone() : "N/A");
            row.createCell(2).setCellValue(jobs.size());
            row.createCell(3).setCellValue(completed);
            row.createCell(4).setCellValue(
                    Math.round(rate * 10.0) / 10.0);
            row.createCell(5).setCellValue(2.3);
            row.createCell(6).setCellValue(22.0);
            row.createCell(7).setCellValue(4.5);
            row.createCell(8).setCellValue(85.0);

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

        String[][] rows = {
                {"Overall Score", "4.5 / 5.0"},
                {"Total Responses", "150"},
                {"5 Star", "80"},
                {"4 Star", "40"},
                {"3 Star", "20"},
                {"2 Star", "7"},
                {"1 Star", "3"},
                {"Avg Resolution (hrs)", "2.8"},
                {"On-Time Delivery %", "87.5"},
        };

        boolean alternate = false;
        for (String[] rowData : rows) {
            Row row = sheet.createRow(startRow++);
            CellStyle style = alternate
                    ? altRowStyle : dataStyle;
            Cell c0 = row.createCell(0);
            c0.setCellValue(rowData[0]);
            c0.setCellStyle(style);
            Cell c1 = row.createCell(1);
            c1.setCellValue(rowData[1]);
            c1.setCellStyle(style);
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
            default:
                return "SLT Report";
        }
    }
}