package lk.slt.fieldops.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import lk.slt.fieldops.repository.CheckInOutRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.KpiScoreRepository;
import lk.slt.fieldops.repository.KpiTargetRepository;
import lk.slt.fieldops.repository.PaymentRepository;
import lk.slt.fieldops.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOB-018 (03_JOB_LIFECYCLE, FR-7), KPI-001 and KPI-008 (06_KPI_PERFORMANCE, FR-16) — the KPI
 * overall-score formula must be the documented weighted sum: completion 35%, satisfaction 25%,
 * on-time 20%, response 10%, attendance 10%, and its result must always be a percentage in
 * [0, 100].
 *
 * <p><b>Method naming and access.</b> The sheet calls
 * {@code kpiService.calculateScore(completion, satisfaction, onTime, response, attendance)}. The
 * real method is {@code KpiCalculationService.calculateOverallScore(...)} and it is
 * {@code private}, so it is invoked through {@link ReflectionTestUtils} (already on the classpath
 * via {@code spring-boot-starter-test}). Reflection is used deliberately rather than asking for the
 * method to be widened — this suite does not modify production code — and it still exercises the
 * exact arithmetic the score depends on.</p>
 *
 * <p><b>Input scales.</b> The sheet passes fractions (0.90, 0.85, 0.88, 0.80, 1.0) and expects
 * 88.35. The implementation's parameters are on their real domain scales, not fractions:
 * <ul>
 *   <li>{@code completionRate} — percent (0-100)</li>
 *   <li>{@code satisfaction} — a 0-5 star rating, internally rescaled to 0-100</li>
 *   <li>{@code onTimeRate} — percent (0-100)</li>
 *   <li>{@code avgResponseTime} — <b>minutes</b>, internally scored as
 *       {@code max(0, 100 - (minutes - 10) * 2)} (10 min = 100, 60 min = 0)</li>
 *   <li>{@code attendanceRate} — percent (0-100)</li>
 * </ul>
 * The sheet's five component scores are therefore expressed on those scales below (0.85
 * satisfaction &rarr; 4.25 stars &rarr; 85; 0.80 response &rarr; 20 minutes &rarr; 80). The
 * expected result is unchanged: 90(.35) + 85(.25) + 88(.20) + 80(.10) + 100(.10) = <b>88.35</b>.</p>
 */
@ExtendWith(MockitoExtension.class)
class KpiServiceTest {

    @Mock private UserRepository       userRepository;
    @Mock private JobRepository        jobRepository;
    @Mock private PaymentRepository    paymentRepository;
    @Mock private KpiTargetRepository  kpiTargetRepository;
    @Mock private KpiScoreRepository   kpiScoreRepository;
    @Mock private CheckInOutRepository checkInOutRepository;

    @InjectMocks private KpiCalculationService kpiService;

    /** The five documented weights, mirrored here so a silent re-weighting in production fails. */
    private static final double W_COMPLETION   = 0.35;
    private static final double W_SATISFACTION = 0.25;
    private static final double W_ON_TIME      = 0.20;
    private static final double W_RESPONSE     = 0.10;
    private static final double W_ATTENDANCE   = 0.10;

    private double score(double completionPct, double satisfactionStars, double onTimePct,
                         double responseMinutes, double attendancePct) {
        Double result = ReflectionTestUtils.invokeMethod(kpiService, "calculateOverallScore",
            completionPct, satisfactionStars, onTimePct, responseMinutes, attendancePct);
        assertNotNull(result, "calculateOverallScore must return a value");
        return result;
    }

    @Test
    void weightedFormula_correctScore() {
        // ── Steps 1-3: the sheet's five component scores, on the implementation's scales ──
        // completion 90% | satisfaction 4.25/5 (=85) | on-time 88% | response 20 min (=80)
        // | attendance 100%
        double actual = score(90.0, 4.25, 88.0, 20.0, 100.0);

        assertEquals(88.35, actual, 0.01,
            "0.90x0.35 + 0.85x0.25 + 0.88x0.20 + 0.80x0.10 + 1.00x0.10 = 88.35");

        // Each weight in isolation: only one component non-zero at a time, so a swapped or
        // mistyped weight is caught individually rather than hiding inside the total above.
        assertAll("individual component weights",
            () -> assertEquals(100 * W_COMPLETION,   score(100, 0, 0, 60, 0), 0.01,
                "completion must carry 35% of the score"),
            () -> assertEquals(100 * W_SATISFACTION, score(0, 5.0, 0, 60, 0), 0.01,
                "satisfaction (5/5 stars = 100) must carry 25% of the score"),
            () -> assertEquals(100 * W_ON_TIME,      score(0, 0, 100, 60, 0), 0.01,
                "on-time must carry 20% of the score"),
            () -> assertEquals(100 * W_RESPONSE,     score(0, 0, 0, 10, 0), 0.01,
                "response time (10 min = a perfect 100) must carry 10% of the score"),
            () -> assertEquals(100 * W_ATTENDANCE,   score(0, 0, 0, 60, 100), 0.01,
                "attendance must carry 10% of the score")
        );

        // The five weights sum to 1.0, so a perfect scorecard is exactly 100 and a blank one 0.
        assertEquals(100.0, score(100, 5.0, 100, 10, 100), 0.01,
            "A perfect scorecard must total exactly 100 — the weights must sum to 1.0");
        assertEquals(0.0, score(0, 0, 0, 60, 0), 0.01,
            "A zero scorecard (with response at the 60-min floor) must total 0");

        // Result is clamped to 0..100 and never leaks a negative response penalty.
        assertEquals(0.0, score(0, 0, 0, 999, 0), 0.01,
            "A pathological response time must floor at 0, not go negative");
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // KPI-001 (06_KPI_PERFORMANCE, FR-16) — the five-component weighted formula yields 88.35
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The row's own worked example, asserted end to end:
     * {@code 0.90x0.35 + 0.85x0.25 + 0.88x0.20 + 0.80x0.10 + 1.00x0.10
     * = 0.315 + 0.2125 + 0.176 + 0.08 + 0.10 = 88.35}.
     *
     * <p>This overlaps deliberately with {@link #weightedFormula_correctScore()} above (written for
     * JOB-018, which probes the same formula from the job-lifecycle side). KPI-001 maps by name to
     * this class, so the row gets its own named method rather than being silently pointed at an
     * existing one; the per-weight isolation checks are left to JOB-018's method and are not
     * repeated here.</p>
     *
     * <p><b>Input-scale correction.</b> The row passes fractions. The real
     * {@code KpiCalculationService.calculateOverallScore} takes domain scales — completion/on-time/
     * attendance as percents, satisfaction as 0-5 stars, and response time as <b>minutes</b>
     * (scored {@code max(0, 100 - (minutes - 10) * 2)}). The row's 0.85 satisfaction is therefore
     * 4.25 stars and its 0.80 response component is 20 minutes. The arithmetic and the expected
     * 88.35 are unchanged.</p>
     */
    @Test
    void weightedFormula_5Components_88point35() {
        // ── Step 1: the five components, on the implementation's real scales ─────────────────
        double completionPct   = 90.0;   // 0.90
        double satisfaction    = 4.25;   // 0.85 of a 5-star scale
        double onTimePct       = 88.0;   // 0.88
        double responseMinutes = 20.0;   // 0.80 -> 100 - (20-10)*2 = 80
        double attendancePct   = 100.0;  // 1.00

        double actual = score(completionPct, satisfaction, onTimePct, responseMinutes,
            attendancePct);

        // ── Steps 2-3: the expected total, computed the way the row spells it out ────────────
        double expected = (0.90 * W_COMPLETION
                         + 0.85 * W_SATISFACTION
                         + 0.88 * W_ON_TIME
                         + 0.80 * W_RESPONSE
                         + 1.00 * W_ATTENDANCE) * 100;

        assertEquals(88.35, expected, 0.0001,
            "Sanity: the row's own arithmetic must come to 88.35");

        // ── Step 4 ──────────────────────────────────────────────────────────────────────────
        assertEquals(88.35, actual, 0.01,
            "0.315 + 0.2125 + 0.176 + 0.08 + 0.10 = 88.35, but calculateOverallScore returned "
                + actual);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // KPI-008 (06_KPI_PERFORMANCE, FR-16) — the overall score never leaves [0, 100]
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * KPI-008 — whatever the component inputs, the published score must stay inside [0, 100].
     *
     * <p>Three cases, matching the row's three steps: every component at its best, every component
     * at its worst, and a combination whose raw weighted sum genuinely exceeds 100 so the clamp is
     * actually exercised rather than merely present.</p>
     *
     * <p><b>Why a raw sum above 100 is reachable.</b> The response-time sub-score is
     * {@code max(0, 100 - (minutes - 10) * 2)} — floored at 0 but <b>not</b> capped at 100, so a
     * 0-minute response scores 120. With every other component perfect the raw sum is
     * {@code 35 + 25 + 20 + 12 + 10 = 102}, which the final {@code Math.min(100, ...)} must bring
     * back to 100.</p>
     */
    @Test
    void scoreAlwaysInZeroTo100Range() {
        // ── Step 1: all components at maximum ───────────────────────────────────────────────
        double best = score(100.0, 5.0, 100.0, 10.0, 100.0);
        // ── Step 2: all components at minimum (60 min response = a 0 response sub-score) ────
        double worst = score(0.0, 0.0, 0.0, 60.0, 0.0);
        // ── Step 3: a raw weighted sum of 102, which must be clamped ────────────────────────
        double overflow = score(100.0, 5.0, 100.0, 0.0, 100.0);
        // Pathological inputs from both directions.
        double absurdlySlow = score(0.0, 0.0, 0.0, 9999.0, 0.0);
        double negatives    = score(-500.0, -5.0, -500.0, 9999.0, -500.0);

        assertAll("the overall score is always a percentage in [0, 100]",
            () -> assertTrue(best <= 100.0,
                "A perfect scorecard must not exceed 100, was " + best),
            () -> assertTrue(best >= 0.0,
                "A perfect scorecard must not be negative, was " + best),
            () -> assertEquals(100.0, best, 0.01,
                "A perfect scorecard must be exactly 100 - the five weights sum to 1.0"),

            () -> assertTrue(worst >= 0.0,
                "A blank scorecard must not be negative, was " + worst),
            () -> assertEquals(0.0, worst, 0.01,
                "A blank scorecard must be exactly 0, was " + worst),

            () -> assertEquals(100.0, overflow, 0.01,
                "A raw weighted sum of 102 (response time 0 min scores 120, uncapped) must be "
                    + "clamped to 100, was " + overflow),

            () -> assertTrue(absurdlySlow >= 0.0 && absurdlySlow <= 100.0,
                "A 9999-minute response time must not push the score out of range, was "
                    + absurdlySlow),
            () -> assertTrue(negatives >= 0.0 && negatives <= 100.0,
                "Negative component inputs must not produce a negative score, was " + negatives)
        );
    }
}
