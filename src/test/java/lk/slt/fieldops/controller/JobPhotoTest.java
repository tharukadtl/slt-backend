package lk.slt.fieldops.controller;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * JOB-006 and JOB-015 (03_JOB_LIFECYCLE, FR-9) — before/after job photos must be stored as one row
 * per photo, each with its own URL and a {@code photo_type} of BEFORE or AFTER (1NF; no
 * comma-separated URL blob).
 *
 * <p><b>Tool substitution.</b> Tool column says REST Assured, which is not a dependency here;
 * MockMvc through the real filter chain is the module convention ({@link FaultPhotoTest} does the
 * same for the fault-photo rows) and exercises the identical controller/service/exception path.</p>
 *
 * <p><b>Endpoint and storage reality.</b> {@code POST /api/jobs/{id}/photos} does not exist, and
 * neither does a {@code job_photos} table or a {@code JobPhotoRepository}. What exists is:
 * <ul>
 *   <li>{@code POST /api/uploads/photos} ({@link FileUploadController}) — a generic multipart
 *       upload (part name {@code files}) that stores the bytes and returns URLs. It takes no
 *       {@code photo_type} and knows nothing about jobs.</li>
 *   <li>{@code jobs.completion_photo_urls} — a single {@code TEXT} column holding the returned URLs
 *       <b>comma-separated</b>, set on the COMPLETED transition. The {@link Job} entity says so in
 *       as many words: "Comma-separated after-service photo URLs, required to complete a job".</li>
 * </ul>
 * There is consequently no place to record that a photo is a BEFORE photo at all — the mobile
 * {@code TaskDetailScreen} collects before-photos in component state and only ever uploads the
 * after-set. Both rows are driven against the real upload endpoint and the real persisted column,
 * so each produces a genuine verdict rather than being skipped.</p>
 *
 * <p><b>Production change required:</b> a {@code job_photos} table (job_id, url, photo_type,
 * uploaded_at) with its repository and {@code POST/GET /api/jobs/{id}/photos}, replacing the
 * comma-joined {@code completion_photo_urls} column. Out of scope for this suite (test code
 * only).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobPhotoTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JobRepository    jobRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private UserRepository   userRepo;
    @Autowired private ObjectMapper     json;
    @Autowired private jakarta.persistence.EntityManager em;

    /** Existing row the faults.branch_id FK (fk_faults_branch -> branches.id) requires. */
    private static final Long BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    /**
     * The technician is a real persisted user, and the job's fault is a real persisted fault —
     * {@code jobs.fault_id} and {@code faults.customer_id} are both enforced FKs, so neither can be
     * a synthetic id.
     */
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

    private String techBearer(Long techId) {
        return "Bearer " + jwt.createAccessToken(techId, "jobphototech", "TECHNICIAN", BRANCH_ID);
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

    private static byte[] bytes(int size) {
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, (byte) 0x41);
        return data;
    }

    /**
     * A JPEG photo fixture. Since the JOB-008 fix, {@code FileStorageService.store} verifies the
     * file's real magic bytes rather than trusting the declared Content-Type, so this payload
     * starts with the JPEG signature (FF D8 FF) to represent a genuine photo.
     */
    private static MockMultipartFile jpeg(String name) {
        byte[] data = bytes(2048);
        data[0] = (byte) 0xFF; data[1] = (byte) 0xD8; data[2] = (byte) 0xFF; data[3] = (byte) 0xE0;
        return new MockMultipartFile("files", name, "image/jpeg", data);
    }

    /** Uploads one photo through the real endpoint, with the sheet's photo_type form param. */
    private MvcResult uploadPhoto(Long techId, MockMultipartFile file, String photoType)
            throws Exception {
        return mvc.perform(multipart("/api/uploads/photos")
                .file(file)
                .param("photo_type", photoType)
                .header("Authorization", techBearer(techId))
                .with(req -> { req.setMethod("POST"); return req; }))
            .andReturn();
    }

    /**
     * A real client, a real fault reported by them, a real technician, and an IN_PROGRESS job
     * pointing at that fault. Built through the real POST /api/faults endpoint so the fault row
     * satisfies every FK and validation the schema enforces.
     */
    private final class Fixture {
        final User client = newUser(User.Role.CLIENT,     "Client Chandima");
        final User tech   = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        final Long faultId;
        final Job  job;

        Fixture() throws Exception {
            MvcResult res = mvc.perform(post("/api/faults")
                    .header("Authorization",
                        "Bearer " + jwt.createAccessToken(client.getId(), "photoclient", "CLIENT", BRANCH_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(reportRequest())))
                .andReturn();
            assertEquals(201, res.getResponse().getStatus(),
                "Fault setup POST failed. Body: " + res.getResponse().getContentAsString());
            faultId = json.readTree(res.getResponse().getContentAsString()).get("id").asLong();

            Fault fault = faultRepo.findById(faultId).orElseThrow();

            Job j = new Job();
            j.setJobNumber("JOB-PHOTO-" + uniq());
            j.setFaultId(faultId);
            j.setFaultNumber(fault.getFaultNumber());
            j.setTeamLeadId(tech.getId());
            j.setTeamLeadName(tech.getFullName());
            j.setTechnicianId(tech.getId());
            j.setTechnicianName(tech.getFullName());
            j.setCustomerId(client.getId());
            j.setCustomerName(client.getFullName());
            j.setStatus(Job.JobStatus.IN_PROGRESS);
            j.setPriority(Job.JobPriority.HIGH);
            job = jobRepo.save(j);
            jobRepo.flush();
            em.clear();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOB-006 — a BEFORE and an AFTER photo become two separate, typed records
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void uploadBeforeAfterPhotos_twoRowsCreated() throws Exception {
        Fixture f = new Fixture();
        Job job = f.job;

        // ── Steps 1-2: upload one BEFORE and one AFTER photo ─────────────────────────────
        MvcResult before = uploadPhoto(f.tech.getId(), jpeg("before.jpg"), "BEFORE");
        MvcResult after  = uploadPhoto(f.tech.getId(), jpeg("after.jpg"),  "AFTER");

        assertEquals(200, before.getResponse().getStatus(),
            "The BEFORE photo upload must succeed. Body: " + before.getResponse().getContentAsString());
        assertEquals(200, after.getResponse().getStatus(),
            "The AFTER photo upload must succeed. Body: " + after.getResponse().getContentAsString());

        List<String> urls = new ArrayList<>();
        for (MvcResult res : List.of(before, after)) {
            JsonNode node = json.readTree(res.getResponse().getContentAsString()).get("urls");
            assertNotNull(node, "Upload must return the stored URL(s)");
            node.forEach(u -> urls.add(u.asText()));
        }
        assertEquals(2, urls.size(), "Two uploads must yield two URLs");
        assertNotEquals(urls.get(0), urls.get(1),
            "Each stored photo must get its own unique URL, not be overwritten");

        // Attach them to the job the only way the API allows.
        UpdateJobRequest attach = new UpdateJobRequest();
        attach.setNewStatus("COMPLETED");
        attach.setCompletionPhotoUrls(String.join(",", urls));
        mvc.perform(patch("/api/jobs/{id}/status", job.getId())
                .header("Authorization", techBearer(f.tech.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(attach)))
            .andReturn();
        jobRepo.flush();
        em.clear();

        // ── Steps 3-5: the job must expose two typed photo records ───────────────────────
        MvcResult read = mvc.perform(get("/api/jobs/{id}", job.getId())
                .header("Authorization", techBearer(f.tech.getId())))
            .andReturn();
        String jobBody = read.getResponse().getContentAsString();
        JsonNode photos = json.readTree(jobBody).get("photos");

        assertAll("job photos as typed, individually addressable records",
            () -> assertNotNull(photos,
                "JOB-006 step 3 requires GET /api/jobs/{id} to expose a photos array. The Job "
                    + "entity has no photos collection — only the comma-separated "
                    + "completion_photo_urls TEXT column. Body: " + jobBody
                    + ". PRODUCTION CHANGE REQUIRED (job_photos table + endpoints)."),
            () -> {
                assertNotNull(photos);
                assertEquals(2, photos.size(), "Two uploads must produce two photo records");
            },
            () -> {
                assertNotNull(photos);
                List<String> types = new ArrayList<>();
                photos.forEach(p -> types.add(p.has("photoType") ? p.get("photoType").asText()
                    : p.path("photo_type").asText()));
                assertTrue(types.contains("BEFORE") && types.contains("AFTER"),
                    "One photo must be typed BEFORE and one AFTER. There is nowhere to record a "
                        + "photo type today — /api/uploads/photos ignores the photo_type param "
                        + "entirely. Types found: " + types);
            }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOB-015 — 1NF: one row per photo, never a comma-separated blob
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void photos_1NFCompliant_noCommaSeparated() throws Exception {
        Fixture f = new Fixture();
        Job job = f.job;

        // ── Step 1: upload two photos ────────────────────────────────────────────────────
        MvcResult first  = uploadPhoto(f.tech.getId(), jpeg("p1.jpg"), "BEFORE");
        MvcResult second = uploadPhoto(f.tech.getId(), jpeg("p2.jpg"), "AFTER");
        assertEquals(200, first.getResponse().getStatus());
        assertEquals(200, second.getResponse().getStatus());

        List<String> urls = new ArrayList<>();
        for (MvcResult res : List.of(first, second)) {
            json.readTree(res.getResponse().getContentAsString()).get("urls")
                .forEach(u -> urls.add(u.asText()));
        }

        UpdateJobRequest complete = new UpdateJobRequest();
        complete.setNewStatus("COMPLETED");
        complete.setCompletionPhotoUrls(String.join(",", urls));
        MvcResult completed = mvc.perform(patch("/api/jobs/{id}/status", job.getId())
                .header("Authorization", techBearer(f.tech.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(complete)))
            .andReturn();
        assertEquals(200, completed.getResponse().getStatus(),
            "Body: " + completed.getResponse().getContentAsString());

        jobRepo.flush();
        em.clear();
        Job stored = jobRepo.findById(job.getId()).orElseThrow();
        String storedUrls = stored.getCompletionPhotoUrls();

        // ── Steps 2-4: how the photos are actually stored ────────────────────────────────
        assertNotNull(storedUrls, "The uploaded photo URLs must have been persisted");
        assertFalse(storedUrls.contains(","),
            "JOB-015: each photo must be its own row with a single URL. jobs.completion_photo_urls "
                + "is a TEXT column holding all URLs comma-separated — a 1NF violation by design "
                + "(see the Job entity's own comment on the field). Stored value: \"" + storedUrls
                + "\". PRODUCTION CHANGE REQUIRED (job_photos table, one row per photo, replacing "
                + "this column).");
    }
}
