package lk.slt.fieldops.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the GPS-unavailable data-integrity fix on
 * {@link AttendanceDTO.CheckInRequest} (POST /api/attendance/check-in).
 *
 * The fix REMOVED @NotNull from latitude/longitude so a GPS-unavailable client
 * can send null (instead of a fake (0,0)), while KEEPING @DecimalMin/@DecimalMax
 * so a real, out-of-range coordinate is still rejected. Per Bean Validation
 * semantics, range constraints are skipped when the value is null but enforced
 * when a value is present.
 *
 * These tests validate the DTO's constraint annotations directly with a Jakarta
 * Validator (hibernate-validator is on the classpath via
 * spring-boot-starter-validation) — no Spring context / no MySQL, matching the
 * DB-free unit-test convention in this module. This is exactly what Spring MVC's
 * @Valid triggers on the @RequestBody before the controller method runs.
 */
class CheckInRequestValidationTest {

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

    /** THE fix: null coordinates (GPS unavailable) must pass validation. */
    @Test
    void nullCoordinates_areAccepted() {
        AttendanceDTO.CheckInRequest req = AttendanceDTO.CheckInRequest.builder()
                .latitude(null)
                .longitude(null)
                .address("Location unavailable")
                .build();

        Set<ConstraintViolation<AttendanceDTO.CheckInRequest>> violations =
                validator.validate(req);

        assertTrue(violations.isEmpty(),
                "null lat/lng must be valid (no @NotNull), but got: " + violations);
    }

    /** A normal, in-range real coordinate stays valid. */
    @Test
    void validRealCoordinates_areAccepted() {
        AttendanceDTO.CheckInRequest req = AttendanceDTO.CheckInRequest.builder()
                .latitude(6.9271)
                .longitude(79.8612)
                .address("Colombo")
                .build();

        assertTrue(validator.validate(req).isEmpty(),
                "in-range coordinates must be valid");
    }

    /**
     * Removing @NotNull must NOT have weakened the range checks: a present but
     * out-of-range latitude (999 > 90) must still be rejected by @DecimalMax.
     */
    @Test
    void outOfRangeLatitude_isStillRejected() {
        AttendanceDTO.CheckInRequest req = AttendanceDTO.CheckInRequest.builder()
                .latitude(999.0)
                .longitude(79.8612)
                .address("bad")
                .build();

        Set<ConstraintViolation<AttendanceDTO.CheckInRequest>> violations =
                validator.validate(req);

        assertFalse(violations.isEmpty(),
                "latitude 999 must still fail @DecimalMax(90)");
        assertTrue(
                violations.stream().anyMatch(v ->
                        v.getPropertyPath().toString().equals("latitude")),
                "the violation must be on the latitude field: " + violations);
    }

    /** Symmetric check on longitude's lower bound (@DecimalMin(-180)). */
    @Test
    void outOfRangeLongitude_isStillRejected() {
        AttendanceDTO.CheckInRequest req = AttendanceDTO.CheckInRequest.builder()
                .latitude(6.9271)
                .longitude(-999.0)
                .address("bad")
                .build();

        Set<ConstraintViolation<AttendanceDTO.CheckInRequest>> violations =
                validator.validate(req);

        assertFalse(violations.isEmpty(),
                "longitude -999 must still fail @DecimalMin(-180)");
        assertTrue(
                violations.stream().anyMatch(v ->
                        v.getPropertyPath().toString().equals("longitude")),
                "the violation must be on the longitude field: " + violations);
    }

    /**
     * Mixed case: one null (allowed) and one out-of-range (rejected). The null
     * side must not mask the out-of-range side — proves per-field independence.
     */
    @Test
    void nullLatitudeWithOutOfRangeLongitude_rejectsOnlyLongitude() {
        AttendanceDTO.CheckInRequest req = AttendanceDTO.CheckInRequest.builder()
                .latitude(null)
                .longitude(500.0)
                .address("mixed")
                .build();

        Set<ConstraintViolation<AttendanceDTO.CheckInRequest>> violations =
                validator.validate(req);

        assertEquals(1, violations.size(),
                "exactly one violation expected (longitude only): " + violations);
        assertEquals("longitude",
                violations.iterator().next().getPropertyPath().toString());
    }
}
