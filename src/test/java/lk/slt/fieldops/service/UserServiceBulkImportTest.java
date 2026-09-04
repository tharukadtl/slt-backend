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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * RES-016 and RES-017 (05_RESOURCE_MGMT, FR-15) — bulk CSV user import: partial success with
 * per-row error messages, and per-row {@code opmcId} existence validation reusing the same guard
 * as single-user creation. (Branch/branchId was renamed to Opmc/opmcId as part of the OPMC
 * restructure, Stage B.)
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module ({@code pom.xml} carries only {@code spring-boot-starter-test} and
 * {@code spring-security-test}) and adding a test framework is a project decision, not this suite's.
 * MockMvc's {@code multipart()} through the real filter chain is the module's established convention
 * (see {@code JobPhotoTest}, {@code FileUploadContentValidationTest}) and drives the identical path:
 * real JWT filter, real {@code @PreAuthorize}, real {@code UserService}, real MySQL. Both tests are
 * {@code @Transactional} so their rows roll back.</p>
 *
 * <p><b>Placement.</b> The sheet's Automation Mapping names the class but no package. It lives in
 * {@code service} rather than {@code controller} because both rows are about
 * {@code UserService.importUsersFromCsv}'s behaviour — the controller is a two-line passthrough —
 * and it is driven through the real endpoint so the {@code @PreAuthorize} and multipart binding are
 * exercised too. See {@code UserServiceTest} (RES-018) for the single-user half of the same guard.
 * </p>
 *
 * <p><b>CSV contract</b> (documented on {@code UserService.importUsersFromCsv}): a header line that
 * is skipped, then {@code username,password,fullName,email,phone,address,role,opmcId,workgroupId}.
 * Only username, password, fullName and role are required.</p>
 *
 * <p><b>2026-09-03, CI-portable-database fix.</b> Both tests previously assumed OPMC id=1 already
 * existed ({@code REAL_OPMC_ID = 1L}) — dereferenced via {@code opmcRepo.findById(REAL_OPMC_ID)
 * .orElseThrow()} in {@code newWorkGroup}, and directly asserted as a precondition
 * ({@code assertTrue(opmcRepo.existsById(REAL_OPMC_ID))} in {@code validatesOpmcIdPerRow}) — a real
 * row that only ever existed because this suite ran against the same long-lived local dev database
 * all session. Fixed by creating a real, per-test {@code Opmc} instead, matching the established
 * {@code newOpmc()} pattern used correctly in ~40 sibling files; the "must exist" precondition
 * assertion is now trivially true by construction rather than an assumption about ambient state.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserServiceBulkImportTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private OpmcRepository   opmcRepo;
    @Autowired private WorkGroupRepository workGroupRepo;
    @Autowired private ObjectMapper     json;
    @Autowired private jakarta.persistence.EntityManager em;

    /** An OPMC id chosen to be absent — the value RES-017 names. */
    private static final long BOGUS_OPMC_ID = 999999L;

    private static final String CSV_HEADER =
        "username,password,fullName,email,phone,address,role,opmcId,workgroupId";

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, opmcId);
    }

    /** A fresh, genuinely persisted OPMC — no test may assume any OPMC id pre-exists. */
    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("RES-016/017 Test OPMC " + n);
        o.setCode("RBI" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    /**
     * A real Work Group fixture, since a TECHNICIAN/TEAM_LEAD row now requires
     * one (RES-024) and the dev DB carries none by default.
     */
    private WorkGroup newWorkGroup(Opmc opmc) {
        WorkGroup wg = new WorkGroup();
        wg.setName("RES-016/017 Test WG " + uniq());
        wg.setOpmc(opmc);
        wg.setIsActive(true);
        return workGroupRepo.save(wg);
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

    /** POSTs the CSV to the real endpoint and returns the parsed BulkUserImportResponse. */
    private JsonNode importCsv(Long callerId, Long opmcId, String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "users.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        MvcResult res = mvc.perform(multipart("/api/users/import").file(file)
                .header("Authorization", bearer(callerId, "SUPER_ADMIN", opmcId)))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "POST /api/users/import must return 200. Body: "
                + res.getResponse().getContentAsString());

        return json.readTree(res.getResponse().getContentAsString());
    }

    private static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-016 — partial success, per-row errors
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void partialSuccessPerRowErrors() throws Exception {
        // ── Arrange: a caller, plus a username that is already taken ─────────────────────────
        Opmc opmc = newOpmc();
        User caller = newSuperAdmin(opmc.getId());
        User existing = newSuperAdmin(opmc.getId());
        userRepo.flush();

        String goodUsername = "res016good" + uniq();
        WorkGroup wg = newWorkGroup(opmc);

        String csv = CSV_HEADER + "\n"
            // row 2 — valid
            + goodUsername + ",Passw0rd!,Nimal Perera,nimal@slt.lk,0771234567,Colombo,TECHNICIAN,"
                + opmc.getId() + "," + wg.getId() + "\n"
            // row 3 — duplicate username
            + existing.getUsername() + ",Passw0rd!,Duplicate Dinesh,dup@slt.lk,0771234568,Kandy,"
                + "TECHNICIAN," + opmc.getId() + ",\n"
            // row 4 — invalid role
            + "res016bad" + uniq() + ",Passw0rd!,Bad Role Bandara,bad@slt.lk,0771234569,Galle,"
                + "NOTAROLE," + opmc.getId() + ",\n";

        // ── Act: steps 1-2 ───────────────────────────────────────────────────────────────────
        JsonNode result = importCsv(caller.getId(), opmc.getId(), csv);

        List<String> errors   = textList(result.path("errors"));
        List<String> created  = textList(result.path("createdUsernames"));

        assertAll("one bad row must not block the good ones, and each failure is reported per row",

            // ── Step 3: counts ──────────────────────────────────────────────────────────────
            () -> assertEquals(3, result.path("totalRows").asInt(),
                "All three data rows must be counted, result: " + result),
            () -> assertEquals(1, result.path("successCount").asInt(),
                "Exactly the one valid row must import, errors: " + errors),
            () -> assertEquals(2, result.path("failureCount").asInt(),
                "The duplicate-username and invalid-role rows must both fail, errors: " + errors),

            // ── Step 4: per-row, specific error messages ────────────────────────────────────
            () -> assertEquals(2, errors.size(), "One message per failed row, got: " + errors),
            () -> assertTrue(errors.stream().allMatch(e -> e.matches("^Row \\d+: .+")),
                "Every error must name the row it came from as 'Row N: ...', got: " + errors),
            () -> assertTrue(errors.stream().anyMatch(e -> e.startsWith("Row 3:")
                    && e.contains(existing.getUsername()) && e.contains("already taken")),
                "The duplicate username must be reported against row 3 by name, got: " + errors),
            () -> assertTrue(errors.stream().anyMatch(e -> e.startsWith("Row 4:")
                    && e.contains("NOTAROLE")),
                "The invalid role must be reported against row 4 by value, got: " + errors),

            // ── Step 5: only the valid row was created ─────────────────────────────────────
            () -> assertEquals(List.of(goodUsername), created,
                "Only the valid row's username may be reported as created, got: " + created),

            // ── And the created user is real, with the CSV's own field values ──────────────
            () -> {
                em.flush();
                User imported = userRepo.findByUsername(goodUsername)
                    .orElseThrow(() -> new AssertionError(
                        "The valid row must have produced a real users row"));
                assertEquals("Nimal Perera", imported.getFullName());
                assertEquals(User.Role.TECHNICIAN, imported.getRole());
                assertEquals(opmc.getId(), imported.getOpmcId());
                assertNotEquals("Passw0rd!", imported.getPasswordHash(),
                    "The CSV password must be hashed, never stored raw");
            }
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-017 — bulk import validates opmcId per row, same as single creation
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void validatesOpmcIdPerRow() throws Exception {
        // ── Arrange ──────────────────────────────────────────────────────────────────────────
        Opmc opmc = newOpmc();
        User caller = newSuperAdmin(opmc.getId());
        userRepo.flush();

        assertFalse(opmcRepo.existsById(BOGUS_OPMC_ID),
            "Precondition: OPMC " + BOGUS_OPMC_ID + " must not exist");
        assertTrue(opmcRepo.existsById(opmc.getId()),
            "Precondition: OPMC " + opmc.getId() + " must exist");

        String goodUsername  = "res017good" + uniq();
        String bogusUsername = "res017bogus" + uniq();
        WorkGroup wg = newWorkGroup(opmc);

        String csv = CSV_HEADER + "\n"
            // row 2 — valid, real OPMC
            + goodUsername + ",Passw0rd!,Nimal Perera,nimal2@slt.lk,0771234570,Colombo,TECHNICIAN,"
                + opmc.getId() + "," + wg.getId() + "\n"
            // row 3 — bogus OPMC
            + bogusUsername + ",Passw0rd!,Bogus Branch Bandara,bogus@slt.lk,0771234571,Matara,"
                + "TECHNICIAN," + BOGUS_OPMC_ID + ",\n";

        // ── Act: step 1 ──────────────────────────────────────────────────────────────────────
        JsonNode result = importCsv(caller.getId(), opmc.getId(), csv);

        List<String> errors  = textList(result.path("errors"));
        List<String> created = textList(result.path("createdUsernames"));

        assertAll("the bulk path reuses the single-creation OPMC guard, per row",

            // ── Step 2: the bogus-OPMC row fails, with the same message ────────────────────
            () -> assertTrue(errors.stream().anyMatch(
                    e -> e.contains("OPMC " + BOGUS_OPMC_ID + " does not exist")),
                "The bogus OPMC must be rejected with the same message UserService.createUser "
                    + "raises for a single user — not a separate, weaker check. Errors: " + errors),
            () -> assertTrue(errors.stream().anyMatch(e -> e.startsWith("Row 3:")),
                "The failure must be attributed to the row it came from, got: " + errors),
            () -> assertFalse(userRepo.findByUsername(bogusUsername).isPresent(),
                "No user may be created against an OPMC that does not exist"),

            // ── Step 3: the valid row still succeeds ──────────────────────────────────────
            () -> assertEquals(1, result.path("successCount").asInt(),
                "The valid row must still import, errors: " + errors),
            () -> assertEquals(List.of(goodUsername), created,
                "Only the good row may be reported as created, got: " + created),
            () -> {
                em.flush();
                assertTrue(userRepo.findByUsername(goodUsername).isPresent(),
                    "The valid row must have produced a real users row");
            },
            () -> assertEquals(1, result.path("failureCount").asInt(),
                "Exactly the bogus-OPMC row must fail, errors: " + errors)
        );
    }
}
