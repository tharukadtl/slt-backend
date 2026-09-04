package lk.slt.fieldops.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import lk.slt.fieldops.dto.ReportRequestDTO;
import lk.slt.fieldops.dto.ReportResponseDTO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ANA-010 (09_ANALYTICS, FR-23) — {@code ReportService}'s date-range handling: a valid range
 * proceeds, an inverted range is rejected, and an absent range falls back to a sane default.
 *
 * <p><b>Type correction.</b> The row's {@code InvalidDateRangeException} does not exist anywhere in
 * this codebase — {@code shared/exception} contains only {@code DuplicateSessionException},
 * {@code InvalidRefreshTokenException} and {@code ResourceNotFoundException}. The test therefore
 * asserts that <i>some</i> exception is raised rather than naming a type that cannot be imported,
 * the same approach {@code LocationServiceTest} already takes for FAULT-018's non-existent
 * {@code InvalidLocationException}.</p>
 *
 * <p><b>Method correction.</b> There is no {@code reportService.generate(start, end)}. The range is
 * resolved by the private {@code resolveDateRange(ReportRequestDTO)}, which every report method
 * funnels through; it is driven here through the real public entry point
 * {@code getDailyFaultSummary(ReportRequestDTO)} (REP-01), so the assertion covers the path a
 * request actually takes rather than a helper in isolation.</p>
 *
 * <p>{@code @SpringBootTest} because {@code ReportService} has eleven collaborators including a
 * {@code RestTemplate} and {@code KpiCalculationService}; mocking them all would test the mock
 * rather than the resolver. Requires {@code SPRING_PROFILES_ACTIVE=local}. {@code @Transactional}
 * — this test only reads, but keeps the module's convention.</p>
 */
@SpringBootTest
@Transactional
class ReportServiceTest {

    @Autowired private ReportService reportService;

    private ReportRequestDTO request(String period, LocalDate start, LocalDate end) {
        return ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_DAILY_FAULT_SUMMARY)
                .period(period)
                .startDate(start)
                .endDate(end)
                .build();
    }

    @Test
    void invertedDateRange_throwsInvalidDateRange() {
        List<String> gaps = new ArrayList<>();

        // ── Step 1: a valid range proceeds ──────────────────────────────────────
        ReportResponseDTO valid = reportService.getDailyFaultSummary(
                request(ReportRequestDTO.PERIOD_CUSTOM,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        assertNotNull(valid, "A valid range must produce a report.");
        assertEquals("2026-01-01", valid.getStartDate(),
            "The report must echo the requested start date. Report: " + valid);
        assertEquals("2026-01-31", valid.getEndDate(),
            "The report must echo the requested end date. Report: " + valid);
        assertNotNull(valid.getData(), "A valid range must produce a data block.");
        assertTrue(valid.getTotalRecords() >= 0,
            "A valid range must produce a non-negative record count. Report: " + valid);

        // ── Step 2: an inverted range must be rejected ──────────────────────────
        // 2026-03-01 .. 2026-01-01 — the end is two months before the start.
        ReportResponseDTO invertedResult = null;
        Throwable raised = null;
        try {
            invertedResult = reportService.getDailyFaultSummary(
                    request(ReportRequestDTO.PERIOD_CUSTOM,
                            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 1, 1)));
        } catch (Throwable t) {
            raised = t;
        }

        if (raised == null) {
            gaps.add("Inverted range 2026-03-01..2026-01-01 was accepted silently:"
                + " no exception, HTTP-visible report returned with startDate="
                + invertedResult.getStartDate() + " endDate=" + invertedResult.getEndDate()
                + " totalRecords=" + invertedResult.getTotalRecords()
                + " — ReportService.resolveDateRange performs no validation of any kind"
                + " (it returns new LocalDate[]{start, end} unchecked), and there is no"
                + " InvalidDateRangeException type in shared/exception. Every report method"
                + " funnels through this resolver, so an operator who picks an end date before"
                + " the start date gets a confidently-rendered empty report rather than an error.");
        } else {
            assertFalse(raised instanceof NullPointerException,
                "An inverted range must be rejected deliberately, not by an incidental NPE."
                    + " Got: " + raised);
        }

        // ── Step 3: an absent range defaults ────────────────────────────────────
        // Through the real HTTP surface this never reaches the service as null — every
        // ReportController route declares @RequestParam(defaultValue = "THIS_MONTH") (or "TODAY"
        // for REP-01) — so the spec'd "defaults to current month" behaviour is asserted on the
        // preset the controller actually supplies.
        ReportResponseDTO thisMonth = reportService.getDailyFaultSummary(
                request(ReportRequestDTO.PERIOD_THIS_MONTH, null, null));
        assertEquals(LocalDate.now().withDayOfMonth(1).toString(), thisMonth.getStartDate(),
            "period=THIS_MONTH must start on the first of the current month. Report: " + thisMonth);
        assertEquals(LocalDate.now().toString(), thisMonth.getEndDate(),
            "period=THIS_MONTH must end today. Report: " + thisMonth);

        // A fully absent range (no period, no dates) must still produce a usable, non-inverted
        // window rather than nulls. Note for the record: it resolves to the last 30 days
        // (now-30 .. now), not the current month — a difference from the row's wording that is not
        // reachable through the API because of the controller defaults asserted above.
        ReportResponseDTO defaulted = reportService.getDailyFaultSummary(
                request(null, null, null));
        assertNotNull(defaulted.getStartDate(),
            "An absent range must still resolve to a concrete start date. Report: " + defaulted);
        assertNotNull(defaulted.getEndDate(),
            "An absent range must still resolve to a concrete end date. Report: " + defaulted);
        assertFalse(LocalDate.parse(defaulted.getStartDate())
                        .isAfter(LocalDate.parse(defaulted.getEndDate())),
            "The default window must not itself be inverted. Report: " + defaulted);

        assertTrue(gaps.isEmpty(),
            "Report date-range validation gaps:\n  - " + String.join("\n  - ", gaps));
    }
}
