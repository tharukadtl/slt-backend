package lk.slt.fieldops.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-003 — XSS: a {@code <script>} payload stored in a fault description must come back escaped,
 * not as raw executable markup.
 *
 * <p>Harness matches {@code BillDisputeAmendmentIntegrationTest} — real filter chain, real MySQL,
 * {@code @Transactional} rollback, tokens minted by the app's own {@link JwtTokenProvider}.
 * MockMvc replaces the sheet's REST Assured (not a dependency of this module).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class XssSecurityTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private ObjectMapper     json;

    /** SEC-003 Test Data. */
    private static final String XSS_PAYLOAD = "<script>alert(document.cookie)</script>";

    private static final Long REAL_BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private User newClient() {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername("xssclient" + n);
        u.setPasswordHash("x");
        u.setFirstName("Xss");
        u.setLastName("Client");
        u.setFullName("Xss Client " + n);
        u.setPhone("0770000000");
        u.setRole(User.Role.CLIENT);
        return userRepo.save(u);
    }

    private ReportFaultRequest faultWithDescription(String description) {
        ReportFaultRequest r = new ReportFaultRequest();
        r.setCategory("INTERNET");
        r.setDescription(description);
        r.setLocationAddress("No. 5 Main Street, Colombo 03");
        r.setLocationCity("Colombo");
        r.setLocationDistrict("Colombo");
        r.setLatitude(6.9271);
        r.setLongitude(79.8612);
        r.setOpmcId(REAL_BRANCH_ID);
        r.setPriority("MEDIUM");
        return r;
    }

    @Test
    void descriptionFieldEscaped() throws Exception {
        User client = newClient();
        String clientHdr = "Bearer " + jwt.createAccessToken(
            client.getId(), client.getUsername(), "CLIENT", REAL_BRANCH_ID);

        // ── Step 1: POST a fault whose description carries the script tag ────────────────────
        MvcResult created = mvc.perform(post("/api/faults")
                .header("Authorization", clientHdr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(faultWithDescription(XSS_PAYLOAD))))
            .andReturn();

        assertEquals(201, created.getResponse().getStatus(),
            "Fault report should be accepted (201) — the payload must be stored, then escaped on "
                + "read, not rejected outright. Body: " + created.getResponse().getContentAsString());

        JsonNode createdBody = json.readTree(created.getResponse().getContentAsString());
        long faultId = createdBody.get("id").asLong();

        // ── Step 2: GET it back ─────────────────────────────────────────────────────────────
        MvcResult fetched = mvc.perform(get("/api/faults/{id}", faultId)
                .header("Authorization", clientHdr))
            .andReturn();

        assertEquals(200, fetched.getResponse().getStatus(),
            "Body: " + fetched.getResponse().getContentAsString());

        String  rawBody     = fetched.getResponse().getContentAsString();
        String  description = json.readTree(rawBody).get("description").asText();

        // ── Step 3: the stored/returned description must be escaped HTML entities ───────────
        assertTrue(description.contains("&lt;script&gt;"),
            "SEC-003 expects the description to be returned as escaped HTML entities "
                + "(&lt;script&gt;), so it can never execute. Actual description: " + description);
        assertFalse(description.contains("<script>"),
            "Raw <script> tag was returned unescaped — an XSS execution path exists. Actual: "
                + description);

        // Corroborate against the persisted row, not just the response projection.
        Fault stored = faultRepo.findById(faultId).orElseThrow();
        assertFalse(stored.getDescription().contains("<script>"),
            "Raw <script> tag was persisted unescaped. Stored: " + stored.getDescription());
    }
}
