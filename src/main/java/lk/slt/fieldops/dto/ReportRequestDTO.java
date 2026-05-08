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
    private String branchId;
    private String category;
    private String status;

    // Optional column selection
    private List<String> columns;

    // Report title override
    private String title;
}