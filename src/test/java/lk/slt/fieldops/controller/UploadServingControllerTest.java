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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA_Compliance_Consolidated_Report.md Stage G Minor — "{@code /uploads/**} served publicly with
 * no auth — fault/signature photos world-readable if the URL is known" ({@code SecurityConfig.java:55}).
 *
 * <p>Fixed by removing {@code /uploads/**} from {@code permitAll()} and replacing the old
 * unauthenticated static {@code ResourceHandler} ({@code Application.java}) with
 * {@link UploadServingController} — a real, authorized controller at the same URL shape
 * ({@code /uploads/{subfolder}/{filename}}), so every already-persisted photo URL in
 * {@code Fault.photoUrls}/{@code Job.completionPhotoUrls}/{@code Payment.disputePhotoUrl} keeps
 * working with no data migration.</p>
 *
 * <p><b>The header problem, investigated before implementing.</b> Every real caller
 * ({@code frontend-admin}'s {@code <img>}, {@code SLTMobileApp}'s {@code <Image>}) loads these
 * URLs directly — confirmed by a full grep of both codebases before this fix, not assumed — and
 * neither a browser {@code <img>} tag nor React Native's {@code <Image>} can attach a custom
 * {@code Authorization} header. Requiring the normal Bearer-header auth here would have silently
 * broken every fault/job photo in the app, the same header-vs-tag mismatch already found and
 * corrected in the WebSocket investigation earlier this report. Fixed by extending
 * {@code SecurityConfig}'s {@code jwtAuthFilter} to also accept the token via a {@code ?token=}
 * query parameter — the frontend URL-builders now append the caller's own current JWT — while the
 * normal header path keeps working unchanged for any programmatic caller.</p>
 *
 * <p><b>Authorization, not just authentication.</b> A CLIENT may only read a file that genuinely
 * belongs to one of their own Faults ({@code Fault.photoUrls}, client-submitted evidence) or Jobs
 * ({@code Job.completionPhotoUrls}, technician after-service photos) — the two photo sets ever
 * rendered to a Client anywhere in the app. Staff (any authenticated non-CLIENT role) may read
 * any file, the same trust boundary already extended on the Fault/Job detail views these photos
 * are shown from.</p>
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain, matching this project's established
 * convention: real JWT filter, real {@code @PreAuthorize}, real MySQL, {@code @Transactional}
 * rollback for DB rows. The one real file this test uploads to disk via {@code POST
 * /api/uploads/photos} is not deleted afterward — same precedent as
 * {@code FileUploadContentValidationTest}, which does the same.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UploadServingControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private FaultRepository faultRepo;
    @Autowired private JobRepository jobRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private ObjectMapper json;

    private static final Long REAL_OPMC_ID = 1L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    // Fault.customerId/Job.customerId are real FKs to users(id) — a synthetic id isn't enough,
    // the fixture needs a genuine persisted User row, same pattern PaymentOpmcScopingTest uses.
    private User newUser(User.Role role) {
        long n = uniq();
        User u = new User();
        u.setUsername("uptest" + n);
        u.setPasswordHash("x");
        u.setFirstName("Upload");
        u.setLastName("Test" + n);
        u.setFullName("Upload Test " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_OPMC_ID);
        return userRepo.save(u);
    }

    private String bearer(User user) {
        return "Bearer " + jwt.createAccessToken(user.getId(), user.getUsername(), user.getRole().name(), REAL_OPMC_ID);
    }

    /** 2KB of filler behind a real JPEG signature — same shape as FileUploadContentValidationTest. */
    private static byte[] jpegBytes() {
        byte[] data = new byte[2048];
        java.util.Arrays.fill(data, (byte) 0x41);
        byte[] sig = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 };
        System.arraycopy(sig, 0, data, 0, sig.length);
        return data;
    }

    /** Uploads a real JPEG as the given caller and returns the real, disk-backed "/uploads/photos/..." URL. */
    private String uploadRealPhoto(User caller) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/uploads/photos")
                .file(new MockMultipartFile("files", "evidence.jpg", "image/jpeg", jpegBytes()))
                .header("Authorization", bearer(caller)))
            .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("urls").get(0).asText();
    }

    private Fault faultOwnedBy(User customer, String photoUrl) {
        Fault f = new Fault();
        f.setFaultNumber("FLT-UPLOADTEST-" + uniq());
        f.setOpmcId(REAL_OPMC_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("Upload-auth ownership fixture");
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.REPORTED);
        f.setPhotoUrls(photoUrl);
        return faultRepo.save(f);
    }

    private Job jobOwnedBy(User customer, Long faultId, String completionPhotoUrl, Long teamLeadId) {
        Job j = new Job();
        j.setJobNumber("JOB-UPLOADTEST-" + uniq());
        j.setFaultId(faultId);
        j.setCustomerId(customer.getId());
        j.setTeamLeadId(teamLeadId);
        j.setStatus(Job.JobStatus.COMPLETED);
        j.setCompletionPhotoUrls(completionPhotoUrl);
        return jobRepo.save(j);
    }

    @Test
    void unauthenticatedRequest_isRejected_notWorldReadable() throws Exception {
        User owner = newUser(User.Role.CLIENT);
        String photoUrl = uploadRealPhoto(owner);
        faultOwnedBy(owner, photoUrl);

        // No Authorization header, no ?token= — exactly the gap this fix closes.
        mvc.perform(get(photoUrl))
            .andExpect(status().is(401));
    }

    @Test
    void ownerClient_viaQueryTokenLikeAnImgTagWould_succeedsWithRealBytes() throws Exception {
        User owner = newUser(User.Role.CLIENT);
        String photoUrl = uploadRealPhoto(owner);
        faultOwnedBy(owner, photoUrl);

        MvcResult result = mvc.perform(get(photoUrl).param("token", jwt.createAccessToken(
                    owner.getId(), owner.getUsername(), "CLIENT", REAL_OPMC_ID)))
            .andExpect(status().isOk())
            .andReturn();

        assertArrayEquals(jpegBytes(), result.getResponse().getContentAsByteArray(),
            "The bytes served back must be the exact bytes uploaded, not a placeholder or empty body.");
        assertEquals("image/jpeg", result.getResponse().getContentType());
    }

    @Test
    void nonOwnerClient_isForbidden_notJustAnyValidToken() throws Exception {
        User owner    = newUser(User.Role.CLIENT);
        User stranger = newUser(User.Role.CLIENT);
        String photoUrl = uploadRealPhoto(owner);
        faultOwnedBy(owner, photoUrl);

        mvc.perform(get(photoUrl).param("token", jwt.createAccessToken(
                    stranger.getId(), stranger.getUsername(), "CLIENT", REAL_OPMC_ID)))
            .andExpect(status().is(403));
    }

    @Test
    void staffCaller_canReadAnyPhoto_regardlessOfOwnership() throws Exception {
        User owner = newUser(User.Role.CLIENT);
        User admin = newUser(User.Role.SUPER_ADMIN);
        String photoUrl = uploadRealPhoto(owner);
        faultOwnedBy(owner, photoUrl);

        mvc.perform(get(photoUrl).param("token", jwt.createAccessToken(
                    admin.getId(), admin.getUsername(), "SUPER_ADMIN", REAL_OPMC_ID)))
            .andExpect(status().isOk());
    }

    @Test
    void ownerClient_viaAuthorizationHeader_stillWorksForProgrammaticCallers() throws Exception {
        User owner = newUser(User.Role.CLIENT);
        String photoUrl = uploadRealPhoto(owner);
        faultOwnedBy(owner, photoUrl);

        // The header path must keep working unchanged — the ?token= fallback only fires when no
        // (or no valid) Bearer header is present, per SecurityConfig's jwtAuthFilter.
        mvc.perform(get(photoUrl).header("Authorization", bearer(owner)))
            .andExpect(status().isOk());
    }

    @Test
    void jobCompletionPhoto_ownershipTraceableThroughJob_notJustFault() throws Exception {
        User owner     = newUser(User.Role.CLIENT);
        User stranger  = newUser(User.Role.CLIENT);
        User technician = newUser(User.Role.TECHNICIAN);
        User teamLead  = newUser(User.Role.TEAM_LEAD);
        String photoUrl = uploadRealPhoto(technician);
        Fault f = faultOwnedBy(owner, null);
        jobOwnedBy(owner, f.getId(), photoUrl, teamLead.getId());

        // The owning Client can read their own job's after-service photo...
        mvc.perform(get(photoUrl).param("token", jwt.createAccessToken(
                    owner.getId(), owner.getUsername(), "CLIENT", REAL_OPMC_ID)))
            .andExpect(status().isOk());

        // ...a different Client cannot, even though the file exists and the URL is valid.
        mvc.perform(get(photoUrl).param("token", jwt.createAccessToken(
                    stranger.getId(), stranger.getUsername(), "CLIENT", REAL_OPMC_ID)))
            .andExpect(status().is(403));
    }

    @Test
    void nonexistentFile_returns404_notServedNotLeaked() throws Exception {
        User admin = newUser(User.Role.SUPER_ADMIN);
        mvc.perform(get("/uploads/photos/does-not-exist-" + uniq() + ".jpg")
                .param("token", jwt.createAccessToken(admin.getId(), admin.getUsername(), "SUPER_ADMIN", REAL_OPMC_ID)))
            .andExpect(status().is(404));
    }
}
