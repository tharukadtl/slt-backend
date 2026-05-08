package lk.slt.fieldops.controller;

import lk.slt.fieldops.dto.DashboardDTO;
import lk.slt.fieldops.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost
        .PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor

public class DashboardController {

    private final DashboardService dashboardService;

    // â”€â”€â”€ GET /api/dashboard/kpi-summary â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /**
     * Returns 4 main KPI cards for admin dashboard:
     * - Total Faults
     * - Open Faults
     * - Completed Today
     * - Pending Payments
     * Plus additional metrics.
     */
    @GetMapping("/kpi-summary")
    @PreAuthorize("hasAnyRole("
            + "'ADMIN','SUPER_ADMIN')")
    public ResponseEntity<DashboardDTO.KpiSummaryDTO> getKpiSummary() {
        log.info(
                "GET /api/dashboard/kpi-summary");
        return ResponseEntity.ok(
                dashboardService.getKpiSummary());
    }

    // â”€â”€â”€ GET /api/dashboard/fault-distribution â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /**
     * Returns fault counts by status for
     * pie chart in admin dashboard.
     * Also includes breakdown by category
     * and priority.
     */
    @GetMapping("/fault-distribution")
    @PreAuthorize("hasAnyRole("
            + "'ADMIN','SUPER_ADMIN')")
    public ResponseEntity<DashboardDTO.FaultDistributionDTO> getFaultDistribution() {
        log.info(
                "GET /api/dashboard/"
                        + "fault-distribution");
        return ResponseEntity.ok(
                dashboardService
                        .getFaultDistribution());
    }

    // â”€â”€â”€ GET /api/dashboard/fault-trends â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /**
     * Returns daily fault data for line chart.
     * Query param: days = 7 | 14 | 30 | 90
     * Default: 30 days
     *
     * Used for the trend line chart in
     * admin dashboard.
     */
    @GetMapping("/fault-trends")
    @PreAuthorize("hasAnyRole("
            + "'ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<DashboardDTO.FaultTrendPointDTO>> getFaultTrends(
            @RequestParam(defaultValue = "30")
            int days) {
        log.info(
                "GET /api/dashboard/fault-trends"
                        + " days={}",
                days);

        // Limit to max 90 days
        int safeDays = Math.min(days, 90);
        return ResponseEntity.ok(
                dashboardService
                        .getFaultTrends(safeDays));
    }

    // â”€â”€â”€ GET /api/dashboard/technician-performance â”€â”€â”€â”€â”€â”€â”€â”€
    /**
     * Returns technician performance table
     * for admin dashboard.
     * Sorted by completion rate descending.
     * Used for the sortable performance table.
     */
    @GetMapping("/technician-performance")
    @PreAuthorize("hasAnyRole("
            + "'ADMIN','SUPER_ADMIN',"
            + "'TEAM_LEAD')")
    public ResponseEntity<List<DashboardDTO.TechnicianPerformanceDTO>> getTechnicianPerformance() {
        log.info(
                "GET /api/dashboard/"
                        + "technician-performance");
        return ResponseEntity.ok(
                dashboardService
                        .getTechnicianPerformance());
    }

    // â”€â”€â”€ GET /api/dashboard/recent-activity â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /**
     * Returns last N system activities
     * for the activity feed sidebar.
     * Query param: limit = 10 | 20 (default 20)
     */
    @GetMapping("/recent-activity")
    @PreAuthorize("hasAnyRole("
            + "'ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<DashboardDTO.ActivityItemDTO>> getRecentActivity(
            @RequestParam(defaultValue = "20")
            int limit) {
        log.info(
                "GET /api/dashboard/"
                        + "recent-activity "
                        + "limit={}",
                limit);

        // Limit to max 50
        int safeLimit = Math.min(limit, 50);
        return ResponseEntity.ok(
                dashboardService
                        .getRecentActivity(
                                safeLimit));
    }

    // â”€â”€â”€ GET /api/dashboard/geographic-data â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /**
     * Returns GPS coordinates for:
     * - Fault heat map points
     * - Active technician locations
     * - Regional boundaries with fault density
     *
     * Used for the geographic heat map in
     * admin dashboard.
     */
    @GetMapping("/geographic-data")
    @PreAuthorize("hasAnyRole("
            + "'ADMIN','SUPER_ADMIN')")
    public ResponseEntity<DashboardDTO.GeographicDataDTO> getGeographicData() {
        log.info(
                "GET /api/dashboard/"
                        + "geographic-data");
        return ResponseEntity.ok(
                dashboardService
                        .getGeographicData());
    }

    // â”€â”€â”€ GET /api/dashboard/summary â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /**
     * Single endpoint that returns ALL
     * dashboard data in one call.
     * Useful for initial page load to reduce
     * number of HTTP requests.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole("
            + "'ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>>
    getFullDashboardSummary() {
        log.info(
                "GET /api/dashboard/summary");

        Map<String, Object> summary =
                new java.util.LinkedHashMap<>();

        summary.put("kpiSummary",
                dashboardService.getKpiSummary());
        summary.put("faultDistribution",
                dashboardService
                        .getFaultDistribution());
        summary.put("faultTrends",
                dashboardService
                        .getFaultTrends(30));
        summary.put("technicianPerformance",
                dashboardService
                        .getTechnicianPerformance());
        summary.put("recentActivity",
                dashboardService
                        .getRecentActivity(20));
        summary.put("geographicData",
                dashboardService
                        .getGeographicData());

        return ResponseEntity.ok(summary);
    }
}
