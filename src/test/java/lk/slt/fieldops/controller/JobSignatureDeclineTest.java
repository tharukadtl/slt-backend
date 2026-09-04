package lk.slt.fieldops.controller;

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

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * QA_Compliance_Consolidated_Report.md #12 — SRS 5.3.1.3 (FR-9): "If the client is unavailable or
 * declines to sign, the Technician records a reason; the job can still be completed but is flagged
 * for Team Lead review before payment submission."
 *
 * <p><b>Confirmed by the earlier investigation, re-proven live here:</b> {@code
 * JobService.updateJobStatus}'s {@code COMPLETED} handler has no signature requirement at the
 * entity level — the mandatory-signature constraint that used to make this impossible lived only
 * in the mobile UI (now replaced there with a real decline path). The decline reason is routed
 * through this same {@code PATCH /api/jobs/{id}/status} completion request — {@code
 * POST /api/jobs/{id}/signature} is simply never called on this path — landing on three new
 * {@code Job} fields mirroring the existing {@code holdReason}/{@code holdAt} pairing convention:
 * {@code signatureDeclineReason}, {@code needsTeamLeadReview} (boolean), {@code signatureFlaggedAt}.
 * No separate audit record — the flag plus its own timestamp is sufficient, per the confirmed
 * decision.</p>
 *
 * <p>{@code @AutoConfigureMockMvc}, real JWT filter chain, real MySQL — matching {@code
 * JobIntegrationTest}'s established convention for this exact endpoint.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobSignatureDeclineTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JobRepository    jobRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private UserRepository   userRepo;
    @Autowired private ObjectMapper     json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "jsd" + userId, role, REAL_BRANCH_ID);
    }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("jsd" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_BRANCH_ID);
        return userRepo.save(u);
    }

    private Long reportFaultAs(User client) throws Exception {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");
        req.setDescription("No internet since 08:00 - JSD fixture");
        req.setLocationAddress("No. 5 Main Street, Colombo 03");
        req.setLocationCity("Colombo");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(REAL_BRANCH_ID);
        req.setPriority("HIGH");
        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
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

    /** Client + Team Lead + Technician + a fault, driven to an IN_PROGRESS job ready to complete. */
    private final class Fixture {
        final User client = newUser(User.Role.CLIENT,     "JSD Client");
        final User lead   = newUser(User.Role.TEAM_LEAD,  "JSD Lead");
        final User tech   = newUser(User.Role.TECHNICIAN, "JSD Tech");
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
            job.setJobNumber("JOB-JSD-" + uniq());
            job.setFaultId(faultId);
            job.setFaultNumber(fault.getFaultNumber());
            job.setTeamLeadId(lead.getId());
            job.setTeamLeadName(lead.getFullName());
            job.setTechnicianId(tech.getId());
            job.setTechnicianName(tech.getFullName());
            job.setCustomerId(client.getId());
            job.setCustomerName(client.getFullName());
            job.setStatus(Job.JobStatus.IN_PROGRESS);
            job.setPriority(Job.JobPriority.HIGH);
            jobId = jobRepo.save(job).getId();
            flushAndClear();
        }

        String techBearer() { return bearer(tech.getId(), "TECHNICIAN"); }
        Job reload()        { return jobRepo.findById(jobId).orElseThrow(); }
    }

    private MvcResult patchStatus(Long jobId, String bearerToken, UpdateJobRequest req) throws Exception {
        return mvc.perform(patch("/api/jobs/{id}/status", jobId)
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // The exact scenario: client declines, reason routed through the completion request
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void completingWithDeclineReason_reachesCompleted_flagsForTeamLeadReview() throws Exception {
        Fixture f = new Fixture();

        UpdateJobRequest complete = new UpdateJobRequest();
        complete.setNewStatus("COMPLETED");
        complete.setCompletionPhotoUrls("/uploads/photos/after-1.jpg");
        complete.setCompletionRemarks("Line repaired");
        complete.setSignatureDeclineReason("Client left the property before work finished");

        MvcResult res = patchStatus(f.jobId, f.techBearer(), complete);
        assertEquals(200, res.getResponse().getStatus(),
            "Body: " + res.getResponse().getContentAsString());

        flushAndClear();
        Job job = f.reload();

        assertAll("no signature was ever captured, yet the job reaches COMPLETED and is flagged",
            () -> assertEquals(Job.JobStatus.COMPLETED, job.getStatus(),
                "Confirmed by investigation: JobService.updateJobStatus's COMPLETED handler has "
                    + "no signature requirement at the entity level — completion must not be "
                    + "blocked by a missing signature"),
            () -> assertNull(job.getCompletionSignature(),
                "No /signature call was ever made on this path — completionSignature must stay null"),
            () -> assertEquals("Client left the property before work finished",
                job.getSignatureDeclineReason()),
            () -> assertEquals(Boolean.TRUE, job.getNeedsTeamLeadReview()),
            () -> assertNotNull(job.getSignatureFlaggedAt(),
                "signatureFlaggedAt must be stamped — mirrors the holdReason/holdAt pairing convention"),
            () -> assertTrue(
                ChronoUnit.SECONDS.between(job.getSignatureFlaggedAt(), LocalDateTime.now()) < 30,
                "signatureFlaggedAt must be stamped to roughly now, not some other time")
        );
    }

    @Test
    void declineReasonAndFlag_areExposedOnGet_forTeamLeadAndAdminScreensToRead() throws Exception {
        Fixture f = new Fixture();

        UpdateJobRequest complete = new UpdateJobRequest();
        complete.setNewStatus("COMPLETED");
        complete.setCompletionPhotoUrls("/uploads/photos/after-1.jpg");
        complete.setSignatureDeclineReason("Client unavailable - no answer at the door");
        assertEquals(200, patchStatus(f.jobId, f.techBearer(), complete).getResponse().getStatus());
        flushAndClear();

        // The exact read path both PaymentSubmissionScreen (mobile Team Lead) and PaymentsPage's
        // ReviewPanel (Admin) use — GET /api/jobs/{id}, no new endpoint.
        MvcResult read = mvc.perform(get("/api/jobs/{id}", f.jobId)
                .header("Authorization", f.techBearer()))
            .andReturn();
        assertEquals(200, read.getResponse().getStatus());
        var body = json.readTree(read.getResponse().getContentAsString());

        assertEquals(true, body.get("needsTeamLeadReview").asBoolean());
        assertEquals("Client unavailable - no answer at the door",
            body.get("signatureDeclineReason").asText());
        assertFalse(body.get("signatureFlaggedAt").isNull());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // The normal (signed) path must be completely unaffected
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void completingWithARealSignature_doesNotFlag() throws Exception {
        Fixture f = new Fixture();

        // Real signature captured first, exactly as the mobile SignatureScreen does.
        MvcResult sigRes = mvc.perform(post("/api/jobs/{id}/signature", f.jobId)
                .header("Authorization", f.techBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signature\":\"data:image/png;base64,REALSIGDATA\"}"))
            .andReturn();
        assertEquals(200, sigRes.getResponse().getStatus());

        UpdateJobRequest complete = new UpdateJobRequest();
        complete.setNewStatus("COMPLETED");
        complete.setCompletionPhotoUrls("/uploads/photos/after-1.jpg");
        assertEquals(200, patchStatus(f.jobId, f.techBearer(), complete).getResponse().getStatus());

        flushAndClear();
        Job job = f.reload();

        assertAll("a normal signed completion must not be touched by this fix",
            () -> assertEquals(Job.JobStatus.COMPLETED, job.getStatus()),
            () -> assertEquals("data:image/png;base64,REALSIGDATA", job.getCompletionSignature()),
            () -> assertNull(job.getSignatureDeclineReason()),
            () -> assertEquals(Boolean.FALSE, job.getNeedsTeamLeadReview(),
                "needsTeamLeadReview must default to false, not null, for a normal completion"),
            () -> assertNull(job.getSignatureFlaggedAt())
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Neither signature nor decline reason — the entity-level permissiveness this fix relies on
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void completingWithNeitherSignatureNorDeclineReason_stillReachesCompleted_notFlagged() throws Exception {
        Fixture f = new Fixture();

        UpdateJobRequest complete = new UpdateJobRequest();
        complete.setNewStatus("COMPLETED");
        complete.setCompletionPhotoUrls("/uploads/photos/after-1.jpg");
        // Neither /signature nor signatureDeclineReason — confirms the investigation's finding
        // directly: no entity-level guard existed before this fix, and this fix adds none either.
        MvcResult res = patchStatus(f.jobId, f.techBearer(), complete);
        assertEquals(200, res.getResponse().getStatus(),
            "Body: " + res.getResponse().getContentAsString());

        flushAndClear();
        Job job = f.reload();
        assertEquals(Job.JobStatus.COMPLETED, job.getStatus());
        assertEquals(Boolean.FALSE, job.getNeedsTeamLeadReview(),
            "No decline reason was ever sent — the job must not be flagged just because it "
                + "lacks a signature");
        assertNull(job.getSignatureFlaggedAt());
    }

    @Test
    void blankSignatureDeclineReason_isTreatedAsAbsent_doesNotFlag() throws Exception {
        Fixture f = new Fixture();

        UpdateJobRequest complete = new UpdateJobRequest();
        complete.setNewStatus("COMPLETED");
        complete.setCompletionPhotoUrls("/uploads/photos/after-1.jpg");
        complete.setSignatureDeclineReason("   ");
        assertEquals(200, patchStatus(f.jobId, f.techBearer(), complete).getResponse().getStatus());

        flushAndClear();
        Job job = f.reload();
        assertEquals(Boolean.FALSE, job.getNeedsTeamLeadReview(),
            "A blank/whitespace-only reason must not set the review flag");
        assertNull(job.getSignatureFlaggedAt());
    }
}
