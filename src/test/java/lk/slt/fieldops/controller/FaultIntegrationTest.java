package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.LocationUpdateRequest;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.dto.UpdateFaultRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.TechnicianLocation;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.TechnicianLocationRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * FAULT-001, FAULT-007, FAULT-009 and FAULT-010 (02_FAULT_TRACKING, FR-4 / FR-5) — the fault
 * reporting + real-time status/timeline/tracking REST surface.
 *
 * <p><b>Tool substitution.</b> The sheet's Tool column says REST Assured; REST Assured is not a
 * dependency of this module ({@code pom.xml} carries only {@code spring-boot-starter-test} +
 * {@code spring-security-test}) and adding a new test framework is a project decision, not this
 * suite's. MockMvc through the real filter chain is the module's established integration
 * convention ({@link BillDisputeAmendmentIntegrationTest}) and exercises the identical code path:
 * real JWT filter, real {@code @PreAuthorize}, real service, real MySQL.</p>
 *
 * <p>Each test is {@code @Transactional} so every row created here rolls back and never pollutes
 * the shared dev database.</p>
 *
 * <p><b>Endpoint reality vs. the sheet.</b> Two rows name URLs that do not exist in this codebase;
 * each test below drives the real endpoint that implements the described behaviour and says so:
 * <ul>
 *   <li>FAULT-001 expects {@code status == 'OPEN'}. The persisted enum value is
 *       {@code REPORTED}; {@code FaultDTO.getStatusDisplay()} is the field that maps it to
 *       {@code OPEN} for consumers, so both are asserted.</li>
 *   <li>FAULT-010 names {@code GET /api/faults/{id}/tracking}, which does not exist. The real
 *       client-facing technician-tracking endpoint is
 *       {@code GET /api/issues/{id}/technician-location} ({@link IssueController}), which returns
 *       the technician's live GPS, name and ETA. Its ETA field is named {@code eta} and is a
 *       {@code "N mins"} string rather than a numeric {@code estimatedArrivalMinutes}.</li>
 * </ul></p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FaultIntegrationTest {

    @Autowired private MockMvc                      mvc;
    @Autowired private JwtTokenProvider             jwt;
    @Autowired private FaultRepository              faultRepo;
    @Autowired private JobRepository                jobRepo;
    @Autowired private TechnicianLocationRepository locationRepo;
    @Autowired private UserRepository               userRepo;
    @Autowired private ObjectMapper                 json;
    @Autowired private jakarta.persistence.EntityManager em;

    /** Existing row the faults.branch_id FK (fk_faults_branch -> branches.id) requires. */
    private static final Long REAL_BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_BRANCH_ID);
    }

    /** faults.customer_id is an FK to users, so the reporter must be a real persisted row. */
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

    private ReportFaultRequest reportRequest() {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");          // normalizeMobileCategory maps this to INTERNET
        req.setDescription("No internet since 08:00");
        req.setLocationAddress("No. 5 Main Street, Colombo 03");
        req.setLocationCity("Colombo");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(REAL_BRANCH_ID);
        req.setPriority("HIGH");
        return req;
    }

    private UpdateFaultRequest statusChange(String newStatus) {
        UpdateFaultRequest req = new UpdateFaultRequest();
        req.setNewStatus(newStatus);
        return req;
    }

    /** Reports a fault through the real endpoint and returns its id. */
    private Long reportFaultAs(User client) throws Exception {
        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reportRequest())))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus(),
            "Fault setup POST failed. Body: " + res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void flushAndClear() {
        faultRepo.flush();
        em.clear();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FAULT-001 — POST /api/faults returns 201 with an OPEN fault persisted
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void createFault_validPayload_returns201() throws Exception {
        User client = newUser(User.Role.CLIENT, "Test Client");

        // ── Steps 1-2: POST the payload and assert HTTP 201 ────────────────────────────────
        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reportRequest())))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(201, res.getResponse().getStatus(),
            "A valid fault report must be 201 Created. Body: " + body);

        JsonNode created = json.readTree(body);

        // ── Step 3: the fault is open ─────────────────────────────────────────────────────
        // The persisted enum is REPORTED; statusDisplay is the field that renders it as OPEN.
        assertEquals("OPEN", created.get("statusDisplay").asText(),
            "A newly reported fault must be client-visible as OPEN. Body: " + body);
        assertEquals("REPORTED", created.get("status").asText(),
            "The persisted status enum for a new fault is REPORTED. Body: " + body);

        // ── Step 4: id present ────────────────────────────────────────────────────────────
        assertFalse(created.get("id").isNull(), "Created fault must carry a non-null id");
        Long faultId = created.get("id").asLong();
        assertTrue(faultId > 0, "Created fault id must be a real generated key, was " + faultId);

        // ── Step 5: the row really is in the database ─────────────────────────────────────
        flushAndClear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow(
            () -> new AssertionError("No faults row was created for id " + faultId));
        assertEquals(Fault.FaultStatus.REPORTED, persisted.getStatus());
        assertEquals(Fault.FaultCategory.INTERNET, persisted.getCategory(),
            "FaultController.normalizeMobileCategory maps the mobile name BROADBAND to INTERNET");
        assertEquals(Fault.FaultPriority.HIGH, persisted.getPriority());
        assertEquals(client.getId(), persisted.getCustomerId());
        assertEquals(6.9271, persisted.getLatitude(), 0.00001);
        assertEquals(79.8612, persisted.getLongitude(), 0.00001);
        assertEquals("No internet since 08:00", persisted.getDescription());
        assertNotNull(persisted.getFaultNumber(), "A fault number must be generated");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FAULT-007 — GET /api/faults/{id} always reflects the current status
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getFault_statusReflectsCurrentState() throws Exception {
        User client = newUser(User.Role.CLIENT, "Tracking Client");
        User admin  = newUser(User.Role.ADMIN,  "Status Admin");
        Long faultId = reportFaultAs(client);
        flushAndClear();

        // Drive the fault to IN_PROGRESS through the real transition guard
        // (REPORTED -> ASSIGNED -> IN_PROGRESS; FaultService.validateTransition allows no shortcut).
        for (String next : new String[] {"ASSIGNED", "IN_PROGRESS"}) {
            MvcResult patch = mvc.perform(patch("/api/faults/{id}/status", faultId)
                    .header("Authorization", bearer(admin.getId(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(statusChange(next))))
                .andReturn();
            assertEquals(200, patch.getResponse().getStatus(),
                "Transition to " + next + " must succeed. Body: " + patch.getResponse().getContentAsString());
        }
        flushAndClear();

        // ── Steps 1-3: GET returns the CURRENT status, not the state at creation ──────────
        MvcResult get = mvc.perform(get("/api/faults/{id}", faultId)
                .header("Authorization", bearer(client.getId(), "CLIENT")))
            .andReturn();

        String body = get.getResponse().getContentAsString();
        assertEquals(200, get.getResponse().getStatus(), "Body: " + body);
        assertEquals("IN_PROGRESS", json.readTree(body).get("status").asText(),
            "GET must reflect the current status after the PATCH, not a stale REPORTED. Body: " + body);

        // ── Steps 4-6: change it again and re-read — still no stale data ─────────────────
        mvc.perform(patch("/api/faults/{id}/status", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(statusChange("COMPLETED"))))
            .andReturn();
        flushAndClear();

        MvcResult reread = mvc.perform(get("/api/faults/{id}", faultId)
                .header("Authorization", bearer(client.getId(), "CLIENT")))
            .andReturn();
        String rereadBody = reread.getResponse().getContentAsString();
        assertEquals(200, reread.getResponse().getStatus(), "Body: " + rereadBody);
        assertEquals("COMPLETED", json.readTree(rereadBody).get("status").asText(),
            "The second read must reflect the second status change. Body: " + rereadBody);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FAULT-009 — GET /api/faults/{id}/timeline is chronological and complete
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getFaultTimeline_chronologicalOrder() throws Exception {
        User client = newUser(User.Role.CLIENT, "Timeline Client");
        User admin  = newUser(User.Role.ADMIN,  "Timeline Admin");
        Long faultId = reportFaultAs(client);

        // Let the fault's stored creation second elapse before making any status change. Without
        // this the ordering assertion below is a coin flip, because faults.created_at is a
        // second-precision MySQL DATETIME that rounds to the NEAREST second while
        // fault_history.created_at keeps microseconds — see the precision sub-check at the end.
        flushAndClear();
        LocalDateTime storedCreatedAt = faultRepo.findById(faultId).orElseThrow().getCreatedAt();
        while (!LocalDateTime.now().isAfter(storedCreatedAt.plusSeconds(1))) {
            Thread.sleep(20);
        }

        // Three status changes total: REPORTED (on create) -> ASSIGNED -> IN_PROGRESS.
        for (String next : new String[] {"ASSIGNED", "IN_PROGRESS"}) {
            mvc.perform(patch("/api/faults/{id}/status", faultId)
                    .header("Authorization", bearer(admin.getId(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(statusChange(next))))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus(),
                    "Transition to " + next + " failed: " + result.getResponse().getContentAsString()));
        }
        flushAndClear();

        // ── Steps 1-2 ─────────────────────────────────────────────────────────────────────
        MvcResult res = mvc.perform(get("/api/faults/{id}/timeline", faultId)
                .header("Authorization", bearer(client.getId(), "CLIENT")))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);

        // The endpoint returns a bare JSON array, not an {events:[...]} envelope.
        JsonNode events = json.readTree(body);
        assertTrue(events.isArray(), "Timeline must be a JSON array. Body: " + body);

        // ── Step 3: at least the three status-change rows ────────────────────────────────
        assertTrue(events.size() >= 3,
            "Expected at least 3 timeline events, got " + events.size() + ". Body: " + body);

        // ── Step 4: sorted by timestamp ascending ────────────────────────────────────────
        for (int i = 1; i < events.size(); i++) {
            String previous = events.get(i - 1).get("timestamp").asText();
            String current  = events.get(i).get("timestamp").asText();
            assertTrue(previous.compareTo(current) <= 0,
                "Timeline must be ascending by timestamp, but event " + (i - 1) + " (" + previous
                    + ") is after event " + i + " (" + current + "). Body: " + body);
        }

        assertAll("timeline ordering and required fields",

            // ── Step 5a: the first entry is the creation event ───────────────────────────
            // FaultAssignmentService synthesises it with eventType FAULT_CREATED (the sheet
            // writes it as 'CREATED') and sorts the whole list by timestamp.
            () -> assertEquals("FAULT_CREATED", events.get(0).get("eventType").asText(),
                "The first timeline event must be the fault creation. Body: " + body),

            // ── Step 5b: the last entry is the most recent status change ─────────────────
            () -> assertEquals("IN_PROGRESS",
                events.get(events.size() - 1).get("newValue").asText(),
                "The last event must be the IN_PROGRESS status change. Body: " + body),

            // ── Step 6: every event carries eventType, timestamp and actorName ───────────
            () -> {
                for (int i = 0; i < events.size(); i++) {
                    JsonNode event = events.get(i);
                    assertFalse(event.get("eventType").isNull(), "Event " + i + " has no eventType: " + event);
                    assertFalse(event.get("timestamp").isNull(), "Event " + i + " has no timestamp: " + event);
                    assertFalse(event.get("actorName").isNull(), "Event " + i + " has no actorName: " + event);
                }
            },

            // ── The precondition that makes step 4's ordering guarantee hold at all ──────
            // The two timestamps the timeline sorts by are stored at different precisions:
            // fault_history.created_at is a microsecond DATETIME(6), faults.created_at is a plain
            // second-precision DATETIME that MySQL rounds to the NEAREST second. A fault reported
            // at 13:17:47.689 therefore reads back as 13:17:48 — later than the very history row
            // written for its own creation — and the synthesised FAULT_CREATED event sorts to the
            // END of the timeline. Any fault whose first status change lands in the same second as
            // the report (auto-assignment, bulk assign, or any automated flow) gets a
            // chronologically wrong timeline. The wait in this test's arrange step is what keeps
            // the assertions above deterministic; this sub-check records the underlying defect.
            () -> assertNotEquals(0,
                faultRepo.findById(faultId).orElseThrow().getCreatedAt().getNano(),
                "faults.created_at must retain sub-second precision for the timeline sort to be "
                    + "reliable. It is stored as a second-precision DATETIME and rounds to the "
                    + "nearest second, so the fault-creation event can sort after status changes "
                    + "made in the same second")
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FAULT-010 — client tracks the assigned technician, with a calculated ETA
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getFaultTracking_etaCalculated() throws Exception {
        User client = newUser(User.Role.CLIENT,     "ETA Client");
        User tech   = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User lead   = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");

        // Fault at (6.9271, 79.8612), assigned so the tracking guard passes.
        Long faultId = reportFaultAs(client);
        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setAssignedTeamLeadId(lead.getId());
        fault.setAssignedTeamLeadName(lead.getFullName());
        fault.setStatus(Fault.FaultStatus.ASSIGNED);
        faultRepo.save(fault);

        // An active (TRAVELLING) job links this client to this technician — the exact condition
        // LocationService.assertCanViewTechnicianLocation requires for a CLIENT caller.
        Job job = new Job();
        job.setJobNumber("JOB-TEST-" + uniq());
        job.setFaultId(faultId);
        job.setFaultNumber(fault.getFaultNumber());
        job.setTeamLeadId(lead.getId());
        job.setTeamLeadName(lead.getFullName());
        job.setTechnicianId(tech.getId());
        job.setTechnicianName(tech.getFullName());
        job.setCustomerId(client.getId());
        job.setCustomerName(client.getFullName());
        job.setStatus(Job.JobStatus.TRAVELLING);
        jobRepo.save(job);

        // Technician GPS ping at (6.9500, 79.8700) — ~2.7km from the fault, so a non-zero ETA.
        locationRepo.save(TechnicianLocation.builder()
            .user(tech)
            .latitude(6.9500)
            .longitude(79.8700)
            .address("Borella")
            .technicianStatus(TechnicianLocation.TechnicianStatus.TRAVELLING)
            .isActive(true)
            .lastUpdated(LocalDateTime.now())
            .build());
        flushAndClear();

        // ── Steps 1-2 ─────────────────────────────────────────────────────────────────────
        MvcResult res = mvc.perform(get("/api/issues/{id}/technician-location", faultId)
                .header("Authorization", bearer(client.getId(), "CLIENT")))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        JsonNode tracking = json.readTree(body);

        // ── Step 3: the technician's live latitude is present ────────────────────────────
        assertTrue(tracking.hasNonNull("latitude"),
            "Tracking must carry the technician's live latitude. Body: " + body);
        assertEquals(6.9500, tracking.get("latitude").asDouble(), 0.0001);
        assertEquals(79.8700, tracking.get("longitude").asDouble(), 0.0001);

        // ── Step 4: an ETA greater than zero ─────────────────────────────────────────────
        // The endpoint returns eta as a "N mins" string (LocationService.getTechnicianLocationWithEta),
        // not the numeric estimatedArrivalMinutes the sheet names.
        assertTrue(tracking.hasNonNull("eta"), "Tracking must carry an ETA. Body: " + body);
        String eta = tracking.get("eta").asText();
        assertNotEquals("Unknown", eta,
            "ETA must be calculated, not 'Unknown', when the technician has a live GPS fix. Body: " + body);
        int etaMinutes = Integer.parseInt(eta.replaceAll("[^0-9]", ""));
        assertTrue(etaMinutes > 0,
            "estimatedArrival must be > 0 minutes for a technician 2.7km away, was '" + eta + "'");

        // ── Step 5: the technician's name ────────────────────────────────────────────────
        assertEquals("Tech Nimal", tracking.get("technicianName").asText(),
            "Tracking must name the assigned technician. Body: " + body);
    }
}
