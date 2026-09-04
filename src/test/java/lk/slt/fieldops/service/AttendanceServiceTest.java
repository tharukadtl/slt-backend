package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.AttendanceDTO;
import lk.slt.fieldops.entity.CheckInOut;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.VehicleAssignment;
import lk.slt.fieldops.repository.CheckInOutRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.VehicleAssignmentRepository;
import lk.slt.fieldops.repository.VehicleRepository;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 07_ATTENDANCE — ATT-002, ATT-003, ATT-004, ATT-006, ATT-010 and ATT-011 (FR-19 / FR-20).
 *
 * <p>The six rows of that sheet whose {@code Automation Mapping} names
 * {@code AttendanceServiceTest::<method>}. Pure Mockito unit tests against the real
 * {@link AttendanceService}, matching the module's service-test convention
 * ({@link AttendanceServiceCheckInNullCoordTest}, {@link LocationServiceTest},
 * {@link JobServiceBodDuplicateTest}) — no Spring context, no MySQL.</p>
 *
 * <p><b>Exception types named by the sheet do not exist.</b> {@code AlreadyCheckedInException},
 * {@code InvalidLocationException}, {@code MissingGPSException}, {@code NotCheckedInException} and
 * {@code ValidationException} are none of them present anywhere in this codebase —
 * {@code shared/exception} contains only {@code DuplicateSessionException},
 * {@code InvalidRefreshTokenException} and {@code ResourceNotFoundException}. Naming them would not
 * compile, so (following the precedent {@link LocationServiceTest} set for FAULT-018) these tests
 * assert that <i>some</i> exception is raised and check its message, rather than a class name.</p>
 *
 * <p><b>Methods named by the sheet do not all exist either</b> ({@code markAbsent},
 * {@code getDailyMileage}, a {@code vehicleInspectionDone} check-in flag). Those rows are written
 * as reflective API probes so they compile and produce a real pass/fail verdict on whether the
 * feature exists, instead of silently not being written. This suite never modifies production
 * code.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceServiceTest {

    @Mock private CheckInOutRepository    checkInOutRepository;
    @Mock private UserRepository          userRepository;
    @Mock private JobRepository           jobRepository;
    @Mock private FaultRepository         faultRepository;
    @Mock private WebSocketEventPublisher webSocketEventPublisher;
    @Mock private NotificationService     notificationService;

    @InjectMocks private AttendanceService attendanceService;

    private static final Long TECH_ID = 5L;

    private User technician;

    @BeforeEach
    void setUp() {
        technician = new User();
        technician.setId(TECH_ID);
        technician.setFullName("Tech Nimal");
        technician.setRole(User.Role.TECHNICIAN);
        technician.setPhone("0771234567");

        when(userRepository.findById(eq(TECH_ID))).thenReturn(Optional.of(technician));
        when(checkInOutRepository.save(any(CheckInOut.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private AttendanceDTO.CheckInRequest at(Double latitude, Double longitude) {
        return AttendanceDTO.CheckInRequest.builder()
            .latitude(latitude)
            .longitude(longitude)
            .address("Test address")
            .build();
    }

    /** A CHECKED_IN row for today, i.e. the technician has already done BOD. */
    private CheckInOut existingCheckInToday() {
        CheckInOut row = new CheckInOut();
        row.setId(999L);
        row.setUser(technician);
        row.setCheckType("ATTENDANCE");
        row.setCheckInTime(LocalDate.now().atTime(8, 0));
        row.setCheckInLatitude(6.9271);
        row.setCheckInLongitude(79.8612);
        row.setStatus("CHECKED_IN");
        return row;
    }

    private static List<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toList());
    }

    private static List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
            .map(java.lang.reflect.Field::getName)
            .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-002 — a second BOD check-in on the same calendar day must be rejected
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * The row's two halves are asserted separately so a failure on one does not hide the other:
     * (a) no duplicate {@code check_in_out} row is created — the data-integrity half, and
     * (b) the caller is actually TOLD the second attempt was a duplicate — the signalling half.
     *
     * <p>The Team Lead's own once-a-day BOD limit (the {@code day_sessions} path) is a different
     * code path and is already covered by {@link JobServiceBodDuplicateTest}: it throws
     * {@code DuplicateSessionException} → HTTP 409. This row is about the individual attendance
     * check-in path, {@link AttendanceService#checkIn}, which has no such coverage.</p>
     */
    @Test
    void duplicateCheckIn_throwsException() {
        CheckInOut existing = existingCheckInToday();
        when(checkInOutRepository.existsTodayCheckIn(eq(TECH_ID), any(LocalDateTime.class)))
            .thenReturn(true);
        when(checkInOutRepository.findTodayByUserId(eq(TECH_ID), any(LocalDateTime.class)))
            .thenReturn(Optional.of(existing));

        assertAll("a second same-day check-in must be refused, not silently absorbed",

            // ── Steps 2-3: the second attempt must surface an error to the caller ──────────
            () -> {
                RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> attendanceService.checkIn(TECH_ID, at(6.9271, 79.8612)),
                    "A second check-in on the same calendar day must be refused with an explicit "
                        + "error. AttendanceService.checkIn instead detects the duplicate "
                        + "(existsTodayCheckIn -> true) and RETURNS THE EXISTING RECORD with a "
                        + "log.warn, so POST /api/attendance/check-in answers 200 with a "
                        + "CHECKED_IN body that is indistinguishable from a fresh check-in. The "
                        + "client cannot tell a duplicate from a success — the same shape the "
                        + "Team Lead BOD path already rejects with DuplicateSessionException/409 "
                        + "(see JobServiceBodDuplicateTest).");

                assertTrue(thrown.getMessage() != null
                        && thrown.getMessage().toLowerCase().contains("already"),
                    "The error must say the user has already checked in today. Was: "
                        + thrown.getMessage());
            },

            // ── Step 4: whatever the signalling, no duplicate row may be written ───────────
            () -> {
                clearInvocations(checkInOutRepository);
                try {
                    attendanceService.checkIn(TECH_ID, at(6.9271, 79.8612));
                } catch (RuntimeException expectedOnceFixed) {
                    // Either behaviour is fine for THIS sub-check; what matters is no save().
                }
                verify(checkInOutRepository, never()).save(any(CheckInOut.class));
            }
        );
    }

    /**
     * QA_Compliance_Consolidated_Report.md — Stage G FCM Major: a check-in only ever raised a
     * transient {@code sendToRole("admin", ...)} WebSocket frame; an admin who is not currently
     * connected learns nothing. Mirrors FaultService.notifyAdminsOfNewFault's loop-over-admins
     * shape: every active ADMIN/SUPER_ADMIN, not just whoever is on socket.
     */
    @Test
    void checkIn_notifiesEveryActiveAdmin() {
        when(checkInOutRepository.existsTodayCheckIn(eq(TECH_ID), any(LocalDateTime.class)))
            .thenReturn(false);

        User admin1 = new User();
        admin1.setId(900L);
        admin1.setFullName("Admin Amelia");
        admin1.setRole(User.Role.ADMIN);
        admin1.setFcmToken("fcm-admin-1");

        User superAdmin = new User();
        superAdmin.setId(901L);
        superAdmin.setFullName("Super Admin Sena");
        superAdmin.setRole(User.Role.SUPER_ADMIN);
        superAdmin.setFcmToken("fcm-super-1");

        when(userRepository.findByRoleAndIsActiveTrue(User.Role.ADMIN))
            .thenReturn(List.of(admin1));
        when(userRepository.findByRoleAndIsActiveTrue(User.Role.SUPER_ADMIN))
            .thenReturn(List.of(superAdmin));

        attendanceService.checkIn(TECH_ID, at(6.9271, 79.8612));

        assertAll("a check-in notifies every active admin/super-admin, not just whoever is on socket",
            () -> verify(webSocketEventPublisher).sendToRole(
                eq("admin"), eq("Staff Checked In"), any(), eq("ATTENDANCE_CHECK_IN")),
            () -> verify(notificationService).notifyStaffCheckedIn(
                eq(900L), eq("fcm-admin-1"), eq(technician.getFullName()), any()),
            () -> verify(notificationService).notifyStaffCheckedIn(
                eq(901L), eq("fcm-super-1"), eq(technician.getFullName()), any())
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-003 — GPS validation at BOD
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * <p><b>Deliberate deviation from the row on its step 2.</b> The row expects
     * {@code checkIn(techId, null, null)} to throw a {@code MissingGPSException}. That is no longer
     * this system's intended behaviour and asserting it would re-break a closed Critical: null
     * coordinates at check-in are legitimate by design (Critical #28/#29 and the ATT-013 row on
     * this very sheet), because a technician with no GPS fix must be able to start the day and a
     * fake {@code (0,0)} would be indistinguishable from a real check-in at 0°N 0°E. See the
     * {@code AttendanceDTO.CheckInRequest} javadoc and
     * {@link AttendanceServiceCheckInNullCoordTest}. Step 2 therefore asserts the <i>current,
     * deliberate</i> contract — null is accepted and stored as null — and the contradiction is
     * recorded rather than silently resolved either way.</p>
     *
     * <p>Steps 1 and 3 are asserted as written. The Sri Lanka bounds that exist elsewhere in this
     * system ({@code LocationService.validateSriLankaCoords}, added for FAULT-018, lat 5.9-9.9 /
     * lng 79.5-81.9, mirroring {@code slt-ai-module}'s {@code Config}) have no counterpart on the
     * attendance path.</p>
     */
    @Test
    void gpsOutsideSL_throwsInvalidLocation() {
        when(checkInOutRepository.existsTodayCheckIn(eq(TECH_ID), any(LocalDateTime.class)))
            .thenReturn(false);

        assertAll("BOD check-in coordinate validation",

            // ── Step 1: London (51.5, -0.12) is nowhere near Sri Lanka and must be rejected ─
            () -> assertThrows(RuntimeException.class,
                () -> attendanceService.checkIn(TECH_ID, at(51.5, -0.12)),
                "A check-in from outside Sri Lanka must be rejected. AttendanceService.checkIn "
                    + "applies no geographic check whatsoever — it copies request.latitude/"
                    + "longitude straight onto the CheckInOut row — and "
                    + "AttendanceDTO.CheckInRequest carries only the global @DecimalMin(-90)/"
                    + "@DecimalMax(90) and -180/180 bounds, which London satisfies. So a "
                    + "technician can be recorded as beginning their SLT day in London, and "
                    + "every downstream location-aware feature reads it as a real position. "
                    + "LocationService.updateLocation DOES enforce Sri Lanka bounds "
                    + "(validateSriLankaCoords, added for FAULT-018) — the attendance path was "
                    + "never given the same guard."),

            // ── Step 2: null coordinates are legitimate BY DESIGN — see the javadoc above ───
            () -> {
                AttendanceDTO.AttendanceResponse response = assertDoesNotThrow(
                    () -> attendanceService.checkIn(TECH_ID, at(null, null)),
                    "A check-in with no GPS fix must be ACCEPTED (Critical #28/#29, ATT-013), "
                        + "not rejected as the row's step 2 asks");
                assertNull(response.getCheckInLatitude(),
                    "and stored as a genuine null, never coerced to 0.0");
                assertNull(response.getCheckInLongitude(),
                    "and stored as a genuine null, never coerced to 0.0");
            },

            // ── Step 3: Colombo (6.9271, 79.8612) is inside Sri Lanka and must succeed ──────
            () -> {
                AttendanceDTO.AttendanceResponse response = assertDoesNotThrow(
                    () -> attendanceService.checkIn(TECH_ID, at(6.9271, 79.8612)),
                    "A coordinate inside Sri Lanka must be accepted");
                assertEquals("CHECKED_IN", response.getStatus());
                assertEquals(6.9271, response.getCheckInLatitude());
                assertEquals(79.8612, response.getCheckInLongitude());
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-004 — vehicle-inspection warning at BOD
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Written as a reflective API probe because the row's own call —
     * {@code attService.checkIn(5L, 6.9271, 79.8612, 15200, false)} — names a five-argument
     * overload that does not exist; the real signature is
     * {@code checkIn(Long, AttendanceDTO.CheckInRequest)}. Writing the row's literal code would
     * not compile, and a test that cannot compile is not a test. The probe asserts the two things
     * the row actually requires of the system: that a check-in can carry a vehicle-inspection
     * flag, and that the response can carry a non-blocking warning.
     */
    @Test
    void vehicleInspectionFalse_warningReturned() {
        when(checkInOutRepository.existsTodayCheckIn(eq(TECH_ID), any(LocalDateTime.class)))
            .thenReturn(false);

        List<String> requestFields = fieldNames(AttendanceDTO.CheckInRequest.class);
        List<String> responseFields = fieldNames(AttendanceDTO.AttendanceResponse.class);

        assertAll("BOD check-in must be able to report an incomplete vehicle inspection",

            // ── Step 1: the check-in request must be able to carry the flag ────────────────
            () -> assertTrue(
                requestFields.stream().anyMatch(f -> f.toLowerCase().contains("inspection")),
                "AttendanceDTO.CheckInRequest must carry a vehicleInspectionDone flag. There is "
                    + "no vehicle-inspection concept anywhere in this system: the string "
                    + "\"inspection\" does not appear in fieldops/src/main, in "
                    + "frontend-admin/src, or in SLTMobileApp/src, and neither the Technician "
                    + "BODScreen nor the Team Lead BODScreen renders such a control. Fields "
                    + "present: " + requestFields),

            // ── Steps 2-3: the response must be able to carry a NON-BLOCKING warning ───────
            () -> assertTrue(
                responseFields.stream().anyMatch(f -> f.toLowerCase().contains("warning")),
                "AttendanceDTO.AttendanceResponse must carry a warning field so an incomplete "
                    + "inspection can be surfaced without blocking the check-in. It has no such "
                    + "field, so there is no channel for a non-blocking warning at all. Fields "
                    + "present: " + responseFields),

            // ── Step 4 and the row's real point: check-in itself must still succeed ────────
            () -> {
                AttendanceDTO.AttendanceResponse response =
                    attendanceService.checkIn(TECH_ID, at(6.9271, 79.8612));
                assertEquals("CHECKED_IN", response.getStatus(),
                    "Whatever the inspection state, the check-in must still succeed — the "
                        + "warning is advisory, not a gate. (This half holds today only because "
                        + "the feature is absent entirely.)");
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-006 — check-out with no check-in
    // ══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void checkOutWithoutCheckIn_throws() {
        // Mock: no open check-in record for this technician at all.
        when(checkInOutRepository.findActiveCheckInByUserId(eq(TECH_ID)))
            .thenReturn(Optional.empty());

        AttendanceDTO.CheckOutRequest request = AttendanceDTO.CheckOutRequest.builder()
            .latitude(6.93)
            .longitude(79.86)
            .address("Test address")
            .build();

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> attendanceService.checkOut(TECH_ID, request),
            "Checking out without ever having checked in must be refused");

        assertAll("check-out without check-in",
            () -> assertTrue(
                thrown.getMessage() != null
                    && thrown.getMessage().toLowerCase().contains("check-in"),
                "The error must name the missing check-in so the client can tell the technician "
                    + "what to do. Was: " + thrown.getMessage()),
            // Nothing may be persisted on the failed path.
            () -> verify(checkInOutRepository, never()).save(any(CheckInOut.class)),
            () -> verify(jobRepository, never()).save(any())
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-010 — absent marking when nobody checks in by the cutoff
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Reflective API probe, for the same compile-time reason as ATT-004: the row calls
     * {@code attService.markAbsent(5L, today)} and reads back an {@code ABSENT} attendance row,
     * and neither the method nor the persisted status exists.
     */
    @Test
    void noCheckInByCutoff_marksAbsent() {
        when(checkInOutRepository.findTodayByUserId(eq(TECH_ID), any(LocalDateTime.class)))
            .thenReturn(Optional.empty());

        List<String> serviceMethods = methodNames(AttendanceService.class);

        assertAll("a technician who never checks in must be recorded ABSENT for the day",

            // ── Steps 2-3: the entry point the scheduled cutoff job would call ─────────────
            () -> assertTrue(serviceMethods.contains("markAbsent"),
                "AttendanceService must expose markAbsent(userId, date) for the EOD-cutoff "
                    + "sweep. No such method exists, and nothing anywhere in fieldops/src/main "
                    + "is @Scheduled to run an attendance cutoff — so no attendance row is ever "
                    + "created for a no-show. Declared methods: " + serviceMethods),

            // ── Steps 3-4: ABSENT must be a persisted state, not merely a rendering ────────
            () -> {
                AttendanceDTO.TodaySummaryDTO summary =
                    attendanceService.getTodaySummary(TECH_ID);
                assertEquals("ABSENT", summary.getCurrentStatus(),
                    "After the cutoff, a technician with no check-in must read ABSENT with a "
                        + "null checkInTime. getTodaySummary returns NOT_CHECKED_IN and cannot "
                        + "distinguish 'has not checked in yet, it is 09:00' from 'never turned "
                        + "up, it is 20:00'. getTeamToday DOES label such a member ABSENT, but "
                        + "that label is computed on the fly for the Team Lead screen and is "
                        + "never written to check_in_out — so no absence is queryable, "
                        + "reportable or auditable once the day rolls over. Worse, "
                        + "AttendanceService.getHistory (behind GET /api/attendance/history/"
                        + "{userId} and /me/history) hard-codes absentDays = 0 and "
                        + "attendanceRate = presentDays > 0 ? 100.0 : 0 for EVERY technician, "
                        + "so the attendance history screen always reads 100% / 0 absences. "
                        + "ReportService.buildAttendanceReport derives both correctly from the "
                        + "very same check_in_out rows (absentDays = totalDays - distinct "
                        + "present days), so the same data yields two different answers "
                        + "depending on which endpoint asks.");
            },

            // ── Step 5: the admin must be notified of the absence ──────────────────────────
            () -> {
                List<String> types =
                    Arrays.stream(lk.slt.fieldops.entity.Notification.NotificationType.values())
                        .map(Enum::name)
                        .collect(Collectors.toList());
                assertTrue(types.stream().anyMatch(t -> t.contains("ABSEN")
                        || t.contains("ATTENDANCE")),
                    "An absence must be notifiable to the Admin. Notification.NotificationType "
                        + "has no attendance/absence constant at all, so even a persisted "
                        + "absence could not be pushed — Notification.type is "
                        + "@Enumerated(EnumType.STRING) over this closed set. The only "
                        + "attendance signalling in the module is the two ephemeral "
                        + "WebSocketEventPublisher.sendToRole(\"admin\", ...) frames on check-in "
                        + "and check-out, which write no notifications row and reach nobody who "
                        + "is offline. Constants: " + types);
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-011 — daily mileage from the BOD/EOD odometer pair
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Reflective API probe. The row calls {@code attService.getDailyMileage(checkIn, 12250)}; no
     * such method exists on {@link AttendanceService}, and {@link CheckInOut} has no odometer
     * column at all — the readings live on {@code day_sessions} ({@code bodOdometer} /
     * {@code eodOdometer}), written by {@code JobService.performBod}/{@code performEod} on the
     * Team Lead path, not the individual attendance path this row targets.
     *
     * <p>The backward-odometer half of the row is checked against the one place a distance is
     * actually computed, {@code VehicleService.closeAssignment}. RES-008 (sheet 05) already
     * established that {@code closeAssignment} has no caller anywhere; this row adds that its
     * arithmetic is also unguarded.</p>
     */
    @Test
    void odometerGoingBackward_throwsValidation() {
        List<String> serviceMethods = methodNames(AttendanceService.class);
        List<String> checkInOutFields = fieldNames(CheckInOut.class);

        assertAll("daily mileage from the day's two odometer readings",

            // ── Steps 1-2: the readings must be capturable on the attendance record ────────
            () -> assertTrue(
                checkInOutFields.stream().anyMatch(f -> f.toLowerCase().contains("odometer")),
                "CheckInOut must carry the BOD and EOD odometer readings for this row's "
                    + "arithmetic to have any input. It has none — neither "
                    + "AttendanceDTO.CheckInRequest nor CheckOutRequest accepts an odometer "
                    + "either, so POST /api/attendance/check-in {odometer:15200} is simply "
                    + "discarded. The readings exist only on day_sessions, via the Team Lead's "
                    + "POST /api/jobs/bod and /api/jobs/eod. Fields present: " + checkInOutFields),

            // ── Step 3: 12250 - 12000 = 250 must be obtainable ────────────────────────────
            () -> assertTrue(serviceMethods.contains("getDailyMileage"),
                "AttendanceService must expose getDailyMileage. It does not, and no other "
                    + "service exposes a per-day mileage for a technician: the only distance "
                    + "arithmetic in the module is VehicleService.closeAssignment "
                    + "(distanceKm = eod - bod), which RES-008 established has NO CALLER "
                    + "anywhere, so vehicle_assignments is never written. Declared methods: "
                    + serviceMethods),

            // ── Step 3 again, against the one implementation that DOES subtract ────────────
            // 12000 -> 12250 must give 250. Driven through the real
            // VehicleService.closeAssignment with its three repositories mocked.
            () -> {
                VehicleAssignmentRepository assignmentRepo =
                    mock(VehicleAssignmentRepository.class);
                VehicleService vehicleService = new VehicleService(
                    mock(VehicleRepository.class), assignmentRepo, mock(UserRepository.class));

                VehicleAssignment open = new VehicleAssignment();
                open.setBodOdometer(12000);
                open.setAssignmentDate(LocalDate.now());
                when(assignmentRepo.findByTeamLeadIdAndAssignmentDate(
                        eq(TECH_ID), any(LocalDate.class)))
                    .thenReturn(Optional.of(open));
                when(assignmentRepo.save(any(VehicleAssignment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

                VehicleAssignment closed = vehicleService.closeAssignment(TECH_ID, 12250);
                assertEquals(250, closed.getDistanceKm().intValue(),
                    "12250 - 12000 is 250 km");
            },

            // ── Step 4: an EOD reading BELOW the BOD reading must be rejected ──────────────
            () -> {
                VehicleAssignmentRepository assignmentRepo =
                    mock(VehicleAssignmentRepository.class);
                VehicleService vehicleService = new VehicleService(
                    mock(VehicleRepository.class), assignmentRepo, mock(UserRepository.class));

                VehicleAssignment open = new VehicleAssignment();
                open.setBodOdometer(12000);
                open.setAssignmentDate(LocalDate.now());
                when(assignmentRepo.findByTeamLeadIdAndAssignmentDate(
                        eq(TECH_ID), any(LocalDate.class)))
                    .thenReturn(Optional.of(open));
                when(assignmentRepo.save(any(VehicleAssignment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

                RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> vehicleService.closeAssignment(TECH_ID, 11999),
                    "An EOD odometer BELOW the BOD odometer is impossible and must be rejected "
                        + "as invalid input. VehicleService.closeAssignment instead clamps it — "
                        + "distanceKm = Math.max(0, eodOdometer - bodOdometer) — so 11999 after "
                        + "12000 is silently accepted and recorded as 0 km driven, erasing the "
                        + "evidence of a mistyped or fraudulent reading instead of surfacing it. "
                        + "JobService.performEod is looser still: it writes request.odometerEnd "
                        + "onto day_sessions without comparing it to bodOdometer at all.");
                assertFalse(thrown instanceof NullPointerException,
                    "and must be rejected by an explicit validation error, not a crash. Thrown: "
                        + thrown);
            }
        );
    }
}
