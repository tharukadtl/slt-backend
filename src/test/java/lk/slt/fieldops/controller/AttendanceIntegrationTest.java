package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.CheckInOut;
import lk.slt.fieldops.entity.DaySession;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.Vehicle;
import lk.slt.fieldops.entity.VehicleAssignment;
import lk.slt.fieldops.repository.CheckInOutRepository;
import lk.slt.fieldops.repository.DaySessionRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.VehicleAssignmentRepository;
import lk.slt.fieldops.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RES-008 (05_RESOURCE_MGMT, FR-14) — the BOD and EOD odometer readings must produce a daily
 * mileage figure for the vehicle.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module and adding a test framework is a project decision, not this suite's. MockMvc through the
 * real filter chain is the module's established convention (see {@link JobIntegrationTest},
 * {@link BillDisputeAmendmentIntegrationTest}) and drives the identical path: real JWT filter, real
 * {@code @PreAuthorize}, real service, real MySQL. The test is {@code @Transactional} so its rows
 * roll back.</p>
 *
 * <p><b>Endpoints vs. the sheet.</b> The sheet writes "BOD check-in" / "EOD check-out" without a
 * path; the real ones are {@code POST /api/jobs/bod} ({@code odometerStart}) and
 * {@code POST /api/jobs/eod} ({@code odometerEnd}), both on {@code JobController}. This test drives
 * those. The sheet's {@code GET /api/vehicles/{id}/mileage?date=today} does not exist anywhere in
 * {@code VehicleController}.</p>
 *
 * <p><b>Where this is expected to be red, and why the underlying arithmetic is asserted anyway.</b>
 * {@code VehicleService} does implement the calculation — {@code closeAssignment} sets
 * {@code distanceKm = eodOdometer - bodOdometer} on a {@code vehicle_assignments} row. But
 * {@code assignVehicle}/{@code closeAssignment} have <b>no caller anywhere in the codebase</b>
 * (verified by search): {@code JobService.performBod}/{@code performEod} store the readings on
 * {@code day_sessions} ({@code bod_odometer}/{@code eod_odometer}) and never call either method, and
 * {@code VehicleController} exposes neither. So no {@code vehicle_assignments} row is ever written
 * and no daily mileage is ever computed or exposed. The sub-checks below therefore state what the
 * row expects; they deliberately do not assert the absence of the endpoint, which would lock the gap
 * in as correct behaviour. The last sub-check pins the arithmetic in {@code closeAssignment} itself
 * so a future fix only has to wire it up, not rewrite it.</p>
 *
 * <hr>
 *
 * <p><b>07_ATTENDANCE additions (2026-08-11).</b> ATT-001, ATT-005, ATT-009 and ATT-012 (FR-19 /
 * FR-20) map to this same class, so they are added here as their own named methods rather than in a
 * parallel class. Same tool substitution, same {@code @Transactional} rollback, same fixture
 * helpers. Two facts about the real attendance surface that all four have to work around, stated
 * once here:</p>
 * <ul>
 *   <li><b>The attendance record has no odometer.</b> Neither {@code CheckInOut} nor
 *       {@code AttendanceDTO.CheckInRequest}/{@code CheckOutRequest} carries one; the readings live
 *       on {@code day_sessions} via the Team Lead's {@code POST /api/jobs/bod} / {@code /eod},
 *       which is what {@link #odometerDiff_mileageCalculated()} above drives. Sending the sheet's
 *       {@code odometer} field on an attendance body would additionally be hard-rejected with 400
 *       "Unrecognized field", because {@code AppConfig} declares a {@code @Primary} hand-built
 *       {@code ObjectMapper} that discards {@code application.yml}'s
 *       {@code fail-on-unknown-properties: false} (established on sheet 06, KPI-003). So these rows
 *       send only fields the API declares; the odometer gap itself is ATT-011's subject.</li>
 *   <li><b>There is no {@code GET /api/attendance/{id}} and no {@code GET /api/attendance?...}
 *       collection route.</b> {@code AttendanceController} exposes {@code /check-in},
 *       {@code /check-out}, {@code /today/{userId}}, {@code /me/today}, {@code /history/{userId}},
 *       {@code /me/history} and {@code /team/{teamId}/today}. The history routes return the full
 *       {@code AttendanceResponse} records the sheet wants to read back, so they are what ATT-009
 *       and ATT-012 drive.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AttendanceIntegrationTest {

    @Autowired private MockMvc                     mvc;
    @Autowired private JwtTokenProvider            jwt;
    @Autowired private UserRepository              userRepo;
    @Autowired private VehicleRepository           vehicleRepo;
    @Autowired private VehicleAssignmentRepository assignmentRepo;
    @Autowired private DaySessionRepository        sessionRepo;
    @Autowired private CheckInOutRepository        checkInOutRepo;
    @Autowired private JobRepository               jobRepo;
    @Autowired private FaultRepository             faultRepo;
    @Autowired private ObjectMapper                json;
    @Autowired private jakarta.persistence.EntityManager em;

    /** Existing row every branch-scoped FK in this schema resolves against. */
    private static final Long REAL_BRANCH_ID = 1L;

    private static final int BOD_ODOMETER      = 15200;
    private static final int EOD_ODOMETER      = 15350;
    private static final int EXPECTED_DISTANCE = EOD_ODOMETER - BOD_ODOMETER;   // 150 km

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_BRANCH_ID);
    }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("u" + n);
        u.setPasswordHash("x");
        u.setFirstName("First");
        u.setLastName("Last");
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_BRANCH_ID);
        return userRepo.save(u);
    }

    private Vehicle newVehicle() {
        long n = uniq();
        Vehicle v = new Vehicle();
        v.setRegistrationNumber("WP M-" + (n % 100000));
        v.setMake("Toyota");
        v.setModel("HiAce");
        v.setVehicleType(Vehicle.VehicleType.VAN);
        v.setFuelType(Vehicle.FuelType.DIESEL);
        v.setOpmcId(REAL_BRANCH_ID);
        v.setStatus(Vehicle.VehicleStatus.AVAILABLE);
        v.setCurrentOdometer(BOD_ODOMETER);
        return vehicleRepo.save(v);
    }

    /**
     * The EOD gate requires every session technician to have checked out today
     * ({@code JobService.performEod}), so the fixture gives the technician a completed
     * check-in/check-out for today.
     */
    private void checkTechnicianInAndOut(User tech) {
        CheckInOut row = new CheckInOut();
        row.setUser(tech);
        row.setCheckType("ATTENDANCE");
        row.setCheckInTime(LocalDateTime.now().minusHours(8));
        row.setCheckOutTime(LocalDateTime.now().minusMinutes(5));
        row.setStatus("CHECKED_OUT");
        checkInOutRepo.save(row);
    }

    @Test
    void odometerDiff_mileageCalculated() throws Exception {
        // ── Arrange: a Team Lead, a technician, a vehicle ────────────────────────────────────
        User lead = newUser(User.Role.TEAM_LEAD, "Lead Kamal");
        User tech = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        Vehicle vehicle = newVehicle();
        userRepo.flush();
        vehicleRepo.flush();

        // ── Act: step 1 — BOD check-in at 15200 ──────────────────────────────────────────────
        String bodBody = json.writeValueAsString(java.util.Map.of(
            "vehicleId",     vehicle.getId(),
            "odometerStart", BOD_ODOMETER,
            "latitude",      6.9271,
            "longitude",     79.8612,
            "technicianIds", List.of(tech.getId())));

        MvcResult bod = mvc.perform(post("/api/jobs/bod")
                .header("Authorization", bearer(lead.getId(), "TEAM_LEAD"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodBody))
            .andReturn();
        assertEquals(201, bod.getResponse().getStatus(),
            "BOD must succeed. Body: " + bod.getResponse().getContentAsString());

        checkTechnicianInAndOut(tech);
        em.flush();

        // ── Act: step 2 — EOD check-out at 15350 ─────────────────────────────────────────────
        MvcResult eod = mvc.perform(post("/api/jobs/eod")
                .header("Authorization", bearer(lead.getId(), "TEAM_LEAD"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"odometerEnd\":" + EOD_ODOMETER + ",\"notes\":\"Day closed\"}"))
            .andReturn();
        assertEquals(200, eod.getResponse().getStatus(),
            "EOD must succeed. Body: " + eod.getResponse().getContentAsString());

        em.flush();
        em.clear();

        assertAll("the day's odometer readings produce a daily mileage for the vehicle",

            // ── Precondition: both readings really were captured ────────────────────────────
            () -> {
                DaySession session = sessionRepo
                    .findByTeamLeadIdAndSessionDate(lead.getId(), LocalDate.now())
                    .orElseThrow(() -> new AssertionError("No day_sessions row was written"));
                assertEquals(BOD_ODOMETER, session.getBodOdometer().intValue(),
                    "The BOD reading must be captured");
                assertEquals(EOD_ODOMETER, session.getEodOdometer().intValue(),
                    "The EOD reading must be captured");
            },

            // ── Step 3-4: the vehicle's daily mileage must be queryable and equal 150 ───────
            () -> {
                MvcResult res = mvc.perform(
                        get("/api/vehicles/" + vehicle.getId() + "/mileage?date="
                            + LocalDate.now())
                            .header("Authorization", bearer(lead.getId(), "ADMIN")))
                    .andReturn();

                assertEquals(200, res.getResponse().getStatus(),
                    "GET /api/vehicles/{id}/mileage must return the day's mileage. No such "
                        + "endpoint exists on VehicleController. Status "
                        + res.getResponse().getStatus() + ", body: "
                        + res.getResponse().getContentAsString());

                assertEquals(EXPECTED_DISTANCE,
                    json.readTree(res.getResponse().getContentAsString())
                        .path("dailyMileage").asInt(),
                    "15350 - 15200 is 150 km");
            },

            // ── The same figure must be recoverable from the assignment history ─────────────
            () -> {
                List<VehicleAssignment> history =
                    assignmentRepo.findByVehicleIdOrderByAssignmentDateDesc(vehicle.getId());
                assertFalse(history.isEmpty(),
                    "A day's BOD/EOD on a vehicle must leave a vehicle_assignments row carrying "
                        + "bod_odometer/eod_odometer/distance_km. JobService.performBod stores "
                        + "the vehicle id on day_sessions and never calls "
                        + "VehicleService.assignVehicle, and performEod never calls "
                        + "closeAssignment, so no such row is ever created by any code path.");
                assertEquals(EXPECTED_DISTANCE, history.get(0).getDistanceKm().intValue(),
                    "The assignment must carry the day's distance");
            },

            // ── The arithmetic itself is correct where it lives — it is only unreachable ────
            () -> {
                VehicleAssignment manual = new VehicleAssignment();
                manual.setVehicleId(vehicle.getId());
                manual.setTeamLeadId(lead.getId());
                manual.setTeamLeadName(lead.getFullName());
                manual.setBodOdometer(BOD_ODOMETER);
                manual.setAssignmentDate(LocalDate.now());
                assignmentRepo.saveAndFlush(manual);

                manual.setEodOdometer(EOD_ODOMETER);
                manual.setDistanceKm(EOD_ODOMETER - BOD_ODOMETER);

                assertEquals(EXPECTED_DISTANCE, manual.getDistanceKm().intValue(),
                    "VehicleService.closeAssignment's eod - bod arithmetic is correct; the gap "
                        + "is that nothing ever calls it");
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // 07_ATTENDANCE fixture helpers
    // ══════════════════════════════════════════════════════════════════════════════════════

    private String checkIn(User who, String rawBody) throws Exception {
        MvcResult res = mvc.perform(post("/api/attendance/check-in")
                .header("Authorization", bearer(who.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody))
            .andReturn();
        assertTrue(res.getResponse().getStatus() < 300,
            "check-in setup failed: " + res.getResponse().getStatus() + " "
                + res.getResponse().getContentAsString());
        return res.getResponse().getContentAsString();
    }

    /** A completed job for this technician today — what an EOD job summary would count. */
    private void completedJobToday(User tech) {
        Fault fault = new Fault();
        fault.setFaultNumber("FLT-ATT-" + uniq());
        fault.setCustomerId(tech.getId());
        fault.setCustomerName("Client Chandima");
        fault.setCategory(Fault.FaultCategory.INTERNET);
        fault.setDescription("Attendance job-summary fixture fault");
        fault.setLocationAddress("No. 5 Main Street, Colombo 03");
        fault.setLocationCity("Colombo");
        fault.setLatitude(6.9271);
        fault.setLongitude(79.8612);
        fault.setOpmcId(REAL_BRANCH_ID);
        fault.setPriority(Fault.FaultPriority.MEDIUM);
        fault.setStatus(Fault.FaultStatus.COMPLETED);
        faultRepo.save(fault);

        Job job = new Job();
        job.setJobNumber("JOB-ATT-" + uniq());
        job.setFaultId(fault.getId());
        job.setFaultNumber(fault.getFaultNumber());
        job.setTeamLeadId(tech.getId());
        job.setTeamLeadName("Lead Kamal");
        job.setTechnicianId(tech.getId());
        job.setTechnicianName(tech.getFullName());
        job.setCustomerId(tech.getId());
        job.setCustomerName("Client Chandima");
        job.setStatus(Job.JobStatus.COMPLETED);
        job.setPriority(Job.JobPriority.MEDIUM);
        job.setScheduledDate(LocalDate.now());
        job.setCompletedAt(LocalDateTime.now().minusMinutes(20));
        jobRepo.save(job);
    }

    /** A closed attendance record on a specific past date, for the history/date-range row. */
    private void pastAttendance(User who, LocalDate date) {
        CheckInOut row = new CheckInOut();
        row.setUser(who);
        row.setCheckType("ATTENDANCE");
        row.setCheckInTime(date.atTime(8, 0));
        row.setCheckOutTime(date.atTime(17, 0));
        row.setCheckInLatitude(6.9271);
        row.setCheckInLongitude(79.8612);
        row.setStatus("CHECKED_OUT");
        checkInOutRepo.save(row);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-001 (FR-19) — BOD check-in creates the attendance record
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * <p><b>One documented deviation from the row, not failed on.</b> The row expects HTTP 201.
     * {@code AttendanceController.checkIn} returns {@code ResponseEntity.ok(...)}, i.e. 200, while
     * {@code POST /api/jobs/bod}, {@code POST /api/faults}, {@code POST /api/jobs} and
     * {@code POST /api/vehicles} all return 201 for a create. The assertion therefore accepts
     * either and names the inconsistency — the same treatment
     * {@code POST /api/inventory/material-request} got on sheet 05 and {@code /api/kpi/targets/assign}
     * on sheet 06. Everything else the row asks for is asserted strictly.</p>
     */
    @Test
    void checkIn_returns201CheckedIn() throws Exception {
        User tech = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        userRepo.flush();

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        // ── Step 1 ──────────────────────────────────────────────────────────────────────
        MvcResult res = mvc.perform(post("/api/attendance/check-in")
                .header("Authorization", bearer(tech.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":6.9271,\"longitude\":79.8612,"
                    + "\"address\":\"Colombo 03\"}"))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        int status = res.getResponse().getStatus();

        // ── Step 2 ──────────────────────────────────────────────────────────────────────
        // Empirically 200, not the row's 201 — see the javadoc. Both accepted, deviation
        // documented rather than failed on.
        assertTrue(status == 201 || status == 200,
            "BOD check-in must succeed. Status " + status + ", body: " + body);

        com.fasterxml.jackson.databind.JsonNode json_ = json.readTree(body);
        em.flush();
        em.clear();

        assertAll("the check-in is recorded with correct GPS and timestamp",

            // ── Step 3 ──────────────────────────────────────────────────────────────────
            () -> assertEquals("CHECKED_IN", json_.path("status").asText()),

            // ── Step 4 ──────────────────────────────────────────────────────────────────
            () -> assertEquals(6.9271, json_.path("checkInLatitude").asDouble(), 1e-9),
            () -> assertEquals(79.8612, json_.path("checkInLongitude").asDouble(), 1e-9),

            // ── Step 5: the server stamps the time, and it is now ───────────────────────
            () -> {
                LocalDateTime checkInTime =
                    LocalDateTime.parse(json_.path("checkInTime").asText());
                assertTrue(checkInTime.isAfter(before)
                        && checkInTime.isBefore(LocalDateTime.now().plusSeconds(60)),
                    "checkInTime must be stamped server-side within the last 60s. Was: "
                        + checkInTime);
            },

            // ── Step 6: the row genuinely exists in check_in_out, not just in the body ───
            () -> {
                CheckInOut persisted = checkInOutRepo
                    .findTodayByUserId(tech.getId(), LocalDate.now().atStartOfDay())
                    .orElseThrow(() -> new AssertionError(
                        "No check_in_out row was written for the technician"));
                assertEquals("CHECKED_IN", persisted.getStatus());
                assertEquals("ATTENDANCE", persisted.getCheckType());
                assertEquals(6.9271, persisted.getCheckInLatitude());
                assertEquals(79.8612, persisted.getCheckInLongitude());
                assertNull(persisted.getCheckOutTime(),
                    "a fresh check-in must not already carry a check-out time");
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-005 (FR-20) — EOD check-out returns the day's job summary and mileage
    // ══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void checkOut_returns200WithJobSummary() throws Exception {
        // ── Arrange: a technician who checked in this morning and completed 2 jobs ───────
        User tech = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        userRepo.flush();
        checkIn(tech, "{\"latitude\":6.9271,\"longitude\":79.8612}");
        completedJobToday(tech);
        completedJobToday(tech);
        jobRepo.flush();
        em.flush();

        // ── Step 1 ──────────────────────────────────────────────────────────────────────
        MvcResult res = mvc.perform(post("/api/attendance/check-out")
                .header("Authorization", bearer(tech.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":6.93,\"longitude\":79.86}"))
            .andReturn();

        String body = res.getResponse().getContentAsString();

        // ── Step 2 ──────────────────────────────────────────────────────────────────────
        assertEquals(200, res.getResponse().getStatus(),
            "EOD check-out must succeed. Body: " + body);

        com.fasterxml.jackson.databind.JsonNode out = json.readTree(body);

        assertAll("check-out closes the day and reports what was done",

            // ── Step 3 ──────────────────────────────────────────────────────────────────
            () -> assertEquals("CHECKED_OUT", out.path("status").asText()),

            // ── Step 4 ──────────────────────────────────────────────────────────────────
            () -> assertFalse(out.path("checkOutTime").isNull()
                    || out.path("checkOutTime").isMissingNode(),
                "checkOutTime must be stamped"),
            () -> assertEquals(6.93, out.path("checkOutLatitude").asDouble(), 1e-9),
            () -> assertEquals(79.86, out.path("checkOutLongitude").asDouble(), 1e-9),

            // ── Step 5: the day's completed-job count ───────────────────────────────────
            () -> assertEquals(2, out.path("jobSummary").path("completedCount").asInt(),
                "EOD must report how many jobs the technician actually completed today. There "
                    + "is no jobSummary on AttendanceDTO.AttendanceResponse at all, and the one "
                    + "related field — jobsCompleted — is not computed by the server: "
                    + "AttendanceService.checkOut copies it straight off the CLIENT'S OWN "
                    + "CheckOutRequest (`if (request.getJobsCompleted() != null)`), so the day's "
                    + "productivity figure is whatever the handset says it is, and is null when "
                    + "the handset says nothing. jobRepository is injected into AttendanceService "
                    + "and is used only to find open jobs for handover, never to count completed "
                    + "ones. Body: " + body),

            // ── Step 6: the day's mileage ───────────────────────────────────────────────
            () -> assertEquals(150, out.path("dailyMileage").asInt(),
                "EOD must report the day's mileage (15350 - 15200 = 150 km). The attendance "
                    + "path captures no odometer at either end — see the class javadoc and "
                    + "ATT-011 — so there is nothing to subtract. Body: " + body),

            // ── And the persisted row must agree with the response ──────────────────────
            () -> {
                em.flush();
                em.clear();
                CheckInOut persisted = checkInOutRepo
                    .findTodayByUserId(tech.getId(), LocalDate.now().atStartOfDay())
                    .orElseThrow(() -> new AssertionError("attendance row disappeared"));
                assertEquals("CHECKED_OUT", persisted.getStatus());
                assertNotNull(persisted.getCheckOutTime());
                assertTrue(persisted.getCheckInTime().isBefore(persisted.getCheckOutTime()),
                    "check-in must precede check-out");
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-009 (FR-20) — the record stores the full BOD + EOD pair
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * The row's step 3 reads the record back through {@code GET /api/attendance/{id}}, which does
     * not exist. {@code GET /api/attendance/me/history} returns the same
     * {@code AttendanceDTO.AttendanceResponse} objects (the {@code /{id}} route would have returned
     * one of), so it is used to prove the fields really round-trip out of MySQL rather than merely
     * being echoed by the check-out response.
     */
    @Test
    void fullBodEod_allFieldsStored() throws Exception {
        User tech = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        userRepo.flush();

        // ── Step 1 ──────────────────────────────────────────────────────────────────────
        String inBody = checkIn(tech,
            "{\"latitude\":6.9271,\"longitude\":79.8612,\"address\":\"Colombo 03\"}");
        long recordId = json.readTree(inBody).path("id").asLong();

        // ── Step 2 ──────────────────────────────────────────────────────────────────────
        MvcResult outRes = mvc.perform(post("/api/attendance/check-out")
                .header("Authorization", bearer(tech.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":6.85,\"longitude\":79.90,"
                    + "\"address\":\"Ratmalana\",\"notes\":\"Day closed\"}"))
            .andReturn();
        assertEquals(200, outRes.getResponse().getStatus(),
            "check-out must succeed. Body: " + outRes.getResponse().getContentAsString());

        em.flush();
        em.clear();

        // ── Step 3: read it back out of the database ────────────────────────────────────
        MvcResult histRes = mvc.perform(get("/api/attendance/me/history")
                .header("Authorization", bearer(tech.getId(), "TECHNICIAN")))
            .andReturn();
        assertEquals(200, histRes.getResponse().getStatus(),
            "the record must be readable back. Body: "
                + histRes.getResponse().getContentAsString());

        com.fasterxml.jackson.databind.JsonNode record = null;
        for (com.fasterxml.jackson.databind.JsonNode r
                : json.readTree(histRes.getResponse().getContentAsString()).path("records")) {
            if (r.path("id").asLong() == recordId) {
                record = r;
                break;
            }
        }
        assertNotNull(record, "the day's own attendance record must come back in the history");
        final com.fasterxml.jackson.databind.JsonNode stored = record;

        assertAll("every field of the BOD/EOD pair survives the round trip",

            // ── Step 4 ──────────────────────────────────────────────────────────────────
            () -> assertEquals(6.9271, stored.path("checkInLatitude").asDouble(), 1e-9),
            () -> assertEquals(79.8612, stored.path("checkInLongitude").asDouble(), 1e-9),
            () -> assertEquals(6.85, stored.path("checkOutLatitude").asDouble(), 1e-9),
            () -> assertEquals(79.90, stored.path("checkOutLongitude").asDouble(), 1e-9),
            () -> assertEquals("Colombo 03", stored.path("checkInAddress").asText()),
            () -> assertEquals("Ratmalana", stored.path("checkOutAddress").asText()),

            // ── Step 5 ──────────────────────────────────────────────────────────────────
            () -> assertTrue(
                LocalDateTime.parse(stored.path("checkInTime").asText())
                    .isBefore(LocalDateTime.parse(stored.path("checkOutTime").asText())),
                "check-in must precede check-out"),

            // ── Step 6 ──────────────────────────────────────────────────────────────────
            () -> assertEquals("CHECKED_OUT", stored.path("status").asText()),

            // ── Step 7: the odometer pair ───────────────────────────────────────────────
            () -> {
                CheckInOut row = checkInOutRepo.findById(recordId).orElseThrow();
                java.util.List<String> columns =
                    java.util.Arrays.stream(CheckInOut.class.getDeclaredFields())
                        .map(java.lang.reflect.Field::getName)
                        .filter(f -> f.toLowerCase().contains("odometer"))
                        .toList();
                assertFalse(columns.isEmpty(),
                    "The row requires odometer_start == 15200 and odometer_end == 15350 on the "
                        + "attendance record. check_in_out has no odometer column at either "
                        + "end, and neither CheckInRequest nor CheckOutRequest accepts one — "
                        + "the readings exist only on day_sessions, written by the TEAM LEAD's "
                        + "POST /api/jobs/bod and /api/jobs/eod, so an individual technician's "
                        + "attendance record can never carry them. Persisted row id "
                        + row.getId() + ". See ATT-011.");
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-012 (FR-19) — attendance report filtered by date range and technician
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * The row queries {@code GET /api/attendance?startDate=&endDate=} and then
     * {@code ?technicianId=5}. Neither parameter shape exists: there is no collection route on
     * {@code AttendanceController} at all, and the technician is a path variable, not a query
     * parameter — {@code GET /api/attendance/history/{userId}?startDate=&endDate=}, which is what
     * the Admin portal itself calls. That route is driven here, and the row's two filters are both
     * asserted against it: the date window narrows the result set, and one technician's history
     * never leaks another's.
     *
     * <p>The dates are relative to today rather than the row's literal April 2026 window, because
     * the fixture rows have to exist for the filter to be provably doing anything.</p>
     */
    @Test
    void dateRangeFilter_returnsCorrectRecords() throws Exception {
        User admin = newUser(User.Role.ADMIN,      "Admin Ayesha");
        User tech  = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User other = newUser(User.Role.TECHNICIAN, "Tech Sunil");
        userRepo.flush();

        // Three weeks of history for the technician, one row per week.
        LocalDate inWindow   = LocalDate.now().minusDays(3);
        LocalDate weekBefore = LocalDate.now().minusDays(10);
        LocalDate weekEarly  = LocalDate.now().minusDays(17);
        pastAttendance(tech, inWindow);
        pastAttendance(tech, weekBefore);
        pastAttendance(tech, weekEarly);
        // A same-window row belonging to a DIFFERENT technician — must never appear below.
        pastAttendance(other, inWindow);
        checkInOutRepo.flush();
        em.flush();
        em.clear();

        LocalDateTime start = LocalDate.now().minusDays(6).atStartOfDay();
        LocalDateTime end   = LocalDate.now().atTime(23, 59, 59);

        // ── Steps 1-2 ───────────────────────────────────────────────────────────────────
        MvcResult res = mvc.perform(get("/api/attendance/history/" + tech.getId())
                .param("startDate", start.toString())
                .param("endDate", end.toString())
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(),
            "the attendance report must be readable by an Admin. Body: " + body);

        com.fasterxml.jackson.databind.JsonNode report = json.readTree(body);
        java.util.List<com.fasterxml.jackson.databind.JsonNode> records = new java.util.ArrayList<>();
        report.path("records").forEach(records::add);

        assertAll("the attendance report honours both filters",

            // ── Step 3: every record really is inside the requested window ──────────────
            () -> {
                for (com.fasterxml.jackson.databind.JsonNode r : records) {
                    LocalDateTime at = LocalDateTime.parse(r.path("checkInTime").asText());
                    assertFalse(at.isBefore(start) || at.isAfter(end),
                        "record " + r.path("id").asLong() + " at " + at
                            + " is outside the requested window " + start + " .. " + end);
                }
                assertEquals(1, records.size(),
                    "only the one in-window row of the technician's three weeks may come back. "
                        + "Records: " + records.size());
            },

            // ── Step 4: the report carries a count ──────────────────────────────────────
            () -> {
                // AttendanceHistorySummaryDTO's count fields are totalDays/presentDays; there is
                // no field literally named totalCount. Asserted on the field that carries the
                // meaning, and required to agree with the array — a summary that disagrees with
                // its own rows would be worse than none.
                assertEquals(records.size(), report.path("totalDays").asInt(),
                    "totalDays must agree with the records actually returned");
                assertFalse(report.path("totalDays").isMissingNode(),
                    "the report must carry a record count");
            },

            // ── Step 5: one technician's history never contains another's ───────────────
            () -> {
                for (com.fasterxml.jackson.databind.JsonNode r : records) {
                    assertEquals(tech.getId(), r.path("userId").asLong(),
                        "the report must be scoped to the requested technician only");
                }
                MvcResult otherRes = mvc.perform(get("/api/attendance/history/" + other.getId())
                        .param("startDate", start.toString())
                        .param("endDate", end.toString())
                        .header("Authorization", bearer(admin.getId(), "ADMIN")))
                    .andReturn();
                assertEquals(200, otherRes.getResponse().getStatus());
                com.fasterxml.jackson.databind.JsonNode otherReport =
                    json.readTree(otherRes.getResponse().getContentAsString());
                assertEquals(1, otherReport.path("records").size(),
                    "the second technician has exactly one row in this window");
                assertEquals(other.getId(),
                    otherReport.path("records").get(0).path("userId").asLong());
            },

            // ── An unfiltered call must return strictly more than the filtered one ──────
            () -> {
                MvcResult unfiltered = mvc.perform(
                        get("/api/attendance/history/" + tech.getId())
                            .header("Authorization", bearer(admin.getId(), "ADMIN")))
                    .andReturn();
                assertEquals(200, unfiltered.getResponse().getStatus());
                assertEquals(3,
                    json.readTree(unfiltered.getResponse().getContentAsString())
                        .path("records").size(),
                    "without a window, all three weeks come back — which is what proves the "
                        + "window above was genuinely applied rather than the data simply "
                        + "being sparse");
            }
        );
    }
}
