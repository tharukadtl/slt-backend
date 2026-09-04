package lk.slt.fieldops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RES-018 (05_RESOURCE_MGMT, FR-15) — single-user creation validates a <i>supplied</i>
 * {@code opmcId} against the opmcs table (formerly the branches table — Branch/branchId was
 * renamed to Opmc/opmcId as part of the OPMC restructure, Stage B), without making it required
 * for the roles that legitimately have no OPMC.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module and adding a test framework is a project decision, not this suite's. MockMvc through the
 * real filter chain is the module's established convention (see {@code JobIntegrationTest},
 * {@code BillDisputeAmendmentIntegrationTest}) and drives the identical path: real JWT filter, real
 * {@code @PreAuthorize}, real {@code UserService}, real MySQL. The test is {@code @Transactional} so
 * its rows roll back.</p>
 *
 * <p><b>Test Type column vs. Automation Mapping.</b> The Test Type cell says Unit but the mapping
 * cell says "Integration Test | REST Assured" and every Test Step is an HTTP call, so this is
 * written as an integration test. It lives in {@code service} rather than {@code controller} because
 * the guard under test is {@code UserService.createUser}'s, not the controller's — the controller is
 * a two-line passthrough. See {@code UserServiceBulkImportTest} (RES-016/017) for the bulk half of
 * the same guard.</p>
 *
 * <p><b>Requiredness is deliberately untouched</b> — {@code UserService.createUser} only validates
 * {@code opmcId} when one is supplied ({@code req.getOpmcId() != null && !opmcRepo.existsById}),
 * because SUPER_ADMIN and CLIENT users legitimately have no OPMC in the real data. Case B below
 * pins that so a future "make opmcId mandatory" change cannot land unnoticed.</p>
 *
 * <p><b>2026-09-03, CI-portable-database fix.</b> This test previously assumed OPMC id=1 already
 * existed ({@code REAL_OPMC_ID = 1L}) — dereferenced via {@code opmcRepo.findById(REAL_OPMC_ID)
 * .orElseThrow()} in {@code newWorkGroup}, and directly asserted as a precondition
 * ({@code assertTrue(opmcRepo.existsById(REAL_OPMC_ID))}) — a real row that only ever existed
 * because this suite ran against the same long-lived local dev database all session. Fixed by
 * creating a real, per-test {@code Opmc} instead, matching the established {@code newOpmc()}
 * pattern used correctly in ~40 sibling files.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserServiceTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private OpmcRepository   opmcRepo;
    @Autowired private WorkGroupRepository workGroupRepo;
    @Autowired private ObjectMapper     json;

    /** An OPMC id chosen to be absent — the value the row names. */
    private static final long BOGUS_OPMC_ID = 999999L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, opmcId);
    }

    /** A fresh, genuinely persisted OPMC — no test may assume any OPMC id pre-exists. */
    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("RES-018 Test OPMC " + n);
        o.setCode("RUS" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private User newSuperAdmin(Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("sa" + n);
        u.setPasswordHash("x");
        u.setFirstName("Super");
        u.setLastName("Admin");
        u.setFullName("Super Admin");
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(User.Role.SUPER_ADMIN);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    /** The CreateUserRequest body, with opmcId included only when non-null. */
    private String createBody(String username, String fullName, String role, Long opmcId)
            throws Exception {
        return createBody(username, fullName, role, opmcId, null);
    }

    /** The CreateUserRequest body, with opmcId/workgroupId included only when non-null. */
    private String createBody(String username, String fullName, String role, Long opmcId,
            Long workgroupId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", "Passw0rd!");
        body.put("fullName", fullName);
        body.put("email",    username + "@slt.lk");
        body.put("phone",    "07" + (10000000L + (uniq() % 80000000L)));
        body.put("role",     role);
        if (opmcId != null) body.put("opmcId", opmcId);
        if (workgroupId != null) body.put("workgroupId", workgroupId);
        return json.writeValueAsString(body);
    }

    /**
     * A real Work Group fixture, since a TECHNICIAN/TEAM_LEAD creation now
     * requires one (RES-024) and the dev DB carries none by default.
     */
    private WorkGroup newWorkGroup(Opmc opmc) {
        WorkGroup wg = new WorkGroup();
        wg.setName("RES-018 Test WG " + uniq());
        wg.setOpmc(opmc);
        wg.setIsActive(true);
        return workGroupRepo.save(wg);
    }

    private MvcResult createUser(Long callerId, Long callerOpmcId, String body) throws Exception {
        return mvc.perform(post("/api/users")
                .header("Authorization", bearer(callerId, "SUPER_ADMIN", callerOpmcId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
    }

    @Test
    void opmcIdExistenceValidation() throws Exception {
        // ── Arrange ──────────────────────────────────────────────────────────────────────────
        Opmc opmc = newOpmc();
        User caller = newSuperAdmin(opmc.getId());
        userRepo.flush();

        assertFalse(opmcRepo.existsById(BOGUS_OPMC_ID),
            "Precondition: OPMC " + BOGUS_OPMC_ID + " must not exist");
        assertTrue(opmcRepo.existsById(opmc.getId()),
            "Precondition: OPMC " + opmc.getId() + " must exist");

        String bogusUsername      = "res018bogus" + uniq();
        String superAdminUsername = "res018sa"    + uniq();
        String technicianUsername = "res018tech"  + uniq();

        assertAll("a supplied opmcId must exist; an omitted one is still legal",

            // ── Case A, steps 1-2: bogus opmcId is rejected, and says why ──────────────────
            () -> {
                MvcResult res = createUser(caller.getId(), opmc.getId(),
                    createBody(bogusUsername, "Bogus Branch Bandara", "TECHNICIAN",
                        BOGUS_OPMC_ID));
                String body = res.getResponse().getContentAsString();

                assertEquals(400, res.getResponse().getStatus(),
                    "An opmcId that does not exist must be rejected with 400. Body: " + body);
                assertTrue(body.contains("OPMC " + BOGUS_OPMC_ID + " does not exist"),
                    "The rejection must name the OPMC that does not exist, body: " + body);
                assertFalse(userRepo.findByUsername(bogusUsername).isPresent(),
                    "No user may be created against an OPMC that does not exist");
            },

            // ── Case B, steps 3-4: a SUPER_ADMIN with no OPMC is still created ─────────────
            () -> {
                MvcResult res = createUser(caller.getId(), opmc.getId(),
                    createBody(superAdminUsername, "Super Sarath", "SUPER_ADMIN", null));
                String body = res.getResponse().getContentAsString();

                assertEquals(201, res.getResponse().getStatus(),
                    "Roles that legitimately have no OPMC must still be creatable — "
                        + "requiredness is untouched. Body: " + body);

                JsonNode created = json.readTree(body);
                assertTrue(created.path("opmcId").isNull(),
                    "An omitted opmcId must stay null, body: " + body);
                assertEquals("SUPER_ADMIN", created.path("role").asText());
            },

            // ── And a real opmcId is accepted and persisted, so the guard isn't a blanket
            //    rejection of every opmcId ──────────────────────────────────────────────────
            () -> {
                WorkGroup wg = newWorkGroup(opmc);
                MvcResult res = createUser(caller.getId(), opmc.getId(),
                    createBody(technicianUsername, "Nimal Perera", "TECHNICIAN",
                        opmc.getId(), wg.getId()));
                String body = res.getResponse().getContentAsString();

                assertEquals(201, res.getResponse().getStatus(),
                    "A real opmcId must be accepted. Body: " + body);
                assertEquals(opmc.getId().longValue(),
                    json.readTree(body).path("opmcId").asLong(),
                    "The supplied OPMC must be persisted on the user, body: " + body);
            }
        );
    }
}
