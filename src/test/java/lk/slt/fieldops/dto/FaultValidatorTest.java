package lk.slt.fieldops.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import lk.slt.fieldops.entity.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FAULT-002 and FAULT-003 (02_FAULT_TRACKING, FR-4) — Bean Validation on the fault-report DTO.
 *
 * <p>The sheet names the target "FaultValidatorTest"; the DTO that Spring MVC actually runs
 * {@code @Valid} against on {@code POST /api/faults} is {@link ReportFaultRequest}, so that is
 * what is validated here. Constraints are exercised with a Jakarta {@link Validator} directly —
 * no Spring context, no MySQL — matching the convention of
 * {@link CheckInRequestValidationTest} in this same package. {@code validateProperty} is used so
 * each test isolates the single field under test instead of tripping the other required fields.</p>
 *
 * <p><b>Where the real enforcement lives.</b> {@code ReportFaultRequest.category} is a plain
 * {@code String} annotated only with {@code @NotNull}; membership of {@link Fault.FaultCategory}
 * is enforced one layer down, in {@code FaultService.parseCategoryOrThrow}, which throws
 * {@code "Invalid category: ... Valid: INTERNET, PHONE, FIBER, TV, OTHER"}. The FAULT-003 test
 * below therefore asserts what the row asks for (a Bean Validation violation at the DTO layer)
 * and will be red while that enforcement stays at the service layer.</p>
 *
 * <p><b>Note on the row's test data.</b> FAULT-003 lists the valid enum set as
 * "BROADBAND, FIBER, TELEPHONE, TELEVISION, OTHER" and calls {@code 'INTERNET'} invalid. The real
 * {@link Fault.FaultCategory} enum is {@code INTERNET, PHONE, FIBER, TV, OTHER} — the mobile-facing
 * names (BROADBAND / TELEPHONE / TELEVISION) are aliases translated by
 * {@code FaultController.normalizeMobileCategory} before the service ever sees them. The test is
 * written against the real enum and treats a string that is in neither set as the invalid case.</p>
 */
class FaultValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
    }

    private ReportFaultRequest requestWithDescription(String description) {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("INTERNET");
        req.setDescription(description);
        req.setLocationAddress("No. 5 Main Street, Colombo 03");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(1L);
        return req;
    }

    private ReportFaultRequest requestWithCategory(String category) {
        ReportFaultRequest req = requestWithDescription("No internet since 08:00");
        req.setCategory(category);
        return req;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FAULT-002 — description must be at least 10 characters
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void description_min10chars_enforced() {
        // ── Step 1: a 5-character description must produce a violation ─────────────────────
        Set<ConstraintViolation<ReportFaultRequest>> tooShort =
            validator.validateProperty(requestWithDescription("Short"), "description");
        assertFalse(tooShort.isEmpty(),
            "A 5-character description must be rejected — the row requires a 10-character minimum, "
                + "but ReportFaultRequest.description carries only @NotBlank + @Size(max = 500), "
                + "so nothing enforces a lower bound. Violations found: " + tooShort);

        // ── Step 2: a 23-character description must pass ───────────────────────────────────
        Set<ConstraintViolation<ReportFaultRequest>> validLength =
            validator.validateProperty(requestWithDescription("No internet since 08:00"), "description");
        assertTrue(validLength.isEmpty(),
            "A >= 10 character description must be accepted. Violations: " + validLength);

        // ── Step 3: an empty description must produce a violation (@NotBlank) ──────────────
        Set<ConstraintViolation<ReportFaultRequest>> empty =
            validator.validateProperty(requestWithDescription(""), "description");
        assertFalse(empty.isEmpty(),
            "An empty description must be rejected by @NotBlank");
        assertTrue(empty.stream().anyMatch(v -> "Description is required".equals(v.getMessage())),
            "Empty description should carry the DTO's own message. Violations: " + empty);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FAULT-003 — category must be a member of the FaultCategory enum
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void category_invalidEnum_throwsViolation() {
        // ── Step 1: every real enum constant must pass ─────────────────────────────────────
        for (Fault.FaultCategory category : Fault.FaultCategory.values()) {
            Set<ConstraintViolation<ReportFaultRequest>> violations =
                validator.validateProperty(requestWithCategory(category.name()), "category");
            assertTrue(violations.isEmpty(),
                "Valid category " + category + " must be accepted. Violations: " + violations);
        }

        // ── Step 2: null must produce a violation (@NotNull) ───────────────────────────────
        Set<ConstraintViolation<ReportFaultRequest>> nullCategory =
            validator.validateProperty(requestWithCategory(null), "category");
        assertFalse(nullCategory.isEmpty(), "A null category must be rejected by @NotNull");
        assertTrue(nullCategory.stream().anyMatch(v -> "Category is required".equals(v.getMessage())),
            "Null category should carry the DTO's own message. Violations: " + nullCategory);

        // ── Step 3: a string outside the enum must produce a violation ─────────────────────
        // 'NOT_A_CATEGORY' is in neither the real enum (INTERNET/PHONE/FIBER/TV/OTHER) nor the
        // mobile alias set (BROADBAND/TELEPHONE/TELEVISION) that normalizeMobileCategory maps.
        Set<ConstraintViolation<ReportFaultRequest>> invalidCategory =
            validator.validateProperty(requestWithCategory("NOT_A_CATEGORY"), "category");
        assertFalse(invalidCategory.isEmpty(),
            "A category outside FaultCategory must be rejected at the DTO layer. It currently is "
                + "not: the field is a bare String with only @NotNull, and enum membership is "
                + "checked later in FaultService.parseCategoryOrThrow. Violations: " + invalidCategory);
    }
}
