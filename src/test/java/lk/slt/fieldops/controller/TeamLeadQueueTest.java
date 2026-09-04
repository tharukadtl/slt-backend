package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 07_ATTENDANCE — ATT-016 (FR-20) — the Team Lead "Needs Attention" queue must resolve into four
 * distinct categories, each carrying its own contextual data, rather than one flat list.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module. MockMvc through the real filter chain is the module's convention and drives the identical
 * path. {@code @Transactional} so the fixture rows roll back.</p>
 *
 * <p><b>Endpoint reading ("or equivalent", per the row's own step 1).</b> There is no
 * {@code GET /api/jobs?teamLead={id}&status=needsAttention} route and no server-side
 * "needsAttention" concept: the four-way grouping is performed client-side by
 * {@code SLTMobileApp/src/screens/teamlead/HomeScreen.tsx}'s {@code ATTENTION_SECTIONS}, over the
 * jobs returned by {@code GET /api/jobs/today} (the Team Lead view, {@code fetchTeamTasks}). So
 * the backend's actual contract for this feature is: that endpoint must expose enough per-job data
 * for the four categories to be told apart and for each card to show its own context. That is what
 * is asserted here, and the grouping predicates below are copied verbatim from
 * {@code ATTENTION_SECTIONS} so the test fails if the API stops satisfying the real client.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeamLeadQueueTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private JobRepository    jobRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private ObjectMapper     json;
    @Autowired private jakarta.persistence.EntityManager em;

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

    private Fault newFault(User client, User lead) {
        Fault f = new Fault();
        f.setFaultNumber("FLT-ATTN-" + uniq());
        f.setCustomerId(client.getId());
        f.setCustomerName(client.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("Needs-Attention queue fixture fault");
        f.setLocationAddress("No. 5 Main Street, Colombo 03");
        f.setLocationCity("Colombo");
        f.setLatitude(6.9271);
        f.setLongitude(79.8612);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setPriority(Fault.FaultPriority.HIGH);
        f.setStatus(Fault.FaultStatus.IN_PROGRESS);
        f.setAssignedTeamLeadId(lead.getId());
        f.setAssignedTeamLeadName(lead.getFullName());
        return faultRepo.save(f);
    }

    private Job newJob(User lead, User tech, User client, Job.JobStatus status) {
        Fault fault = newFault(client, lead);
        Job job = new Job();
        job.setJobNumber("JOB-ATTN-" + uniq());
        job.setFaultId(fault.getId());
        job.setFaultNumber(fault.getFaultNumber());
        job.setTeamLeadId(lead.getId());
        job.setTeamLeadName(lead.getFullName());
        job.setTechnicianId(tech.getId());
        job.setTechnicianName(tech.getFullName());
        job.setCustomerId(client.getId());
        job.setCustomerName(client.getFullName());
        job.setStatus(status);
        job.setPriority(Job.JobPriority.HIGH);
        job.setScheduledDate(LocalDate.now());
        return jobRepo.save(job);
    }

    // ── The exact predicates ATTENTION_SECTIONS uses, expressed against the JSON ──────────
    private static boolean isRejectedAs(JsonNode j, String category) {
        return "REJECTED".equals(j.path("status").asText())
            && category.equals(j.path("rejectionCategory").asText());
    }

    private static boolean isEodHandover(JsonNode j) {
        return !j.path("eodHandoverReason").isNull()
            && !j.path("eodHandoverReason").isMissingNode();
    }

    private static JsonNode byJobNumber(List<JsonNode> jobs, String jobNumber) {
        return jobs.stream()
            .filter(j -> jobNumber.equals(j.path("jobNumber").asText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Job " + jobNumber + " was not returned by GET /api/jobs/today at all"));
    }

    @Test
    void fourWayCategoryGrouping() throws Exception {
        // ── Arrange: one Team Lead with exactly one job in each of the four states ───────
        User lead   = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        User tech   = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User client = newUser(User.Role.CLIENT,     "Client Chandima");

        Job mismatch = newJob(lead, tech, client, Job.JobStatus.REJECTED);
        mismatch.setRejectionCategory(Job.RejectionCategory.ISSUE_MISMATCH);
        mismatch.setObservedIssueType(Fault.FaultCategory.PHONE);
        mismatch.setRejectionReason("Reported as broadband, the actual fault is the phone line");
        mismatch.setRejectedByRole("TECHNICIAN");
        jobRepo.save(mismatch);

        Job materialDelay = newJob(lead, tech, client, Job.JobStatus.REJECTED);
        materialDelay.setRejectionCategory(Job.RejectionCategory.MATERIAL_DELAY);
        materialDelay.setLinkedMaterialRequestId(4242L);
        materialDelay.setLinkedMaterialRequestNumber("MR-2026-0042");
        materialDelay.setRejectionReason("Waiting on drop wire");
        materialDelay.setRejectedByRole("TECHNICIAN");
        jobRepo.save(materialDelay);

        Job other = newJob(lead, tech, client, Job.JobStatus.REJECTED);
        other.setRejectionCategory(Job.RejectionCategory.OTHER);
        other.setRejectionReason("Customer not at premises");
        other.setRejectedByRole("TECHNICIAN");
        jobRepo.save(other);

        LocalDateTime handoverAt = LocalDateTime.now().minusMinutes(30);
        Job handover = newJob(lead, tech, client, Job.JobStatus.PENDING);
        handover.setEodHandoverReason("Splice not finished, resuming first thing tomorrow");
        handover.setEodHandoverAt(handoverAt);
        jobRepo.save(handover);

        jobRepo.flush();
        faultRepo.flush();
        em.flush();
        em.clear();

        // ── Act: step 1 — the Team Lead's own queue ──────────────────────────────────────
        MvcResult res = mvc.perform(get("/api/jobs/today")
                .header("Authorization", bearer(lead.getId(), "TEAM_LEAD")))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "The Team Lead's job queue must be readable. Body: "
                + res.getResponse().getContentAsString());

        List<JsonNode> jobs = new ArrayList<>();
        json.readTree(res.getResponse().getContentAsString()).forEach(jobs::add);

        JsonNode mismatchJson = byJobNumber(jobs, mismatch.getJobNumber());
        JsonNode delayJson    = byJobNumber(jobs, materialDelay.getJobNumber());
        JsonNode otherJson    = byJobNumber(jobs, other.getJobNumber());
        JsonNode handoverJson = byJobNumber(jobs, handover.getJobNumber());

        assertAll("the queue resolves into four distinct, individually contextualised categories",

            // ── Step 2: each of the four jobs falls into exactly ONE category ────────────
            () -> {
                assertTrue(isRejectedAs(mismatchJson, "ISSUE_MISMATCH"),
                    "the issue-mismatch job must be identifiable as such");
                assertTrue(isRejectedAs(delayJson, "MATERIAL_DELAY"),
                    "the material-delay job must be identifiable as such");
                assertTrue(isRejectedAs(otherJson, "OTHER"),
                    "the generic rejection must be identifiable as OTHER, not lumped in with "
                        + "the two specific categories");
                assertTrue(isEodHandover(handoverJson),
                    "the EOD-handover job must be identifiable by its eodHandoverReason — it is "
                        + "NOT a rejection and carries no rejectionCategory, so a queue keyed on "
                        + "rejection alone would lose it entirely");
            },

            // ── Step 2 (cont.): the categories must be mutually exclusive ────────────────
            () -> {
                assertFalse(isEodHandover(mismatchJson),
                    "a rejected job must not also read as an EOD handover");
                assertFalse(isRejectedAs(handoverJson, "OTHER"),
                    "an EOD handover must not be miscounted as a generic rejection");
                long distinctCategories = jobs.stream()
                    .filter(j -> isRejectedAs(j, "ISSUE_MISMATCH")
                        || isRejectedAs(j, "MATERIAL_DELAY")
                        || isRejectedAs(j, "OTHER")
                        || isEodHandover(j))
                    .count();
                assertTrue(distinctCategories >= 4,
                    "all four fixture jobs must land in the attention queue. Landed: "
                        + distinctCategories);
            },

            // ── Step 3: ISSUE_MISMATCH carries observedIssueType ────────────────────────
            () -> assertEquals("PHONE", mismatchJson.path("observedIssueType").asText(),
                "an issue-mismatch card must show what the technician actually found, so the "
                    + "Team Lead can re-triage without phoning them"),

            // ── Step 3: MATERIAL_DELAY carries the linked material request ───────────────
            () -> {
                assertEquals(4242L, delayJson.path("linkedMaterialRequestId").asLong(),
                    "a material-delay card must link to the request that is blocking it");
                assertEquals("MR-2026-0042",
                    delayJson.path("linkedMaterialRequestNumber").asText(),
                    "and carry its human-readable number, which is what the card falls back to "
                        + "when the request detail lookup fails");
            },

            // ── Step 3: OTHER carries at least the free-text reason ─────────────────────
            () -> assertEquals("Customer not at premises",
                otherJson.path("rejectionReason").asText(),
                "a generic rejection has no structured context, so its free-text reason is the "
                    + "only thing the card can show and must be present"),

            // ── Step 3: EOD handover carries its reason AND when it happened ─────────────
            () -> {
                assertEquals("Splice not finished, resuming first thing tomorrow",
                    handoverJson.path("eodHandoverReason").asText(),
                    "an EOD-handover card must show the technician's own per-job reason "
                        + "(SRS 5.3.1.4)");
                assertFalse(handoverJson.path("eodHandoverAt").isMissingNode()
                        || handoverJson.path("eodHandoverAt").isNull(),
                    "and when it was handed over, which the card renders beneath the reason");
            },

            // ── Every card in the queue needs the free-text reason and the technician ────
            () -> {
                assertEquals("Reported as broadband, the actual fault is the phone line",
                    mismatchJson.path("rejectionReason").asText());
                assertEquals(tech.getFullName(), handoverJson.path("technicianName").asText(),
                    "the card must name who handed the job back");
            }
        );
    }
}
