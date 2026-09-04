package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.ReassignJobRequest;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.dto.UpdateJobRequest;
import lk.slt.fieldops.entity.DaySession;
import lk.slt.fieldops.entity.DaySessionMember;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.DaySessionMemberRepository;
import lk.slt.fieldops.repository.DaySessionRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * JOB-028 (03_JOB_LIFECYCLE, FR-8) — {@code POST /api/jobs/{id}/reassign}: a REJECTED job sitting
 * in the Team Lead's "Needs Attention" queue is retargeted to another technician, its status resets
 * to PENDING, and it leaves the queue.
 *
 * <p><b>Distinct from JOB-027.</b> This is {@code JobService.reassignJob} (Team Lead moves an
 * existing <i>job</i> between technicians on their own team), not
 * {@code FaultAssignmentService.reassignFault} (Admin moves a <i>fault</i> to a different Work
 * Group) — a separate code path with no dedicated test class of its own currently (confirmed by
 * search; {@code FaultAssignmentTechnicianJobTest}, which once covered assignment-adjacent
 * behaviour, was deleted as part of Stage D's retirement of direct Admin-to-Technician assignment).
 * Nothing exercised {@code JobService.reassignJob} before this test.</p>
 *
 * <p><b>Tool substitution.</b> Tool column says REST Assured, which is not a dependency of this
 * module; MockMvc through the real filter chain is the established convention and drives the same
 * controller, {@code @PreAuthorize}, service and MySQL. {@code @Transactional} so every row rolls
 * back.</p>
 *
 * <p><b>Precondition detail.</b> The row requires "target technician active in today's DaySession".
 * {@code reassignJob} enforces something stricter than the row states — the technician must be
 * active in <i>this job's own team's</i> session ({@code findActiveMemberForTeamLeadToday}), not
 * merely in any team's session. The fixture therefore builds a real ACTIVE DaySession for the job's
 * Team Lead with both technicians as members, and the stricter guard is asserted separately
 * below.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobReassignIntegrationTest {

    @Autowired private MockMvc                    mvc;
    @Autowired private JwtTokenProvider           jwt;
    @Autowired private JobRepository              jobRepo;
    @Autowired private FaultRepository            faultRepo;
    @Autowired private UserRepository             userRepo;
    @Autowired private DaySessionRepository       sessionRepo;
    @Autowired private DaySessionMemberRepository memberRepo;
    @Autowired private ObjectMapper               json;
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

    /** An ACTIVE session for today owned by {@code lead}, with every given technician as a member. */
    private DaySession activeSessionFor(User lead, User... technicians) {
        // day_sessions has a UNIQUE (team_lead_id, session_date); each test makes a fresh lead,
        // so today's row is always free.
        DaySession session = new DaySession();
        session.setTeamLeadId(lead.getId());
        session.setSessionDate(LocalDate.now());
        session.setStatus(DaySession.SessionStatus.ACTIVE);
        session.setBodTime(LocalDateTime.now());
        DaySession saved = sessionRepo.save(session);

        for (User tech : technicians) {
            DaySessionMember member = new DaySessionMember();
            member.setSessionId(saved.getId());
            member.setTechnicianId(tech.getId());
            member.setIsActive(true);
            memberRepo.save(member);
        }
        sessionRepo.flush();
        memberRepo.flush();
        return saved;
    }

    private Long faultFor(User client, User lead) throws Exception {
        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reportRequest())))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus(),
            "Fault setup POST failed. Body: " + res.getResponse().getContentAsString());
        Long faultId = json.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setAssignedTeamLeadId(lead.getId());
        fault.setAssignedTeamLeadName(lead.getFullName());
        fault.setStatus(Fault.FaultStatus.IN_PROGRESS);
        faultRepo.save(fault);
        return faultId;
    }

    private Job jobOn(Long faultId, User client, User lead, User tech, DaySession session) {
        Job job = new Job();
        job.setJobNumber("JOB-REASSIGN-" + uniq());
        job.setFaultId(faultId);
        job.setSessionId(session.getId());
        job.setTeamLeadId(lead.getId());
        job.setTeamLeadName(lead.getFullName());
        job.setTechnicianId(tech.getId());
        job.setTechnicianName(tech.getFullName());
        job.setCustomerId(client.getId());
        job.setCustomerName(client.getFullName());
        job.setStatus(Job.JobStatus.PENDING);
        job.setPriority(Job.JobPriority.HIGH);
        Job saved = jobRepo.save(job);
        jobRepo.flush();
        return saved;
    }

    private MvcResult reassign(Long jobId, User caller, String role, Long newTechId)
            throws Exception {
        ReassignJobRequest body = new ReassignJobRequest();
        body.setNewTechnicianId(newTechId);
        return mvc.perform(post("/api/jobs/{id}/reassign", jobId)
                .header("Authorization", bearer(caller.getId(), role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
            .andReturn();
    }

    @Test
    void reassignUpdatesTechnicianAndStatus() throws Exception {
        User client  = newUser(User.Role.CLIENT,     "Client Chandima");
        User lead    = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        User oldTech = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User newTech = newUser(User.Role.TECHNICIAN, "Tech Saman");

        DaySession session = activeSessionFor(lead, oldTech, newTech);
        Long faultId = faultFor(client, lead);
        Job job = jobOn(faultId, client, lead, oldTech, session);
        Long jobId = job.getId();

        // ── Pre-condition: the technician rejected it, so it's in Needs Attention ────────
        UpdateJobRequest reject = new UpdateJobRequest();
        reject.setNewStatus("REJECTED");
        reject.setReason("Wrong skill set for this fault");
        MvcResult rejected = mvc.perform(patch("/api/jobs/{id}/status", jobId)
                .header("Authorization", bearer(oldTech.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reject)))
            .andReturn();
        assertEquals(200, rejected.getResponse().getStatus(),
            "Body: " + rejected.getResponse().getContentAsString());

        jobRepo.flush();
        em.clear();
        assertEquals(Job.JobStatus.REJECTED, jobRepo.findById(jobId).orElseThrow().getStatus(),
            "Pre-condition: the job starts REJECTED, in the Team Lead's Needs Attention queue");

        // ── Step 1: the Team Lead reassigns it to another technician ────────────────────
        MvcResult res = reassign(jobId, lead, "TEAM_LEAD", newTech.getId());
        String body = res.getResponse().getContentAsString();

        // ── Step 2 ───────────────────────────────────────────────────────────────────────
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);

        // ── Step 3: fresh query — retargeted and reset to PENDING ───────────────────────
        jobRepo.flush();
        em.clear();
        Job reassigned = jobRepo.findById(jobId).orElseThrow();
        assertEquals(newTech.getId(), reassigned.getTechnicianId(),
            "The job must now belong to the new technician");
        assertEquals(newTech.getFullName(), reassigned.getTechnicianName(),
            "The denormalised technician name must be refreshed too, not left stale");
        assertEquals(Job.JobStatus.PENDING, reassigned.getStatus(),
            "Reassigning must reset the job to PENDING so the new technician can accept it");
        assertEquals(lead.getId(), reassigned.getUpdatedBy(),
            "The reassigning Team Lead must be recorded as the updater");

        // ── Step 4: it has left the Needs Attention queue ───────────────────────────────
        assertNotEquals(Job.JobStatus.REJECTED, reassigned.getStatus(),
            "A reassigned job must no longer read as REJECTED");
        List<Job> needsAttention = jobRepo.findByTeamLeadIdAndScheduledDate(
                lead.getId(), LocalDate.now()).stream()
            .filter(j -> j.getStatus() == Job.JobStatus.REJECTED)
            .toList();
        assertTrue(needsAttention.stream().noneMatch(j -> j.getId().equals(jobId)),
            "The reassigned job must have left the Team Lead's Needs Attention (REJECTED) queue. "
                + "Still there: " + needsAttention.stream().map(Job::getJobNumber).toList());

        // The new technician now sees it in their own list.
        MvcResult myJobs = mvc.perform(get("/api/jobs/my")
                .header("Authorization", bearer(newTech.getId(), "TECHNICIAN")))
            .andReturn();
        assertEquals(200, myJobs.getResponse().getStatus());
        assertTrue(myJobs.getResponse().getContentAsString().contains(reassigned.getJobNumber()),
            "The reassigned job must appear in the new technician's job list");
    }

    /**
     * The guard the row's precondition implies: a technician who is NOT in this team's active
     * session today cannot be reassigned to.
     */
    @Test
    void reassignToTechnicianOutsideTodaysSession_isRefused() throws Exception {
        User client   = newUser(User.Role.CLIENT,     "Client Chandima");
        User lead     = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        User oldTech  = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User outsider = newUser(User.Role.TECHNICIAN, "Tech Outsider");

        DaySession session = activeSessionFor(lead, oldTech);   // outsider deliberately excluded
        Long faultId = faultFor(client, lead);
        Long jobId = jobOn(faultId, client, lead, oldTech, session).getId();
        em.clear();

        MvcResult res = reassign(jobId, lead, "TEAM_LEAD", outsider.getId());
        String body = res.getResponse().getContentAsString();

        assertEquals(400, res.getResponse().getStatus(),
            "Reassigning to a technician outside today's session must be refused. Body: " + body);
        assertTrue(body.contains("active session"),
            "The refusal must explain the technician is not in this team's session. Body: " + body);

        em.clear();
        assertEquals(oldTech.getId(), jobRepo.findById(jobId).orElseThrow().getTechnicianId(),
            "A refused reassignment must leave the original technician in place");
    }

    /**
     * SUPER_ADMIN — added when {@code JobModal} (frontend-admin, {@code JobsPage.js}) was fixed to
     * route its technician-reassignment save path through this endpoint instead of the retired
     * direct Admin-to-Technician fault-assign shape. {@code @PreAuthorize} here was
     * {@code hasAnyRole('TEAM_LEAD','ADMIN')} — the sole outlier against every comparable
     * {@code FaultController} endpoint (12/12 checked), all of which pair {@code ADMIN} with
     * {@code SUPER_ADMIN}. Without this fix, a Super Admin using the Jobs page would have gotten a
     * fresh 403 the moment JobModal started actually reaching this endpoint.
     */
    @Test
    void reassignBySuperAdmin_notForbidden() throws Exception {
        User client  = newUser(User.Role.CLIENT,     "Client Chandima");
        User lead    = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        User oldTech = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User newTech = newUser(User.Role.TECHNICIAN, "Tech Saman");
        User superAdmin = newUser(User.Role.SUPER_ADMIN, "Super Sarath");

        DaySession session = activeSessionFor(lead, oldTech, newTech);
        Long faultId = faultFor(client, lead);
        Long jobId = jobOn(faultId, client, lead, oldTech, session).getId();
        em.clear();

        MvcResult res = reassign(jobId, superAdmin, "SUPER_ADMIN", newTech.getId());
        String body = res.getResponse().getContentAsString();

        assertNotEquals(403, res.getResponse().getStatus(),
            "A SUPER_ADMIN must not be Forbidden from reassigning a job. Body: " + body);
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);

        jobRepo.flush();
        em.clear();
        Job fresh = jobRepo.findById(jobId).orElseThrow();
        assertEquals(newTech.getId(), fresh.getTechnicianId(),
            "The reassignment must have actually taken effect, not just been let through the guard");
    }

    /** A COMPLETED job is terminal and cannot be reassigned. */
    @Test
    void reassignCompletedJob_isRefused() throws Exception {
        User client  = newUser(User.Role.CLIENT,     "Client Chandima");
        User lead    = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        User oldTech = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User newTech = newUser(User.Role.TECHNICIAN, "Tech Saman");

        DaySession session = activeSessionFor(lead, oldTech, newTech);
        Long faultId = faultFor(client, lead);
        Job job = jobOn(faultId, client, lead, oldTech, session);
        job.setStatus(Job.JobStatus.COMPLETED);
        jobRepo.save(job);
        jobRepo.flush();
        em.clear();

        MvcResult res = reassign(job.getId(), lead, "TEAM_LEAD", newTech.getId());
        assertEquals(400, res.getResponse().getStatus(),
            "Body: " + res.getResponse().getContentAsString());
        assertTrue(res.getResponse().getContentAsString().contains("COMPLETED"),
            "The refusal must name the terminal status. Body: "
                + res.getResponse().getContentAsString());
    }
}
