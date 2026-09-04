package lk.slt.fieldops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.DaySession;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.DaySessionRepository;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 07_ATTENDANCE — ATT-014 and ATT-015 (FR-20 / SRS 5.4.2.1) — the Team Lead's EOD Routing Options
 * for whatever jobs are still open when the day closes.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module and adding a test framework is a project decision, not this suite's. MockMvc through the
 * real filter chain is the module's established convention ({@code JobIntegrationTest},
 * {@code BillDisputeAmendmentIntegrationTest}) and drives the identical path — real JWT filter,
 * real {@code @PreAuthorize}, real {@code JobService}, real MySQL. Both tests are
 * {@code @Transactional} so their rows roll back.</p>
 *
 * <p><b>Package placement.</b> The mapping gives a bare class name with no package. It is placed
 * in {@code lk.slt.fieldops.service} to sit beside the {@code JobService} method it exercises
 * ({@code performEod} → the private {@code routeOpenJobsAtEod}), the same reasoning
 * {@code JobServiceBodDuplicateTest} follows for the BOD half.</p>
 *
 * <p><b>Fixture shape.</b> Each routing option needs its own {@code day_sessions} row, and the
 * table has a unique constraint on {@code (team_lead_id, session_date)}, so each sub-scenario gets
 * its own Team Lead. Sessions are created directly rather than through {@code POST /api/jobs/bod}
 * so no vehicle/technician rows are needed and the EOD "all technicians checked out" gate — which
 * is a different row's subject — is trivially satisfied by a member-less session.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobServiceEodRoutingTest {

    @Autowired private MockMvc                mvc;
    @Autowired private JwtTokenProvider       jwt;
    @Autowired private UserRepository         userRepo;
    @Autowired private FaultRepository        faultRepo;
    @Autowired private JobRepository          jobRepo;
    @Autowired private DaySessionRepository   sessionRepo;
    @Autowired private FaultHistoryRepository historyRepo;
    @Autowired private ObjectMapper           json;
    @Autowired private jakarta.persistence.EntityManager em;

    /** Existing row the faults.branch_id FK (fk_faults_branch -> branches.id) requires. */
    private static final Long REAL_BRANCH_ID = 1L;

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
        u.setIsActive(true);
        return userRepo.save(u);
    }

    /** An open session for this Team Lead today, with no members so the checkout gate passes. */
    private DaySession newActiveSession(User lead) {
        DaySession s = new DaySession();
        s.setTeamLeadId(lead.getId());
        s.setSessionDate(LocalDate.now());
        s.setStatus(DaySession.SessionStatus.ACTIVE);
        s.setBodTime(LocalDateTime.now().minusHours(8));
        s.setBodOdometer(15200);
        return sessionRepo.save(s);
    }

    private Fault newAssignedFault(User client, User lead) {
        Fault f = new Fault();
        f.setFaultNumber("FLT-EOD-" + uniq());
        f.setCustomerId(client.getId());
        f.setCustomerName(client.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("No internet since 08:00 — EOD routing fixture");
        f.setLocationAddress("No. 5 Main Street, Colombo 03");
        f.setLocationCity("Colombo");
        f.setLatitude(6.9271);
        f.setLongitude(79.8612);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setPriority(Fault.FaultPriority.HIGH);
        f.setStatus(Fault.FaultStatus.IN_PROGRESS);
        f.setAssignedTeamLeadId(lead.getId());
        f.setAssignedTeamLeadName(lead.getFullName());
        f.setAssignedAt(LocalDateTime.now().minusHours(4));
        return faultRepo.save(f);
    }

    /** A job still open (IN_PROGRESS) inside the given session — exactly what EOD must route. */
    private Job newOpenJob(DaySession session, User lead, User tech, Fault fault, User client) {
        Job job = new Job();
        job.setJobNumber("JOB-EOD-" + uniq());
        job.setFaultId(fault.getId());
        job.setFaultNumber(fault.getFaultNumber());
        job.setSessionId(session.getId());
        job.setTeamLeadId(lead.getId());
        job.setTeamLeadName(lead.getFullName());
        job.setTechnicianId(tech.getId());
        job.setTechnicianName(tech.getFullName());
        job.setCustomerId(client.getId());
        job.setCustomerName(client.getFullName());
        job.setStatus(Job.JobStatus.IN_PROGRESS);
        job.setPriority(Job.JobPriority.HIGH);
        job.setScheduledDate(LocalDate.now());
        return jobRepo.save(job);
    }

    private void flushAndClear() {
        jobRepo.flush();
        faultRepo.flush();
        sessionRepo.flush();
        em.flush();
        em.clear();
    }

    private MvcResult eod(User lead, String rawBody) throws Exception {
        return mvc.perform(post("/api/jobs/eod")
                .header("Authorization", bearer(lead.getId(), "TEAM_LEAD"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody))
            .andReturn();
    }

    private List<String> eventTypesFor(Long faultId) {
        return historyRepo.findByFaultId(faultId).stream()
            .map(FaultHistory::getEventType)
            .toList();
    }

    /** One Team Lead, one open job on one assigned fault, ready to be routed at EOD. */
    private final class Scenario {
        final User      client = newUser(User.Role.CLIENT,     "Client Chandima");
        final User      lead   = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        final User      tech   = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        final DaySession session;
        final Fault     fault;
        final Job       job;

        Scenario() {
            session = newActiveSession(lead);
            fault   = newAssignedFault(client, lead);
            job     = newOpenJob(session, lead, tech, fault, client);
            flushAndClear();
        }

        Job   reloadJob()   { return jobRepo.findById(job.getId()).orElseThrow(); }
        Fault reloadFault() { return faultRepo.findById(fault.getId()).orElseThrow(); }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-014 — all three routing options must be genuinely distinct and separately auditable
    // ══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void allThreeOptionsDistinct() throws Exception {
        // Three separate open jobs, one per routing option — day_sessions is unique on
        // (team_lead_id, session_date), so each needs its own Team Lead.
        Scenario forward  = new Scenario();
        Scenario carry    = new Scenario();
        Scenario reassign = new Scenario();
        User newLead = newUser(User.Role.TEAM_LEAD, "Lead Sunil");
        flushAndClear();

        // ── Step 1: FORWARD_TO_ADMIN ────────────────────────────────────────────────────
        MvcResult forwardRes = eod(forward.lead,
            "{\"odometerEnd\":15350,\"notes\":\"Handing back to Admin\","
                + "\"routingOption\":\"FORWARD_TO_ADMIN\"}");
        assertEquals(200, forwardRes.getResponse().getStatus(),
            "EOD with FORWARD_TO_ADMIN must succeed. Body: "
                + forwardRes.getResponse().getContentAsString());
        assertEquals("FORWARD_TO_ADMIN",
            json.readTree(forwardRes.getResponse().getContentAsString())
                .path("routingOption").asText(),
            "the response must echo which option was applied");
        flushAndClear();

        // ── Step 3: CARRY_OVER ──────────────────────────────────────────────────────────
        MvcResult carryRes = eod(carry.lead,
            "{\"odometerEnd\":15350,\"notes\":\"Finishing tomorrow\","
                + "\"routingOption\":\"CARRY_OVER\"}");
        assertEquals(200, carryRes.getResponse().getStatus(),
            "EOD with CARRY_OVER must succeed. Body: "
                + carryRes.getResponse().getContentAsString());
        flushAndClear();

        // ── Step 4: REASSIGN_TEAM_LEAD ──────────────────────────────────────────────────
        MvcResult reassignRes = eod(reassign.lead,
            "{\"odometerEnd\":15350,\"notes\":\"Passing to Sunil\","
                + "\"routingOption\":\"REASSIGN_TEAM_LEAD\",\"reassignToTeamLeadId\":"
                + newLead.getId() + "}");
        assertEquals(200, reassignRes.getResponse().getStatus(),
            "EOD with REASSIGN_TEAM_LEAD must succeed. Body: "
                + reassignRes.getResponse().getContentAsString());
        flushAndClear();

        LocalDate tomorrow = LocalDate.now().plusDays(1);

        assertAll("the three EOD routing options are distinct and each is separately auditable",

            // ── Step 2: forwarded to the Admin queue, fault status reset ────────────────
            () -> {
                Job job = forward.reloadJob();
                Fault fault = forward.reloadFault();
                assertEquals(Job.JobStatus.CANCELLED, job.getStatus(),
                    "a forwarded job is closed out on this Team Lead's board");
                assertEquals("TEAM_LEAD", job.getRejectedByRole());
                assertNotNull(job.getRejectionReason());
                assertTrue(job.getRejectionReason().contains("Admin"),
                    "the reason must say where it went. Was: " + job.getRejectionReason());

                assertEquals(Fault.FaultStatus.REPORTED, fault.getStatus(),
                    "the fault must be reset to REPORTED — back in the Admin's unassigned "
                        + "queue, not left IN_PROGRESS under a Team Lead who has gone home");
                assertNull(fault.getAssignedTeamLeadId(),
                    "and genuinely unassigned, so Admin can hand it to someone else");
                assertNull(fault.getAssignedTeamLeadName());
                assertNull(fault.getAssignedAt());
            },

            // ── Step 3: carried over — same Team Lead, next day ─────────────────────────
            () -> {
                Job job = carry.reloadJob();
                Fault fault = carry.reloadFault();
                assertEquals(Job.JobStatus.PENDING, job.getStatus());
                assertEquals(tomorrow, job.getScheduledDate(),
                    "CARRY_OVER must bump scheduledDate to the next day");
                assertNull(job.getTechnicianId(),
                    "and release the technician so it can be re-dispatched tomorrow");
                assertEquals(carry.lead.getId(), job.getTeamLeadId(),
                    "but stay with the SAME Team Lead — that is what distinguishes it from "
                        + "REASSIGN_TEAM_LEAD");
                assertEquals(carry.lead.getId(), fault.getAssignedTeamLeadId(),
                    "and the fault stays assigned to them too");
            },

            // ── Step 4: reassigned — job AND fault transferred to the new Team Lead ─────
            () -> {
                Job job = reassign.reloadJob();
                Fault fault = reassign.reloadFault();
                assertEquals(newLead.getId(), job.getTeamLeadId(),
                    "the job must move to the new Team Lead");
                assertEquals(newLead.getFullName(), job.getTeamLeadName());
                assertEquals(Job.JobStatus.PENDING, job.getStatus());
                assertEquals(tomorrow, job.getScheduledDate());
                assertNull(job.getSessionId(),
                    "and be detached from the closed session");
                assertNull(job.getTechnicianId());
                assertEquals(newLead.getId(), fault.getAssignedTeamLeadId(),
                    "the fault's team ownership must transfer with it — 'job + team "
                        + "transferred', not just the job row");
                assertEquals(newLead.getFullName(), fault.getAssignedTeamLeadName());
            },

            // ── Expected Result: each option writes its OWN auditable FaultHistory row ──
            () -> {
                List<String> forwardEvents  = eventTypesFor(forward.fault.getId());
                List<String> carryEvents    = eventTypesFor(carry.fault.getId());
                List<String> reassignEvents = eventTypesFor(reassign.fault.getId());

                assertTrue(forwardEvents.contains("EOD_FORWARDED_TO_ADMIN"),
                    "FORWARD_TO_ADMIN must be auditable in its own right. Events: "
                        + forwardEvents);
                assertTrue(carryEvents.contains("EOD_CARRY_OVER"),
                    "CARRY_OVER must be auditable in its own right. Events: " + carryEvents);
                assertTrue(reassignEvents.contains("EOD_REASSIGNED_TEAM_LEAD"),
                    "REASSIGN_TEAM_LEAD must be auditable in its own right. Events: "
                        + reassignEvents);

                // The point of the row: three DIFFERENT event types, not one generic
                // "returned to pool" entry three times.
                assertEquals(3,
                    java.util.Set.of("EOD_FORWARDED_TO_ADMIN", "EOD_CARRY_OVER",
                        "EOD_REASSIGNED_TEAM_LEAD").size(),
                    "the three options must not collapse into one audit vocabulary");
                assertFalse(carryEvents.contains("EOD_FORWARDED_TO_ADMIN"),
                    "a carried-over job must not be audited as forwarded to Admin");
                assertFalse(forwardEvents.contains("EOD_CARRY_OVER"),
                    "a forwarded job must not be audited as carried over");
            },

            // ── The reason for the transfer must be carried onto the audit row ──────────
            () -> {
                FaultHistory reassignRow = historyRepo.findByFaultId(reassign.fault.getId())
                    .stream()
                    .filter(h -> "EOD_REASSIGNED_TEAM_LEAD".equals(h.getEventType()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                        "no EOD_REASSIGNED_TEAM_LEAD row to inspect"));
                assertEquals(reassign.lead.getFullName(), reassignRow.getPreviousValue(),
                    "the audit row must record who it came from");
                assertEquals(newLead.getFullName(), reassignRow.getNewValue(),
                    "and who it went to");
                assertTrue(reassignRow.getDescription() != null
                        && reassignRow.getDescription().contains("Passing to Sunil"),
                    "SRS 5.4.2.1 requires a reason for the transfer; the EOD notes are it. "
                        + "Description was: " + reassignRow.getDescription());
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // ATT-015 — omitting routingOption must behave exactly as the pre-feature default
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Backward compatibility for a mobile client that predates the routing options and sends a
     * body with no {@code routingOption} field at all. Two EODs are run — one omitting the field,
     * one sending {@code CARRY_OVER} explicitly — and their outcomes are compared field by field,
     * rather than only re-asserting CARRY_OVER's own contract.
     */
    @Test
    void defaultsToCarryOver() throws Exception {
        Scenario omitted  = new Scenario();
        Scenario explicit = new Scenario();
        flushAndClear();

        // ── Step 1: the old client's body — no routingOption field at all ───────────────
        MvcResult omittedRes = eod(omitted.lead, "{\"odometerEnd\":15350}");
        assertEquals(200, omittedRes.getResponse().getStatus(),
            "an EOD body with no routingOption must still be accepted. Body: "
                + omittedRes.getResponse().getContentAsString());
        flushAndClear();

        MvcResult explicitRes = eod(explicit.lead,
            "{\"odometerEnd\":15350,\"routingOption\":\"CARRY_OVER\"}");
        assertEquals(200, explicitRes.getResponse().getStatus());
        flushAndClear();

        Job omittedJob  = omitted.reloadJob();
        Job explicitJob = explicit.reloadJob();

        assertAll("omitting routingOption behaves identically to CARRY_OVER",
            () -> assertEquals("CARRY_OVER",
                json.readTree(omittedRes.getResponse().getContentAsString())
                    .path("routingOption").asText(),
                "the response must name the applied default so the caller is not left guessing"),
            () -> assertEquals(explicitJob.getStatus(), omittedJob.getStatus(),
                "same job status as an explicit CARRY_OVER"),
            () -> assertEquals(explicitJob.getScheduledDate(), omittedJob.getScheduledDate(),
                "same next-day scheduledDate as an explicit CARRY_OVER"),
            () -> assertEquals(LocalDate.now().plusDays(1), omittedJob.getScheduledDate()),
            () -> assertNull(omittedJob.getTechnicianId(),
                "same technician release as an explicit CARRY_OVER"),
            () -> assertEquals(omitted.lead.getId(), omittedJob.getTeamLeadId(),
                "and still owned by the same Team Lead — not forwarded anywhere"),
            () -> assertEquals(Fault.FaultStatus.IN_PROGRESS,
                omitted.reloadFault().getStatus(),
                "the fault is NOT reset to REPORTED, which is what FORWARD_TO_ADMIN would do"),
            () -> assertEquals(
                eventTypesFor(explicit.fault.getId()).stream()
                    .filter(e -> e.startsWith("EOD_")).toList(),
                eventTypesFor(omitted.fault.getId()).stream()
                    .filter(e -> e.startsWith("EOD_")).toList(),
                "and the same audit event is written"),
            () -> assertEquals(DaySession.SessionStatus.CLOSED,
                sessionRepo.findById(omitted.session.getId()).orElseThrow().getStatus(),
                "the session still closes")
        );
    }
}
