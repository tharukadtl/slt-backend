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
 * {@link AttendanceDTO.CheckOutRequest} (POST /api/attendance/check-out).
 *
 * Direct mirror of {@link CheckInRequestValidationTest}: the fix REMOVED
 * @NotNull from latitude/longitude so a Technician whose phone cannot get a fix
 * at end of shift can still check out (sending null instead of a fake (0,0)),
 * while KEEPING @DecimalMin/@DecimalMax so a real, out-of-range coordinate is
 * still rejected. Per Bean Validation semantics, range constraints are skipped
 * when the value is null but enforced when a value is present.
 *
 * Validates the DTO's constraint annotations directly with a Jakarta Validator —
 * no Spring context / no MySQL — which is exactly what Spring MVC's @Valid
 * triggers on the @RequestBody before AttendanceController.checkOut runs.
 */
class CheckOutRequestValidationTest {

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
        AttendanceDTO.CheckOutRequest req = AttendanceDTO.CheckOutRequest.builder()
                .latitude(null)
                .longitude(null)
                .address("Location unavailable")
                .build();

        Set<ConstraintViolation<AttendanceDTO.CheckOutRequest>> violations =
                validator.validate(req);

        assertTrue(violations.isEmpty(),
                "null lat/lng must be valid (no @NotNull), but got: " + violations);
    }

    /** A normal, in-range real coordinate stays valid. */
    @Test
    void validRealCoordinates_areAccepted() {
        AttendanceDTO.CheckOutRequest req = AttendanceDTO.CheckOutRequest.builder()
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
        AttendanceDTO.CheckOutRequest req = AttendanceDTO.CheckOutRequest.builder()
                .latitude(999.0)
                .longitude(79.8612)
                .address("bad")
                .build();

        Set<ConstraintViolation<AttendanceDTO.CheckOutRequest>> violations =
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
        AttendanceDTO.CheckOutRequest req = AttendanceDTO.CheckOutRequest.builder()
                .latitude(6.9271)
                .longitude(-999.0)
                .address("bad")
                .build();

        Set<ConstraintViolation<AttendanceDTO.CheckOutRequest>> violations =
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
        AttendanceDTO.CheckOutRequest req = AttendanceDTO.CheckOutRequest.builder()
                .latitude(null)
                .longitude(500.0)
                .address("mixed")
                .build();

        Set<ConstraintViolation<AttendanceDTO.CheckOutRequest>> violations =
                validator.validate(req);

        assertEquals(1, violations.size(),
                "exactly one violation expected (longitude only): " + violations);
        assertEquals("longitude",
                violations.iterator().next().getPropertyPath().toString());
    }

    /**
     * The @NotNull removal was scoped to the coordinate fields only — the
     * per-open-job handover contract (SRS 5.3.1.4) must still be enforced, so a
     * reason entry with a null jobId / blank reason is still a violation even
     * when the coordinates themselves are null.
     */
    @Test
    void openJobReasonConstraints_areUnaffectedByTheCoordinateChange() {
        AttendanceDTO.JobHandoverReason bad = AttendanceDTO.JobHandoverReason.builder()
                .jobId(null)
                .reason("   ")
                .build();

        Set<ConstraintViolation<AttendanceDTO.JobHandoverReason>> violations =
                validator.validate(bad);

        assertEquals(2, violations.size(),
                "jobId @NotNull and reason @NotBlank must both still fire: " + violations);
    }
}
