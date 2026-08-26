package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.dto.UpdateJobRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * JOB-002, JOB-003, JOB-004 and JOB-014 (03_JOB_LIFECYCLE, FR-8) — the job status-transition REST
 * surface: accept, the full lifecycle to COMPLETED, rejection of an illegal back-transition, and
 * work notes.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module ({@code pom.xml} carries only {@code spring-boot-starter-test} and
 * {@code spring-security-test}) and adding a test framework is a project decision, not this
 * suite's. MockMvc through the real filter chain is the module's established convention (see
 * {@link BillDisputeAmendmentIntegrationTest}, {@link FaultIntegrationTest}) and drives the
 * identical path: real JWT filter, real {@code @PreAuthorize}, real service, real MySQL. Every
 * test is {@code @Transactional} so its rows roll back.</p>
 *
 * <p><b>Status vocabulary vs. the sheet.</b> The sheet writes the starting status as
 * {@code ASSIGNED}. {@code Job.JobStatus} has no such constant — a dispatched job starts
 * {@code PENDING} ({@code JobService.createJob}); {@code ASSIGNED} belongs to
 * {@code Fault.FaultStatus}. The sheet also goes {@code ACCEPTED -> IN_PROGRESS} directly; the
 * implemented machine requires {@code ACCEPTED -> TRAVELLING -> IN_PROGRESS}
 * ({@code JobService.validateJobTransition}), mirroring the technician app's
 * accept/travel/arrive/work flow. Both are asserted as implemented.</p>
 *
 * <p><b>Request shape.</b> The sheet sends {@code {"status": "..."}}. The DTO field is
 * {@code newStatus}, with {@code @JsonAlias("status")} — so the sheet's literal body genuinely
 * works, and JOB-002 sends it verbatim to prove that alias is real.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobIntegrationTest {

    @Autowired private MockMvc                mvc;
    @Autowired private JwtTokenProvider       jwt;
    @Autowired private JobRepository          jobRepo;
    @Autowired private FaultRepository        faultRepo;
    @Autowired private FaultHistoryRepository historyRepo;
    @Autowired private UserRepository         userRepo;
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
        return userRepo.save(u);
    }

    private ReportFaultRequest reportRequest() {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");
        req.setDescription("No internet since 08:00");
        req.setLocationAddress("No. 5 Main Street, Colombo 03");
        req.setLocationCity("Colombo");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(REAL_BRANCH_ID);
        req.setPriority("HIGH");
        return req;
    }

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
        jobRepo.flush();
        faultRepo.flush();
        em.clear();
    }

    /** Fixture: a client, a team lead, a technician, a reported fault, and a PENDING job on it. */
    private final class Fixture {
        final User client   = newUser(User.Role.CLIENT,     "Client Chandima");
        final User lead     = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        final User tech     = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        final Long faultId;
        final Long jobId;

        Fixture() throws Exception {
            faultId = reportFaultAs(client);
            Fault fault = faultRepo.findById(faultId).orElseThrow();
            fault.setAssignedTeamLeadId(lead.getId());
            fault.setAssignedTeamLeadName(lead.getFullName());
            fault.setStatus(Fault.FaultStatus.IN_PROGRESS);
            faultRepo.save(fault);

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
            job.setStatus(Job.JobStatus.PENDING);
            job.setPriority(Job.JobPriority.HIGH);
            jobId = jobRepo.save(job).getId();
            flushAndClear();
        }

        String techBearer()   { return bearer(tech.getId(), "TECHNICIAN"); }
        String clientBearer() { return bearer(client.getId(), "CLIENT"); }
        Job reload()          { return jobRepo.findById(jobId).orElseThrow(); }
    }

    /** PATCHes a status using the sheet's own {"status": ...} body shape. */
    private MvcResult patchStatusRaw(Long jobId, String bearer, String rawBody) throws Exception {
        return mvc.perform(patch("/api/jobs/{id}/status", jobId)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody))
            .andReturn();
    }

    private MvcResult patchStatus(Long jobId, String bearer, UpdateJobRequest req) throws Exception {
        return patchStatusRaw(jobId, bearer, json.writeValueAsString(req));
    }

    private static UpdateJobRequest to(String status) {
        UpdateJobRequest req = new UpdateJobRequest();
        req.setNewStatus(status);
        return req;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOB-002 — PENDING ("ASSIGNED" in the sheet) -> ACCEPTED
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void patchStatus_assignedToAccepted() throws Exception {
        Fixture f = new Fixture();

        // ── Step 1: the sheet's literal body, proving the "status" JsonAlias works ───────
        MvcResult res = patchStatusRaw(f.jobId, f.techBearer(), "{\"status\":\"ACCEPTED\"}");
        String body = res.getResponse().getContentAsString();

        // ── Steps 2-3 ────────────────────────────────────────────────────────────────────
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        assertEquals("ACCEPTED", json.readTree(body).get("status").asText());

        flushAndClear();
        Job accepted = f.reload();
        assertEquals(Job.JobStatus.ACCEPTED, accepted.getStatus(),
            "The persisted job, not just the response, must be ACCEPTED");
        assertNotNull(accepted.getAcceptedAt(), "accepted_at must be stamped on acceptance");

        // ── Step 4: an audit row for the transition ──────────────────────────────────────
        List<FaultHistory> history = historyRepo.findAll().stream()
            .filter(h -> h.getFault() != null && f.faultId.equals(h.getFault().getId()))
            .toList();
        boolean hasAcceptRow = history.stream().anyMatch(h ->
            (h.getNewValue() != null && h.getNewValue().contains("ACCEPTED"))
                || (h.getEventType() != null && h.getEventType().contains("ACCEPT")));
        assertTrue(hasAcceptRow,
            "JOB-002 step 4 requires a fault_history row for the ACCEPTED transition. "
                + "JobService.updateJobStatus writes no history for any job status change — the "
                + "only job-related history rows are the three EOD routing events in "
                + "routeOpenJobsAtEod. Rows found for this fault: "
                + history.stream().map(FaultHistory::getEventType).toList()
                + ". PRODUCTION CHANGE REQUIRED (audit the job status transitions the same way "
                + "FaultService.updateStatus already audits fault transitions).");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOB-003 — full lifecycle through to COMPLETED
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void fullLifecycle_assignedToCompleted() throws Exception {
        Fixture f = new Fixture();
        String tech = f.techBearer();

        // ── Step 1: -> ACCEPTED ──────────────────────────────────────────────────────────
        MvcResult accepted = patchStatus(f.jobId, tech, to("ACCEPTED"));
        assertEquals(200, accepted.getResponse().getStatus(),
            "Body: " + accepted.getResponse().getContentAsString());

        // ── Step 1b: -> TRAVELLING. Not in the sheet, but the implemented machine has no
        //             ACCEPTED -> IN_PROGRESS edge; the technician app travels then arrives.
        MvcResult travelling = patchStatus(f.jobId, tech, to("TRAVELLING"));
        assertEquals(200, travelling.getResponse().getStatus(),
            "Body: " + travelling.getResponse().getContentAsString());

        // ── Step 2: -> IN_PROGRESS, started_at set ───────────────────────────────────────
        MvcResult started = patchStatus(f.jobId, tech, to("IN_PROGRESS"));
        assertEquals(200, started.getResponse().getStatus(),
            "Body: " + started.getResponse().getContentAsString());
        flushAndClear();
        assertNotNull(f.reload().getStartedAt(), "started_at must be stamped on IN_PROGRESS");

        // ── Steps 3-4: after-photo attached, then -> COMPLETED ───────────────────────────
        UpdateJobRequest complete = to("COMPLETED");
        complete.setWorkNotes("Fixed");
        complete.setCauseOfFault("Damaged drop wire");
        complete.setCompletionRemarks("Replaced 15m cable, tested OK");
        complete.setCompletionPhotoUrls("/uploads/photos/after-1.jpg");
        MvcResult completed = patchStatus(f.jobId, tech, complete);
        assertEquals(200, completed.getResponse().getStatus(),
            "Body: " + completed.getResponse().getContentAsString());

        // ── Step 5: completed_at set, readable back through GET ──────────────────────────
        flushAndClear();
        Job finalJob = f.reload();
        assertEquals(Job.JobStatus.COMPLETED, finalJob.getStatus());
        assertNotNull(finalJob.getCompletedAt(), "completed_at must be stamped on completion");
        assertEquals("Fixed", finalJob.getWorkNotes());

        MvcResult read = mvc.perform(get("/api/jobs/{id}", f.jobId)
                .header("Authorization", tech))
            .andReturn();
        assertEquals(200, read.getResponse().getStatus());
        var readJson = json.readTree(read.getResponse().getContentAsString());
        assertEquals("COMPLETED", readJson.get("status").asText());
        assertFalse(readJson.get("completedAt").isNull(), "completedAt must be exposed on GET");

        // ── Step 6: the fault is synced to COMPLETED ─────────────────────────────────────
        assertEquals(Fault.FaultStatus.COMPLETED, faultRepo.findById(f.faultId).orElseThrow().getStatus(),
            "Completing the job must sync the linked fault to COMPLETED");

        // ── Step 7: three history rows for the three transitions ─────────────────────────
        long jobTransitionRows = historyRepo.findAll().stream()
            .filter(h -> h.getFault() != null && f.faultId.equals(h.getFault().getId()))
            .filter(h -> "STATUS_CHANGED".equals(h.getEventType()))
            .count();
        assertTrue(jobTransitionRows >= 3,
            "JOB-003 step 7 requires an audit row per job status transition (ACCEPTED, "
                + "IN_PROGRESS, COMPLETED). Found " + jobTransitionRows + ". "
                + "JobService.updateJobStatus writes no fault_history at all. "
                + "PRODUCTION CHANGE REQUIRED (same gap as JOB-002).");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mobile "What did you find?" field (QA_Compliance_Consolidated_Report.md) —
    // causeOfFault was fully wired end-to-end in JobService/FaultService (see the
    // COMPLETED branch above, already exercised by JOB-003) but had zero UI presence
    // on any platform, so the wiring was never actually exercised by a real user
    // action. JOB-003 itself sends causeOfFault in its completion request but never
    // asserts it persisted anywhere — this test closes that gap directly.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void causeOfFault_persistsToFaultAndIsReadableOnFreshGet() throws Exception {
        Fixture f = new Fixture();
        String tech = f.techBearer();

        assertEquals(200, patchStatus(f.jobId, tech, to("ACCEPTED")).getResponse().getStatus());
        assertEquals(200, patchStatus(f.jobId, tech, to("TRAVELLING")).getResponse().getStatus());
        assertEquals(200, patchStatus(f.jobId, tech, to("IN_PROGRESS")).getResponse().getStatus());

        UpdateJobRequest complete = to("COMPLETED");
        complete.setCauseOfFault("Loose connector at the ONT");
        complete.setCompletionPhotoUrls("/uploads/photos/after-1.jpg");
        MvcResult completed = patchStatus(f.jobId, tech, complete);
        assertEquals(200, completed.getResponse().getStatus(),
            "Body: " + completed.getResponse().getContentAsString());
        flushAndClear();

        // ── The persisted row, not just the write's own response ────────────────────────
        assertEquals("Loose connector at the ONT", f.reload().getCauseOfFault(),
            "Job.causeOfFault must persist from the completion request");
        assertEquals("Loose connector at the ONT",
            faultRepo.findById(f.faultId).orElseThrow().getCauseOfFault(),
            "Fault.causeOfFault must persist — synced from the job completion the same way "
                + "status/completedAt/completionRemarks already are (JobService.updateJobStatus)");

        // ── A fresh, independent GET confirms it's actually durable, not just an in-memory
        //    artifact of this same request/transaction ─────────────────────────────────
        MvcResult read = mvc.perform(get("/api/faults/{id}", f.faultId)
                .header("Authorization", tech))
            .andReturn();
        assertEquals(200, read.getResponse().getStatus(), "Body: " + read.getResponse().getContentAsString());
        var readJson = json.readTree(read.getResponse().getContentAsString());
        assertEquals("Loose connector at the ONT", readJson.get("causeOfFault").asText(),
            "A fresh GET /api/faults/{id} must expose the persisted causeOfFault, not just the "
                + "PATCH response. Body: " + read.getResponse().getContentAsString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOB-004 — illegal back-transition from COMPLETED
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void invalidTransition_completedToInProgress_400() throws Exception {
        Fixture f = new Fixture();
        String tech = f.techBearer();

        // Drive the job to COMPLETED through the real machine first.
        assertEquals(200, patchStatus(f.jobId, tech, to("ACCEPTED")).getResponse().getStatus());
        assertEquals(200, patchStatus(f.jobId, tech, to("TRAVELLING")).getResponse().getStatus());
        assertEquals(200, patchStatus(f.jobId, tech, to("IN_PROGRESS")).getResponse().getStatus());
        UpdateJobRequest complete = to("COMPLETED");
        complete.setCompletionPhotoUrls("/uploads/photos/after-1.jpg");
        assertEquals(200, patchStatus(f.jobId, tech, complete).getResponse().getStatus());
        flushAndClear();

        // ── Step 1: try to walk it back ──────────────────────────────────────────────────
        MvcResult back = patchStatus(f.jobId, tech, to("IN_PROGRESS"));
        String body = back.getResponse().getContentAsString();

        // ── Steps 2-3 ────────────────────────────────────────────────────────────────────
        assertEquals(400, back.getResponse().getStatus(),
            "A COMPLETED job must not accept any further transition. Body: " + body);
        assertTrue(body.contains("Invalid job transition")
                || body.toLowerCase().contains("invalid status transition"),
            "The error must name the invalid transition. Body: " + body);
        assertTrue(body.contains("COMPLETED"),
            "The error should name the status it refused to leave. Body: " + body);

        // ── Step 4: status unchanged ─────────────────────────────────────────────────────
        flushAndClear();
        assertEquals(Job.JobStatus.COMPLETED, f.reload().getStatus(),
            "A refused transition must leave the job COMPLETED");

        // CANCELLED is equally terminal — the other half of the guard.
        assertEquals(400, patchStatus(f.jobId, tech, to("ACCEPTED")).getResponse().getStatus(),
            "COMPLETED -> ACCEPTED must be refused too");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOB-014 — work notes, internal notes hidden from the client
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * <p><b>Endpoint reality.</b> {@code POST /api/jobs/{id}/notes} does not exist. Job work notes
     * are a single free-text {@code jobs.work_notes} column set through the same
     * {@code PATCH /api/jobs/{id}/status} call — there is no per-note row, no {@code isInternal}
     * flag and therefore no client-visibility filtering on job notes at all. The internal/external
     * distinction the row requires exists only on <i>fault</i> notes
     * ({@code POST/GET /api/faults/{id}/notes}, {@code fault_notes.is_internal}).</p>
     *
     * <p>Both halves are driven below: the implemented work-notes path is asserted to work, and the
     * row's actual requirement (an internal job note that a CLIENT cannot see) is asserted against
     * the endpoint it specifies.</p>
     */
    @Test
    void addWorkNotes_internalHiddenFromClient() throws Exception {
        Fixture f = new Fixture();
        String tech = f.techBearer();

        assertEquals(200, patchStatus(f.jobId, tech, to("ACCEPTED")).getResponse().getStatus());
        assertEquals(200, patchStatus(f.jobId, tech, to("TRAVELLING")).getResponse().getStatus());

        // ── Implemented path: a work note is stored and read back verbatim ───────────────
        // workNotes rides on the status transition itself — there is no standalone note call,
        // and re-PATCHing IN_PROGRESS -> IN_PROGRESS is correctly refused as a no-op.
        UpdateJobRequest note = to("IN_PROGRESS");
        note.setWorkNotes("Cable replaced, tested OK");
        MvcResult noted = patchStatus(f.jobId, tech, note);
        assertEquals(200, noted.getResponse().getStatus(),
            "Body: " + noted.getResponse().getContentAsString());
        flushAndClear();
        assertEquals("Cable replaced, tested OK", f.reload().getWorkNotes(),
            "The work note must persist verbatim");

        // ── Steps 1-2: the row's own endpoint and status code ────────────────────────────
        MvcResult created = mvc.perform(post("/api/jobs/{id}/notes", f.jobId)
                .header("Authorization", tech)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("content", "Cable replaced, tested OK", "isInternal", false))))
            .andReturn();

        // ── Steps 4-5: an internal note must be invisible to the CLIENT ──────────────────
        MvcResult internal = mvc.perform(post("/api/jobs/{id}/notes", f.jobId)
                .header("Authorization", tech)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("content", "Customer was rude, escalate", "isInternal", true))))
            .andReturn();

        MvcResult asClient = mvc.perform(get("/api/jobs/{id}/notes", f.jobId)
                .header("Authorization", f.clientBearer()))
            .andReturn();

        assertAll("job notes with an internal/external distinction",
            () -> assertEquals(201, created.getResponse().getStatus(),
                "JOB-014 step 2 requires POST /api/jobs/{id}/notes -> 201. That endpoint does not "
                    + "exist (JobController has no /notes mapping); job notes are a single "
                    + "jobs.work_notes column with no per-note rows. Got "
                    + created.getResponse().getStatus() + ": "
                    + created.getResponse().getContentAsString()
                    + ". PRODUCTION CHANGE REQUIRED (a job_notes table + endpoints with an "
                    + "is_internal flag, mirroring the existing fault_notes feature)."),
            () -> assertEquals(201, internal.getResponse().getStatus(),
                "An internal job note must be creatable. Got "
                    + internal.getResponse().getStatus()),
            () -> {
                assertEquals(200, asClient.getResponse().getStatus(),
                    "A CLIENT must be able to read the non-internal notes on their own job. Got "
                        + asClient.getResponse().getStatus());
                String visible = asClient.getResponse().getContentAsString();
                assertFalse(visible.contains("Customer was rude"),
                    "An internal note must never be returned to a CLIENT. Body: " + visible);
                assertTrue(visible.contains("Cable replaced"),
                    "The non-internal note must still be visible to the CLIENT. Body: " + visible);
            }
        );
    }
}
