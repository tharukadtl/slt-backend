package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.ReportRequestDTO;
import lk.slt.fieldops.dto.ReportResponseDTO;
import lk.slt.fieldops.service.ReportService;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor

public class ReportController {

    private final ReportService reportService;
    private final OpmcAccessGuard opmcGuard;

    private static final DateTimeFormatter FILE_FMT =
            DateTimeFormatter.ofPattern(
                    "yyyyMMdd_HHmmss");

    // â”€â”€â”€ GET Endpoints â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * GET /api/reports/fault-trends
     * Query params:
     *   period = TODAY | THIS_WEEK |
     *            THIS_MONTH | LAST_MONTH | CUSTOM
     *   startDate = 2026-01-01 (only for CUSTOM)
     *   endDate   = 2026-04-30 (only for CUSTOM)
     */
    @GetMapping("/fault-trends")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO>
    getFaultTrends(
            @RequestParam(
                    defaultValue = "THIS_MONTH")
            String period,
            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,
            @AuthenticationPrincipal Long callerId) {

        log.info(
                "GET /api/reports/fault-trends "
                        + "period={}",
                period);
        ReportResponseDTO response =
                reportService.getFaultTrends(
                        period, startDate, endDate,
                        opmcGuard.resolveOpmcFilter(callerId));
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/reports/technician-performance
     */
    @GetMapping("/technician-performance")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO>
    getTechnicianPerformance(
            @RequestParam(
                    defaultValue = "THIS_MONTH")
            String period,
            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,
            @RequestParam(required = false)
            String technicianId,
            @AuthenticationPrincipal Long callerId) {

        log.info(
                "GET /api/reports/"
                        + "technician-performance "
                        + "period={}",
                period);
        ReportResponseDTO response =
                reportService
                        .getTechnicianPerformance(
                                period,
                                startDate,
                                endDate,
                                technicianId,
                                opmcGuard.resolveOpmcFilter(callerId));
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/reports/financial-summary
     */
    @GetMapping("/financial-summary")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO>
    getFinancialSummary(
            @RequestParam(
                    defaultValue = "THIS_MONTH")
            String period,
            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,
            @AuthenticationPrincipal Long callerId) {

        log.info(
                "GET /api/reports/financial-summary "
                        + "period={}",
                period);
        ReportResponseDTO response =
                reportService.getFinancialSummary(
                        period, startDate, endDate,
                        opmcGuard.resolveOpmcFilter(callerId));
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/reports/customer-satisfaction
     */
    @GetMapping("/customer-satisfaction")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO>
    getCustomerSatisfaction(
            @RequestParam(
                    defaultValue = "THIS_MONTH")
            String period,
            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(
                    iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,
            @AuthenticationPrincipal Long callerId) {

        log.info(
                "GET /api/reports/"
                        + "customer-satisfaction "
                        + "period={}",
                period);
        ReportResponseDTO response =
                reportService.getCustomerSatisfaction(
                        period, startDate, endDate,
                        opmcGuard.resolveOpmcFilter(callerId));
        return ResponseEntity.ok(response);
    }

    /** GET /api/reports/daily-fault-summary — REP-01 */
    @GetMapping("/daily-fault-summary")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO> getDailyFaultSummary(
            @RequestParam(defaultValue = "TODAY") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal Long callerId) {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_DAILY_FAULT_SUMMARY)
                .period(period).startDate(startDate).endDate(endDate)
                .callerOpmcId(opmcGuard.resolveOpmcFilter(callerId)).build();
        return ResponseEntity.ok(reportService.getDailyFaultSummary(req));
    }

    /** GET /api/reports/fault-aging — REP-03 */
    @GetMapping("/fault-aging")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO> getFaultAging(
            @RequestParam(required = false) String priority,
            @AuthenticationPrincipal Long callerId) {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_FAULT_AGING)
                .priority(priority)
                .callerOpmcId(opmcGuard.resolveOpmcFilter(callerId)).build();
        return ResponseEntity.ok(reportService.getFaultAging(req));
    }

    /** GET /api/reports/material-cost — REP-05 */
    @GetMapping("/material-cost")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO> getMaterialCostReport(
            @RequestParam(defaultValue = "THIS_MONTH") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal Long callerId) {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_MATERIAL_COST)
                .period(period).startDate(startDate).endDate(endDate)
                .callerOpmcId(opmcGuard.resolveOpmcFilter(callerId)).build();
        return ResponseEntity.ok(reportService.getMaterialCostReport(req));
    }

    /**
     * GET /api/reports/ai-forecast — REP-06. Stage F #2 investigation: proxies
     * slt-ai-module's /api/ai/predictions, which has no OPMC dimension at all — deliberately
     * left unscoped, not an oversight. Scoping it would be an ML redesign (per-OPMC models or
     * per-OPMC training-data filtering), out of scope for this fix.
     */
    @GetMapping("/ai-forecast")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO> getAiForecastReport(
            @RequestParam(required = false, defaultValue = "30") Integer horizonDays) {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_AI_FORECAST)
                .horizonDays(horizonDays).build();
        return ResponseEntity.ok(reportService.getAiForecastReport(req));
    }

    /**
     * GET /api/reports/geographic-demand — REP-07. Same as ai-forecast: proxies
     * slt-ai-module's /api/ai/clusters, no OPMC dimension exists there — deliberately unscoped.
     */
    @GetMapping("/geographic-demand")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO> getGeographicDemandReport() {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_GEOGRAPHIC_DEMAND).build();
        return ResponseEntity.ok(reportService.getGeographicDemandReport(req));
    }

    /** GET /api/reports/kpi-performance — REP-08 */
    @GetMapping("/kpi-performance")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO> getKpiPerformanceReport(
            @RequestParam(defaultValue = "MONTHLY") String period,
            @AuthenticationPrincipal Long callerId) {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_KPI_PERFORMANCE)
                .period(period)
                .callerOpmcId(opmcGuard.resolveOpmcFilter(callerId)).build();
        return ResponseEntity.ok(reportService.getKpiPerformanceReport(req));
    }

    /**
     * GET /api/reports/attendance — REP-09. Stage F #2 — the opmcId filter used to be a plain
     * client-supplied request param; it's now always the caller's own OPMC (or unscoped for
     * Super Admin), resolved server-side.
     */
    @GetMapping("/attendance")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO> getAttendanceReport(
            @RequestParam(defaultValue = "THIS_MONTH") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal Long callerId) {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_ATTENDANCE)
                .period(period).startDate(startDate).endDate(endDate)
                .callerOpmcId(opmcGuard.resolveOpmcFilter(callerId)).build();
        return ResponseEntity.ok(reportService.getAttendanceReport(req));
    }

    /** GET /api/reports/audit-trail — REP-10 (Stage F #3, same root cause as Stage F #2) */
    @GetMapping("/audit-trail")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ReportResponseDTO> getAuditTrailReport(
            @RequestParam(defaultValue = "THIS_MONTH") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String entityType,
            @AuthenticationPrincipal Long callerId) {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_AUDIT_TRAIL)
                .period(period).startDate(startDate).endDate(endDate).entityType(entityType)
                .callerOpmcId(opmcGuard.resolveOpmcFilter(callerId)).build();
        return ResponseEntity.ok(reportService.getAuditTrailReport(req));
    }

    // â”€â”€â”€ Export Endpoints â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * POST /api/reports/export/pdf
     * Body: ReportRequestDTO
     * Returns: PDF file download
     */
    @PostMapping("/export/pdf")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<byte[]> exportPdf(
            @Valid @RequestBody
            ReportRequestDTO request,
            @AuthenticationPrincipal Long callerId)
            throws IOException {

        log.info(
                "POST /api/reports/export/pdf "
                        + "type={}",
                request.getReportType());

        request.setFormat(ReportRequestDTO.FORMAT_PDF);
        // Stage F #2 — never trust callerOpmcId off the request body; the client can set
        // arbitrary fields on this @RequestBody, so it's always overwritten with the
        // server-resolved value, same as every GET endpoint above.
        request.setCallerOpmcId(opmcGuard.resolveOpmcFilter(callerId));
        byte[] pdfBytes =
                reportService.exportPdf(request);

        String filename = buildFilename(
                request.getReportType(),
                "pdf");

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + filename + "\"")
                .contentType(
                        MediaType.APPLICATION_PDF)
                .contentLength(pdfBytes.length)
                .body(pdfBytes);
    }

    /**
     * POST /api/reports/export/excel
     * Body: ReportRequestDTO
     * Returns: Excel file download
     */
    @PostMapping("/export/excel")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<byte[]> exportExcel(
            @Valid @RequestBody
            ReportRequestDTO request,
            @AuthenticationPrincipal Long callerId)
            throws IOException {

        log.info(
                "POST /api/reports/export/excel "
                        + "type={}",
                request.getReportType());

        request.setFormat(
                ReportRequestDTO.FORMAT_EXCEL);
        request.setCallerOpmcId(opmcGuard.resolveOpmcFilter(callerId));
        byte[] excelBytes =
                reportService.exportExcel(request);

        String filename = buildFilename(
                request.getReportType(),
                "xlsx");

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd"
                                + ".openxmlformats"
                                + "-officedocument"
                                + ".spreadsheetml"
                                + ".sheet"))
                .contentLength(excelBytes.length)
                .body(excelBytes);
    }

    /**
     * POST /api/reports/export/csv
     * Body: ReportRequestDTO
     * Returns: CSV file download
     */
    @PostMapping("/export/csv")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<byte[]> exportCsv(
            @Valid @RequestBody
            ReportRequestDTO request,
            @AuthenticationPrincipal Long callerId)
            throws IOException {

        log.info(
                "POST /api/reports/export/csv "
                        + "type={}",
                request.getReportType());

        request.setFormat(ReportRequestDTO.FORMAT_CSV);
        request.setCallerOpmcId(opmcGuard.resolveOpmcFilter(callerId));
        byte[] csvBytes =
                reportService.exportCsv(request);

        String filename = buildFilename(
                request.getReportType(),
                "csv");

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + filename + "\"")
                .contentType(
                        MediaType.parseMediaType("text/csv"))
                .contentLength(csvBytes.length)
                .body(csvBytes);
    }

    // â”€â”€â”€ Utility â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private String buildFilename(
            String reportType,
            String extension) {
        String type = reportType
                .toLowerCase()
                .replace("_", "-");
        String timestamp =
                LocalDateTime.now().format(FILE_FMT);
        return "slt-" + type
                + "-" + timestamp
                + "." + extension;
    }
}
