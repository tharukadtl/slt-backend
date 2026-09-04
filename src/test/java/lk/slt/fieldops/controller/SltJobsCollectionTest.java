package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.ReportFaultRequest;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * JOB-017 (03_JOB_LIFECYCLE, FR-8) — the job status lifecycle driven as an API collection.
 *
 * <p><b>Tool substitution.</b> The row maps to a Newman/Postman artifact,
 * {@code SLT_Jobs_Collection.json → Full_Lifecycle_ASSIGNED_To_COMPLETED}. That collection is
 * checked in at {@code fieldops/src/test/postman/SLT_Jobs_Collection.json} so CI can run it once
 * {@code newman} and a live server are available; neither is available in this environment (newman
 * is not installed and nothing listens on :8080), so it cannot produce a verdict on its own. This
 * class is the executable twin — the same four requests, the same {@code pm.test} assertions —
 * driven through the REAL filter chain with MockMvc against the real database. Same precedent as
 * AUTH-009's {@code SltAuthCollectionTest} and FAULT-016's {@link SltFaultsCollectionTest}.</p>
 *
 * <p>It additionally asserts the checked-in collection file exists and declares the mapped folder
 * name, so the artifact and its twin cannot silently drift apart.</p>
 *
 * <p><b>Status vocabulary.</b> The row's script goes ACCEPTED &rarr; IN_PROGRESS &rarr; COMPLETED
 * from "ASSIGNED". {@code Job.JobStatus} has no ASSIGNED constant (a dispatched job is PENDING) and
 * {@code JobService.validateJobTransition} requires an intervening TRAVELLING step, so the real
 * machine is what is driven here and in the collection. The row's final assertion —
 * {@code pm.expect(resp.json().status).to.eql('COMPLETED')} — is unchanged.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SltJobsCollectionTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JobRepository    jobRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private UserRepository   userRepo;
    @Autowired private ObjectMapper     json;

    private static final Long BRANCH_ID = 1L;
    private static final Path COLLECTION =
        Path.of("src", "test", "postman", "SLT_Jobs_Collection.json");

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    /** Mirrors the collection's {{baseUrl}}/{{techToken}}/{{jobId}} environment variables. */
    private final Map<String, String> environment = new HashMap<>();

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

    /** One collection request: PATCH /api/jobs/{{jobId}}/status with a raw body. */
    private MvcResult patchStatus(String rawBody) throws Exception {
        return mvc.perform(patch("/api/jobs/{id}/status", environment.get("jobId"))
                .header("Authorization", environment.get("techToken"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody))
            .andReturn();
    }

    @Test
    void fullLifecycle_assignedToCompleted_collectionTwin() throws Exception {
        // ── The checked-in artifact the row maps to must exist and name this folder ──────
        assertTrue(Files.exists(COLLECTION),
            "The Newman collection this row maps to must be checked in at " + COLLECTION.toAbsolutePath());
        String collectionJson = Files.readString(COLLECTION);
        assertTrue(collectionJson.contains("Full_Lifecycle_ASSIGNED_To_COMPLETED"),
            "The collection must contain the folder named in the Automation Mapping");
        assertDoesNotThrow(() -> json.readTree(collectionJson),
            "The checked-in collection must be valid JSON so newman can actually run it");

        // ── Environment setup: a technician and a PENDING job assigned to them ───────────
        User client = newUser(User.Role.CLIENT,     "Client Chandima");
        User lead   = newUser(User.Role.TEAM_LEAD,  "Lead Kamal");
        User tech   = newUser(User.Role.TECHNICIAN, "Tech Nimal");

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
        fault.setStatus(Fault.FaultStatus.IN_PROGRESS);
        faultRepo.save(fault);

        Job job = new Job();
        job.setJobNumber("JOB-NEWMAN-" + uniq());
        job.setFaultId(faultId);
        job.setFaultNumber(fault.getFaultNumber());
        job.setTeamLeadId(lead.getId());
        job.setTechnicianId(tech.getId());
        job.setTechnicianName(tech.getFullName());
        job.setCustomerId(client.getId());
        job.setStatus(Job.JobStatus.PENDING);
        job.setPriority(Job.JobPriority.HIGH);
        Job saved = jobRepo.save(job);
        jobRepo.flush();

        environment.put("techToken", bearer(tech.getId(), "TECHNICIAN"));
        environment.put("jobId", String.valueOf(saved.getId()));

        // ── Request 1: PATCH {status:'ACCEPTED'} → pm.test('200 ACCEPTED') ───────────────
        MvcResult accepted = patchStatus("{\"status\":\"ACCEPTED\"}");
        assertEquals(200, accepted.getResponse().getStatus(),
            "Body: " + accepted.getResponse().getContentAsString());
        JsonNode acceptedBody = json.readTree(accepted.getResponse().getContentAsString());
        assertEquals("ACCEPTED", acceptedBody.get("status").asText());
        assertFalse(acceptedBody.get("acceptedAt").isNull(), "acceptedAt must be stamped");
        environment.put("jobStatus", acceptedBody.get("status").asText());

        // ── Request 2: PATCH {status:'TRAVELLING'} ───────────────────────────────────────
        MvcResult travelling = patchStatus("{\"status\":\"TRAVELLING\"}");
        assertEquals(200, travelling.getResponse().getStatus(),
            "Body: " + travelling.getResponse().getContentAsString());
        environment.put("jobStatus",
            json.readTree(travelling.getResponse().getContentAsString()).get("status").asText());
        assertEquals("TRAVELLING", environment.get("jobStatus"));

        // ── Request 3: PATCH {status:'IN_PROGRESS'} → pm.test('200 IN_PROGRESS') ─────────
        MvcResult started = patchStatus("{\"status\":\"IN_PROGRESS\"}");
        assertEquals(200, started.getResponse().getStatus(),
            "Body: " + started.getResponse().getContentAsString());
        JsonNode startedBody = json.readTree(started.getResponse().getContentAsString());
        assertEquals("IN_PROGRESS", startedBody.get("status").asText());
        assertFalse(startedBody.get("startedAt").isNull(), "startedAt must be stamped");
        environment.put("jobStatus", startedBody.get("status").asText());

        // ── Request 4: PATCH {status:'COMPLETED'} → pm.test('Status updated') ────────────
        MvcResult completed = patchStatus(
            "{\"status\":\"COMPLETED\",\"causeOfFault\":\"Damaged drop wire\","
                + "\"completionRemarks\":\"Replaced 15m cable, tested OK\","
                + "\"completionPhotoUrls\":\"/uploads/photos/after-1.jpg\"}");
        assertEquals(200, completed.getResponse().getStatus(),
            "Body: " + completed.getResponse().getContentAsString());
        JsonNode completedBody = json.readTree(completed.getResponse().getContentAsString());
        assertFalse(completedBody.get("completedAt").isNull(), "completedAt must be stamped");

        // ── The row's own final assertion, verbatim ──────────────────────────────────────
        // pm.environment.set('jobStatus', resp.json().status)
        environment.put("jobStatus", completedBody.get("status").asText());
        assertEquals("COMPLETED", environment.get("jobStatus"),
            "pm.test('Status updated') — the collection's final assertion");

        // ── Request 5: the back-transition the collection also guards ────────────────────
        MvcResult back = patchStatus("{\"status\":\"IN_PROGRESS\"}");
        assertEquals(400, back.getResponse().getStatus(),
            "A COMPLETED job must refuse further transitions. Body: "
                + back.getResponse().getContentAsString());
        assertTrue(back.getResponse().getContentAsString().contains("Invalid job transition"),
            "Body: " + back.getResponse().getContentAsString());
    }
}
