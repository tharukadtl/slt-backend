package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.ReportRequestDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Auto-generates the core reports on a schedule and drops them into
 * app.reports.export-dir as PDFs. No SMTP is wired up yet, so the
 * "auto-email to ops team" step is a logged placeholder.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledReportService {

    private final ReportService reportService;

    @Value("${app.reports.export-dir:temp/reports}")
    private String exportDir;

    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Daily fault summary (REP-01) — every day at 23:59. */
    @Scheduled(cron = "0 59 23 * * *")
    public void generateDailyReport() {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_DAILY_FAULT_SUMMARY)
                .period(ReportRequestDTO.PERIOD_TODAY)
                .build();
        generateAndSave(req, "daily-fault-summary");
    }

    /** Technician performance (REP-02) — every Monday at 00:05 (previous week). */
    @Scheduled(cron = "0 5 0 * * MON")
    public void generateWeeklyReport() {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_TECHNICIAN_PERFORMANCE)
                .period(ReportRequestDTO.PERIOD_THIS_WEEK)
                .build();
        generateAndSave(req, "weekly-technician-performance");
    }

    /** Financial summary (REP-04) — 1st of every month at 00:10 (previous month). */
    @Scheduled(cron = "0 10 0 1 * *")
    public void generateMonthlyReport() {
        ReportRequestDTO req = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_FINANCIAL_SUMMARY)
                .period(ReportRequestDTO.PERIOD_LAST_MONTH)
                .build();
        generateAndSave(req, "monthly-financial-summary");
    }

    private void generateAndSave(ReportRequestDTO req, String label) {
        try {
            byte[] pdf = reportService.exportPdf(req);
            Path dir = Path.of(exportDir);
            Files.createDirectories(dir);
            String filename = "slt-" + label + "-" + LocalDateTime.now().format(FILE_FMT) + ".pdf";
            Path file = dir.resolve(filename);
            Files.write(file, pdf);
            log.info("Scheduled report generated: {} ({} bytes)", file, pdf.length);
            // No SMTP integration yet — this is where the report would be emailed to the ops team.
            log.info("Would auto-email '{}' to ops team (SMTP not configured)", filename);
        } catch (IOException e) {
            log.error("Failed to generate scheduled report '{}': {}", label, e.getMessage());
        }
    }
}
