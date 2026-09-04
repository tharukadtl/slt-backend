package lk.slt.fieldops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.dto.UpdateJobRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
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

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * JOB-026 (03_JOB_LIFECYCLE, FR-23) — when a dispatched job is rejected, the linked fault must come
 * back off {@code IN_PROGRESS} to {@code ASSIGNED}, so the Admin dashboard's in-progress count
 * stops over-reporting and the client stops seeing a false "in progress".
 *
 * <p>This is regression coverage for QA Critical Issue #23. The fix lives in
 * {@code JobService.updateJobStatus} (the {@code REJECTED} branch) and in
 * {@code AttendanceService.checkOut} (the EOD-handover path), both guarded so a terminal
 * ({@code COMPLETED}/{@code CANCELLED}) fault is never resurrected. Nothing exercised the reset
 * itself before this test.</p>
 *
 * <p><b>Tool substitution.</b> Tool column says "JUnit + REST Assured"; REST Assured is not a
 * dependency of this module, so the HTTP-facing halves (steps 3 and 4 — the dashboard count and
 * the client-facing issue status) are driven with MockMvc through the real filter chain, the
 * module's established convention. {@code @Transactional} so every row rolls back.</p>
 *
 * <p><b>Placement.</b> Mapped to {@code FaultStatusResetTest}, which did not exist; it lives in the
 * {@code service} package because the behaviour under test belongs to {@link JobService}, even
 * though the assertions reach through the controllers.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FaultStatusResetTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JobRepository    jobRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private UserRepository   userRepo;
    @Autowired private ObjectMapper     json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, BRANCH_ID);
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
        u.setOpmcId(BRANCH_ID);
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
        req.setOpmcId(BRANCH_ID);
        req.setPriority("HIGH");
        return req;
    }

    /**
     * The Admin dashboard's in-progress bucket. The bare {@code GET /api/dashboard} the row names
     * does not exist; the KPI counters live on {@code GET /api/dashboard/kpi-summary}
     * ({@code DashboardDTO.KpiSummaryDTO.inProgressFaults}).
     */
    private long inProgressFaultCount(User admin) throws Exception {
        MvcResult res = mvc.perform(get("/api/dashboard/kpi-summary")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
            "Dashboard KPI summary must be readable by an ADMIN. Body: "
                + res.getResponse().getContentAsString());
        JsonNode dash = json.readTree(res.getResponse().getContentAsString());
        assertTrue(dash.has("inProgressFaults"),
            "KpiSummaryDTO must expose inProgressFaults. Body: " + dash);
        return dash.get("inProgressFaults").asLong();
    }

    @Test
    void rejectionResetsToAssigned() throws Exception {
        User client = newUser(User.Role.CLIENT,     "Client Chandima");
        User lead   = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        User tech   = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User admin  = newUser(User.Role.ADMIN,      "Admin Amelia");

        // ── Pre-condition: fault IN_PROGRESS, job dispatched to the technician ───────────
        MvcResult reported = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reportRequest())))
            .andReturn();
        assertEquals(201, reported.getResponse().getStatus(),
            "Fault setup POST failed. Body: " + reported.getResponse().getContentAsString());
        Long faultId = json.readTree(reported.getResponse().getContentAsString()).get("id").asLong();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setAssignedTeamLeadId(lead.getId());
        fault.setAssignedTeamLeadName(lead.getFullName());
        fault.setStatus(Fault.FaultStatus.IN_PROGRESS);
        faultRepo.save(fault);

        Job job = new Job();
        job.setJobNumber("JOB-RESET-" + uniq());
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
        Long jobId = jobRepo.save(job).getId();
        jobRepo.flush();
        faultRepo.flush();
        em.clear();

        assertEquals(Fault.FaultStatus.IN_PROGRESS,
            faultRepo.findById(faultId).orElseThrow().getStatus(),
            "Pre-condition: the fault starts IN_PROGRESS");

        // Baseline for step 3 — the dashboard counts every fault in the shared dev database, so
        // the assertion below is on the DELTA this rejection causes, not an absolute number.
        long inProgressBefore = inProgressFaultCount(admin);

        // ── Step 1: the technician rejects the job as an issue mismatch ─────────────────
        UpdateJobRequest reject = new UpdateJobRequest();
        reject.setNewStatus("REJECTED");
        reject.setReason("Actually a fiber fault, not broadband");
        reject.setRejectionCategory(Job.RejectionCategory.ISSUE_MISMATCH);
        reject.setObservedIssueType(Fault.FaultCategory.FIBER);

        MvcResult rejected = mvc.perform(patch("/api/jobs/{id}/status", jobId)
                .header("Authorization", bearer(tech.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reject)))
            .andReturn();
        assertEquals(200, rejected.getResponse().getStatus(),
            "Body: " + rejected.getResponse().getContentAsString());

        jobRepo.flush();
        faultRepo.flush();
        em.clear();

        // ── Step 2: fresh query — the fault must no longer be IN_PROGRESS ───────────────
        Fault afterReject = faultRepo.findById(faultId).orElseThrow();
        assertEquals(Fault.FaultStatus.ASSIGNED, afterReject.getStatus(),
            "A rejected job means nobody is working the fault — it must go back to ASSIGNED, not "
                + "stay IN_PROGRESS (QA Critical #23)");
        assertEquals(lead.getId(), afterReject.getAssignedTeamLeadId(),
            "The Team Lead assignment is kept — the fault lands in THEIR Needs Attention queue, "
                + "unlike EOD's FORWARD_TO_ADMIN which clears it");

        Job rejectedJob = jobRepo.findById(jobId).orElseThrow();
        assertEquals(Job.JobStatus.REJECTED, rejectedJob.getStatus());
        assertEquals(Job.RejectionCategory.ISSUE_MISMATCH, rejectedJob.getRejectionCategory());
        assertEquals(Fault.FaultCategory.FIBER, rejectedJob.getObservedIssueType());

        // ── Step 3: the dashboard's in-progress count must exclude it ───────────────────
        long inProgressAfter = inProgressFaultCount(admin);
        assertEquals(inProgressBefore - 1, inProgressAfter,
            "The dashboard's inProgressFaults count must drop by exactly one when a dispatched "
                + "job is rejected — this fault is no longer being worked. Before: "
                + inProgressBefore + ", after: " + inProgressAfter);

        MvcResult allFaults = mvc.perform(get("/api/faults")
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .param("status", "IN_PROGRESS"))
            .andReturn();
        String inProgressBody = allFaults.getResponse().getContentAsString();
        assertFalse(inProgressBody.contains("\"id\":" + faultId + ","),
            "The rejected fault must not appear in the IN_PROGRESS bucket any more. "
                + "Body: " + inProgressBody);

        // ── Step 4: the client must see "assigned", not a false "in progress" ───────────
        MvcResult clientView = mvc.perform(get("/api/issues")
                .header("Authorization", bearer(client.getId(), "CLIENT")))
            .andReturn();
        assertEquals(200, clientView.getResponse().getStatus(),
            "Body: " + clientView.getResponse().getContentAsString());
        String clientBody = clientView.getResponse().getContentAsString();

        JsonNode mine = json.readTree(clientBody);
        JsonNode thisIssue = null;
        for (JsonNode node : mine.isArray() ? mine : mine.path("content")) {
            if (node.path("id").asLong() == faultId) { thisIssue = node; break; }
        }
        assertNotNull(thisIssue, "The client must still see their own fault. Body: " + clientBody);
        String shown = thisIssue.has("status") ? thisIssue.get("status").asText() : "";
        assertFalse(shown.equalsIgnoreCase("IN_PROGRESS") || shown.equalsIgnoreCase("in progress"),
            "The client must not be shown 'in progress' for a fault nobody is working. Shown: "
                + shown);
    }

    /**
     * The terminal-fault guard: a COMPLETED fault must never be resurrected to ASSIGNED by a late
     * rejection on some other job. Same guard {@code routeOpenJobsAtEod}'s FORWARD_TO_ADMIN uses.
     */
    @Test
    void rejectionDoesNotResurrectCompletedFault() throws Exception {
        User client = newUser(User.Role.CLIENT,     "Client Chandima");
        User lead   = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        User tech   = newUser(User.Role.TECHNICIAN, "Tech Nimal");

        MvcResult reported = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reportRequest())))
            .andReturn();
        Long faultId = json.readTree(reported.getResponse().getContentAsString()).get("id").asLong();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setAssignedTeamLeadId(lead.getId());
        fault.setStatus(Fault.FaultStatus.COMPLETED);
        faultRepo.save(fault);

        Job job = new Job();
        job.setJobNumber("JOB-RESET-" + uniq());
        job.setFaultId(faultId);
        job.setTeamLeadId(lead.getId());
        job.setTechnicianId(tech.getId());
        job.setCustomerId(client.getId());
        job.setStatus(Job.JobStatus.PENDING);
        Long jobId = jobRepo.save(job).getId();
        jobRepo.flush();
        faultRepo.flush();
        em.clear();

        UpdateJobRequest reject = new UpdateJobRequest();
        reject.setNewStatus("REJECTED");
        reject.setReason("Stale dispatch");
        mvc.perform(patch("/api/jobs/{id}/status", jobId)
                .header("Authorization", bearer(tech.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reject)))
            .andReturn();

        jobRepo.flush();
        faultRepo.flush();
        em.clear();

        assertEquals(Fault.FaultStatus.COMPLETED,
            faultRepo.findById(faultId).orElseThrow().getStatus(),
            "A COMPLETED fault must stay COMPLETED — the reset must not resurrect terminal faults");
    }
}
