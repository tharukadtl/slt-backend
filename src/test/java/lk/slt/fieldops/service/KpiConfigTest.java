package lk.slt.fieldops.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KPI-012 (06_KPI_PERFORMANCE, FR-16) — the five KPI component weights must sum to exactly 1.0, so
 * that a perfect scorecard is 100 and no component is silently over- or under-counted.
 *
 * <p><b>Where the weights actually live.</b> The row names a {@code KpiConfig} class holding
 * {@code KpiWeights.COMPLETION}, {@code .SATISFACTION}, {@code .ON_TIME}, {@code .RESPONSE} and
 * {@code .ATTENDANCE}. Neither {@code KpiConfig} nor {@code KpiWeights} exists in this codebase —
 * the weights are five {@code private static final double} constants on
 * {@link KpiCalculationService} ({@code WEIGHT_COMPLETION}, {@code WEIGHT_SATISFACTION},
 * {@code WEIGHT_ON_TIME}, {@code WEIGHT_RESPONSE_TIME}, {@code WEIGHT_ATTENDANCE}). This test keeps
 * the row's mapped class name but reads the real constants reflectively, via
 * {@link ReflectionTestUtils} (already on the classpath through {@code spring-boot-starter-test}).
 * Reflection is used deliberately instead of asking for the fields to be widened or extracted into
 * a config class — this suite does not modify production code — and it still fails loudly if a
 * weight is renamed, retyped or re-valued. The class is placed alongside
 * {@link KpiServiceTest} in {@code lk.slt.fieldops.service} because that is where the constants
 * under test live; there is no {@code config} class to sit next to.</p>
 *
 * <p><b>Cross-check.</b> The same five percentages are printed to the user on the Admin portal's
 * KPI page ("Completion 35% · Satisfaction 25% · On-Time 20% · Response 10% · Attendance 10%,
 * score weights per SRS §5.3.4", {@code frontend-admin/src/pages/KPI/KpiPage.js}), so the values
 * asserted here are the contract shown on screen, not just an internal constant.</p>
 */
class KpiConfigTest {

    /** The documented weight for each of the five KPI components (SRS §5.3.4). */
    private static final double EXPECTED_COMPLETION   = 0.35;
    private static final double EXPECTED_SATISFACTION = 0.25;
    private static final double EXPECTED_ON_TIME      = 0.20;
    private static final double EXPECTED_RESPONSE     = 0.10;
    private static final double EXPECTED_ATTENDANCE   = 0.10;

    /** Reads one of {@code KpiCalculationService}'s private static weight constants. */
    private double weight(String fieldName) {
        Object value = ReflectionTestUtils.getField(KpiCalculationService.class, fieldName);
        assertNotNull(value,
            "KpiCalculationService must declare the weight constant " + fieldName
                + " — the KPI score is undefined without it");
        assertInstanceOf(Double.class, value,
            fieldName + " must be a double weight, was " + value.getClass().getSimpleName());
        return (Double) value;
    }

    @Test
    void weights_sumToExactlyOne() {
        // ── Step 1: read the five real constants ────────────────────────────────────────────
        double completion   = weight("WEIGHT_COMPLETION");
        double satisfaction = weight("WEIGHT_SATISFACTION");
        double onTime       = weight("WEIGHT_ON_TIME");
        double response     = weight("WEIGHT_RESPONSE_TIME");
        double attendance   = weight("WEIGHT_ATTENDANCE");

        double sum = completion + satisfaction + onTime + response + attendance;

        assertAll("the five KPI weights are the documented split and total 1.0",

            // ── Step 2: each individual weight is the documented one ────────────────────────
            () -> assertEquals(EXPECTED_COMPLETION, completion, 0.0001,
                "Job completion must carry 35% of the KPI score, was " + completion),
            () -> assertEquals(EXPECTED_SATISFACTION, satisfaction, 0.0001,
                "Customer satisfaction must carry 25% of the KPI score, was " + satisfaction),
            () -> assertEquals(EXPECTED_ON_TIME, onTime, 0.0001,
                "On-time completion must carry 20% of the KPI score, was " + onTime),
            () -> assertEquals(EXPECTED_RESPONSE, response, 0.0001,
                "Response time must carry 10% of the KPI score, was " + response),
            () -> assertEquals(EXPECTED_ATTENDANCE, attendance, 0.0001,
                "Attendance must carry 10% of the KPI score, was " + attendance),

            // ── Step 1/2: 0.35 + 0.25 + 0.20 + 0.10 + 0.10 == 1.0 ───────────────────────────
            () -> assertEquals(1.0, sum, 0.001,
                "The five weights must sum to exactly 1.0 so a perfect scorecard scores 100 "
                    + "without relying on the clamp. Sum was " + sum),

            // No weight may be zero (a silently disabled component) or negative.
            () -> assertTrue(completion > 0 && satisfaction > 0 && onTime > 0
                    && response > 0 && attendance > 0,
                "No KPI component may be weighted at zero or below — that would drop it from the "
                    + "score without any visible sign")
        );
    }
}
