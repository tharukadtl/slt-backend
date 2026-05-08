package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.ReportRequestDTO;
import lk.slt.fieldops.dto.ReportResponseDTO;
import lk.slt.fieldops.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
            LocalDate endDate) {

        log.info(
                "GET /api/reports/fault-trends "
                        + "period={}",
                period);
        ReportResponseDTO response =
                reportService.getFaultTrends(
                        period, startDate, endDate);
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
            String technicianId) {

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
                                endDate);
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
            LocalDate endDate) {

        log.info(
                "GET /api/reports/financial-summary "
                        + "period={}",
                period);
        ReportResponseDTO response =
                reportService.getFinancialSummary(
                        period, startDate, endDate);
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
            LocalDate endDate) {

        log.info(
                "GET /api/reports/"
                        + "customer-satisfaction "
                        + "period={}",
                period);
        ReportResponseDTO response =
                reportService.getCustomerSatisfaction(
                        period, startDate, endDate);
        return ResponseEntity.ok(response);
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
            ReportRequestDTO request)
            throws IOException {

        log.info(
                "POST /api/reports/export/pdf "
                        + "type={}",
                request.getReportType());

        request.setFormat(ReportRequestDTO.FORMAT_PDF);
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
            ReportRequestDTO request)
            throws IOException {

        log.info(
                "POST /api/reports/export/excel "
                        + "type={}",
                request.getReportType());

        request.setFormat(
                ReportRequestDTO.FORMAT_EXCEL);
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
