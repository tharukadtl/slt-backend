package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportRequestDTO {

    // Report types
    public static final String TYPE_FAULT_TRENDS =
            "FAULT_TRENDS";
    public static final String TYPE_TECHNICIAN_PERFORMANCE =
            "TECHNICIAN_PERFORMANCE";
    public static final String TYPE_FINANCIAL_SUMMARY =
            "FINANCIAL_SUMMARY";
    public static final String TYPE_CUSTOMER_SATISFACTION =
            "CUSTOMER_SATISFACTION";
    public static final String TYPE_INVENTORY =
            "INVENTORY";
    public static final String TYPE_ATTENDANCE =
            "ATTENDANCE";

    // REP-01..REP-10 (spec §6 Required Reports)
    public static final String TYPE_DAILY_FAULT_SUMMARY   = "DAILY_FAULT_SUMMARY";   // REP-01
    // TYPE_TECHNICIAN_PERFORMANCE above covers                                          REP-02
    public static final String TYPE_FAULT_AGING            = "FAULT_AGING";           // REP-03
    // TYPE_FINANCIAL_SUMMARY above covers                                              REP-04
    public static final String TYPE_MATERIAL_COST          = "MATERIAL_COST";         // REP-05
    public static final String TYPE_AI_FORECAST            = "AI_FORECAST";           // REP-06
    public static final String TYPE_GEOGRAPHIC_DEMAND      = "GEOGRAPHIC_DEMAND";     // REP-07
    public static final String TYPE_KPI_PERFORMANCE        = "KPI_PERFORMANCE";       // REP-08
    // TYPE_ATTENDANCE above covers                                                     REP-09
    public static final String TYPE_AUDIT_TRAIL            = "AUDIT_TRAIL";           // REP-10

    // Export formats
    public static final String FORMAT_PDF = "PDF";
    public static final String FORMAT_EXCEL = "EXCEL";
    public static final String FORMAT_CSV = "CSV";

    // Period presets
    public static final String PERIOD_TODAY = "TODAY";
    public static final String PERIOD_THIS_WEEK =
            "THIS_WEEK";
    public static final String PERIOD_THIS_MONTH =
            "THIS_MONTH";
    public static final String PERIOD_LAST_MONTH =
            "LAST_MONTH";
    public static final String PERIOD_CUSTOM = "CUSTOM";

    @NotNull(message = "Report type is required")
    private String reportType;

    @NotNull(message = "Export format is required")
    private String format;

    private String period;

    private LocalDate startDate;

    private LocalDate endDate;

    // Optional filters
    private String technicianId;
    private String teamId;
    private String category;
    private String status;

    // Stage F #2/#3 — resolved server-side via OpmcAccessGuard.resolveOpmcFilter(callerId),
    // null meaning unscoped (Super Admin). Never set from client input — the export endpoints
    // bind a client-supplied ReportRequestDTO body directly, so the controller always
    // overwrites this field itself after binding rather than trusting whatever the client sent.
    private Long callerOpmcId;

    // Additional filters used by REP-01..REP-10
    private String priority;
    private String region;
    private String workgroupId;
    private String actorId;
    private String entityType;
    private Integer horizonDays;   // REP-06 forecast horizon

    // Optional column selection
    private List<String> columns;

    // Report title override
    private String title;
}