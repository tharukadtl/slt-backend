package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * FAULT-016 (02_FAULT_TRACKING, FR-5) — {@code GET /api/faults} filtered by status and category
 * must return only items matching the filter.
 *
 * <p><b>Tool substitution.</b> The row maps to a Newman/Postman artifact,
 * {@code SLT_Faults_Collection.json → Filter_By_Category_And_Status}. That collection is checked in
 * at {@code fieldops/src/test/postman/SLT_Faults_Collection.json} so CI can run it once
 * {@code newman} and a live server are available; neither is available in this environment
 * (newman is not installed and nothing listens on :8080), so it cannot produce a verdict on its
 * own. This class is the executable twin, following the same precedent as AUTH-009's
 * {@code SltAuthCollectionTest}: the same requests and the same assertions, run through the REAL
 * filter chain with MockMvc against the real database.</p>
 *
 * <p><b>What this row exposed, and the fix.</b> {@code FaultController.getAll()} used to take no
 * arguments — it declared neither a {@code status} nor a {@code category} request parameter and
 * delegated straight to {@code faultService.getAllFaults()}. Spring silently discards unbound query
 * parameters, so the endpoint returned every fault in the database regardless of the filter. Both
 * parameters are now bound and pushed into a database query
 * ({@code FaultRepository.findByOptionalStatusAndCategory}): each is optional, either alone narrows
 * on that field, both are AND-ed, and neither still returns everything.</p>
 *
 * <p><b>Shape note.</b> The row's script reads {@code resp.json().content}, i.e. a Spring
 * {@code Page} envelope. The endpoint returns a bare JSON array of {@code FaultDTO}. Also, the row
 * filters on {@code category=BROADBAND}; the backend enum value is {@code INTERNET}
 * ({@code FaultController.normalizeMobileCategory} maps the mobile alias on write only), so
 * {@code INTERNET} is what is asserted.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SltFaultsCollectionTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private UserRepository   userRepo;
    @Autowired private ObjectMapper     json;

    private static final Long REAL_BRANCH_ID = 1L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    // RES-023 (Stage D) — GET /api/faults now derives the caller's OPMC scope
    // from their own User row (never trusted off the JWT claim), so the bearer
    // here must belong to a real, persisted ADMIN in REAL_BRANCH_ID's OPMC —
    // a bare unbacked userId would now correctly see nothing.
    private User adminUser;

    private String adminBearer() {
        if (adminUser == null) {
            long n = SEQ.incrementAndGet();
            User u = new User();
            u.setUsername("filteradmin" + n);
            u.setPasswordHash("x");
            u.setFirstName("Filter");
            u.setLastName("Admin");
            u.setFullName("Filter Admin " + n);
            u.setPhone("07" + (10000000L + (n % 80000000L)));
            u.setRole(User.Role.ADMIN);
            u.setOpmcId(REAL_BRANCH_ID);
            adminUser = userRepo.save(u);
        }
        return "Bearer " + jwt.createAccessToken(
            adminUser.getId(), adminUser.getUsername(), "ADMIN", REAL_BRANCH_ID);
    }

    private User newClient() {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername("fu" + n);
        u.setPasswordHash("x");
        u.setFirstName("Filter");
        u.setLastName("Client");
        u.setFullName("Filter Client " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(User.Role.CLIENT);
        u.setOpmcId(REAL_BRANCH_ID);
        return userRepo.save(u);
    }

    /** Persists a fault directly so the fixture can contain statuses the API cannot reach in one hop. */
    private Fault newFault(User client, Fault.FaultCategory category, Fault.FaultStatus status) {
        long n = SEQ.incrementAndGet();
        Fault f = new Fault();
        f.setFaultNumber("FLT-TEST-" + n);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(client.getId());
        f.setCustomerName(client.getFullName());
        f.setCategory(category);
        f.setDescription("Filter fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(status);
        return faultRepo.save(f);
    }

    /**
     * The ids of this test's own fixture rows that appear in a response array, sorted ascending.
     * Scoping the count to the fixture keeps the assertions deterministic even though the suite
     * runs against the shared dev database, which already contains unrelated faults; sorting keeps
     * them independent of the response's ordering, which is not what this row is testing.
     */
    private List<Long> fixtureIdsIn(JsonNode array, List<Long> fixtureIds) {
        List<Long> found = new ArrayList<>();
        for (JsonNode fault : array) {
            long id = fault.path("id").asLong();
            if (fixtureIds.contains(id)) found.add(id);
        }
        Collections.sort(found);
        return found;
    }

    @Test
    void filterByCategoryAndStatus() throws Exception {
        User client = newClient();

        // A deliberately mixed fixture: one item matches the filter, three do not.
        Long matchesBoth   = newFault(client, Fault.FaultCategory.INTERNET, Fault.FaultStatus.REPORTED).getId();
        Long wrongStatus   = newFault(client, Fault.FaultCategory.INTERNET, Fault.FaultStatus.COMPLETED).getId();
        Long wrongCategory = newFault(client, Fault.FaultCategory.TV,       Fault.FaultStatus.REPORTED).getId();
        Long neither       = newFault(client, Fault.FaultCategory.PHONE,    Fault.FaultStatus.COMPLETED).getId();
        List<Long> fixtureIds = List.of(matchesBoth, wrongStatus, wrongCategory, neither);
        faultRepo.flush();

        // Baseline: the unfiltered call must still return everything (no existing caller broken).
        MvcResult unfiltered = mvc.perform(get("/api/faults")
                .header("Authorization", adminBearer()))
            .andReturn();
        assertEquals(200, unfiltered.getResponse().getStatus(),
            "Body: " + unfiltered.getResponse().getContentAsString());
        JsonNode allFaults = json.readTree(unfiltered.getResponse().getContentAsString());
        assertEquals(4, fixtureIdsIn(allFaults, fixtureIds).size(),
            "GET /api/faults with no filter params must keep returning every fault, "
                + "including all 4 fixture rows.");

        // ── Steps 1-2: GET /api/faults?status=OPEN&category=INTERNET ────────────────────
        MvcResult res = mvc.perform(get("/api/faults")
                .param("status", "OPEN")
                .param("category", "INTERNET")
                .header("Authorization", adminBearer()))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);

        JsonNode faults = json.readTree(body);
        assertTrue(faults.isArray(),
            "GET /api/faults returns a bare JSON array, not a {content:[...]} page. Body: " + body);

        // ── Step 3: every returned item must match BOTH filter criteria ─────────────────
        List<String> mismatches = new ArrayList<>();
        for (JsonNode fault : faults) {
            String statusDisplay = fault.path("statusDisplay").asText();
            String category      = fault.path("category").asText();
            if (!"OPEN".equals(statusDisplay) || !"INTERNET".equals(category)) {
                mismatches.add("#" + fault.path("id").asLong()
                    + " status=" + statusDisplay + " category=" + category);
            }
        }
        assertTrue(mismatches.isEmpty(),
            "Every item must match status=OPEN AND category=INTERNET. FaultController.getAll() "
                + "declares no status/category request parameters, so the filter is ignored and the "
                + "full table is returned. " + mismatches.size() + " of " + faults.size()
                + " returned faults do not match: "
                + mismatches.subList(0, Math.min(10, mismatches.size())));

        // ── Step 3b: …and the filter must GENUINELY NARROW, not just return 200 ─────────
        // Without this, an endpoint that filtered too aggressively (or returned an empty
        // array outright) would pass the mismatch check above vacuously: "zero mismatches
        // among zero items" is trivially true. Counts are scoped to this test's own fixture
        // rows because the suite runs against the shared dev database, which already holds
        // unrelated faults; a global array size would not be deterministic.
        assertEquals(List.of(matchesBoth), fixtureIdsIn(faults, fixtureIds),
            "status=OPEN&category=INTERNET must return exactly the one fixture fault that "
                + "matches BOTH criteria (#" + matchesBoth + ") — not an empty array, and none "
                + "of the three non-matching fixture rows. Body: " + body);
        assertTrue(faults.size() < allFaults.size(),
            "The filtered list must be strictly smaller than the unfiltered list ("
                + faults.size() + " vs " + allFaults.size() + "), proving server-side narrowing.");

        // ── Step 4: the same for a status-only filter ──────────────────────────────────
        MvcResult completed = mvc.perform(get("/api/faults")
                .param("status", "COMPLETED")
                .header("Authorization", adminBearer()))
            .andReturn();

        String completedBody = completed.getResponse().getContentAsString();
        assertEquals(200, completed.getResponse().getStatus(), "Body: " + completedBody);

        JsonNode completedFaults = json.readTree(completedBody);
        List<String> statusMismatches = new ArrayList<>();
        for (JsonNode fault : completedFaults) {
            if (!"COMPLETED".equals(fault.path("status").asText())) {
                statusMismatches.add("#" + fault.path("id").asLong()
                    + " status=" + fault.path("status").asText());
            }
        }
        assertTrue(statusMismatches.isEmpty(),
            "?status=COMPLETED must return only COMPLETED faults, but " + statusMismatches.size()
                + " non-matching items came back: "
                + statusMismatches.subList(0, Math.min(10, statusMismatches.size())));

        // …and again, it must actually contain the COMPLETED fixtures rather than nothing.
        assertEquals(List.of(wrongStatus, neither), fixtureIdsIn(completedFaults, fixtureIds),
            "?status=COMPLETED must return exactly the 2 COMPLETED fixture faults (#" + wrongStatus
                + ", #" + neither + ") and neither REPORTED one. Body: " + completedBody);
        assertTrue(completedFaults.size() < allFaults.size(),
            "?status=COMPLETED must narrow the list (" + completedFaults.size()
                + " vs " + allFaults.size() + " unfiltered).");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // API-004 (11_API_ENDPOINTS) — GET /api/faults must be paginated
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * API-004: {@code GET /api/faults?page=0&size=20} with an admin token must answer 200 with a
     * paginated envelope — {@code totalElements} present and {@code content.length <= 20}.
     *
     * <p><b>Why this is not covered by {@link #filterByCategoryAndStatus} above, despite being in
     * the same file and on the same endpoint.</b> That test (FAULT-016) asserts <i>which</i> faults
     * come back for a given filter; it says nothing about how many come back per request or about
     * the response envelope, and it would pass unchanged against an endpoint that returns the entire
     * table on every call. API-004's assertion is precisely the pagination contract — a different
     * property of the same endpoint. It is placed in this class because the row's own Automation
     * Mapping points at the faults collection and this is that collection's executable twin.</p>
     *
     * <p><b>Tool substitution</b> is the same as the class above: the row maps to
     * {@code SLT_Field_Ops_API.postman_collection.json → Faults::listFaultsPaginated}. No file of
     * that name exists in the repository — the checked-in collections are split per domain, and the
     * faults one is {@code fieldops/src/test/postman/SLT_Faults_Collection.json}. newman is not
     * installed and nothing listens on :8080, so this MockMvc twin is what produces the verdict
     * (the AUTH-009 precedent).</p>
     */
    @Test
    void listFaultsPaginated() throws Exception {
        // Pre-condition: at least one fault exists, so totalElements >= 1 is a real assertion.
        User client = newClient();
        Long seeded = newFault(client, Fault.FaultCategory.INTERNET, Fault.FaultStatus.REPORTED).getId();
        faultRepo.flush();
        assertNotNull(seeded);

        // ── Step 1: GET /api/faults?page=0&size=20 with an admin token ─────────────────────
        MvcResult res = mvc.perform(get("/api/faults")
                .param("page", "0")
                .param("size", "20")
                .header("Authorization", adminBearer()))
            .andReturn();

        String body = res.getResponse().getContentAsString();

        // ── Step 2: HTTP 200 ──────────────────────────────────────────────────────────────
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);

        JsonNode parsed = json.readTree(body);

        // ── Steps 3-4 + Expected Result: a paginated envelope ─────────────────────────────
        // The row's own assertion is pm.expect(resp.json().totalElements).to.be.at.least(1),
        // which presupposes a Spring Page envelope {content:[...], totalElements:N, ...}.
        assertFalse(parsed.isArray(),
            "API-004 expects a paginated envelope with totalElements, but GET /api/faults returns "
                + "a BARE JSON ARRAY. FaultController.getAll() declares no page/size parameters and "
                + "returns the full result of faultService.getAllFaults() — the ?page=0&size=20 "
                + "query parameters are silently discarded by Spring as unbound, so every caller "
                + "receives the entire faults table on every request. Body (first 300 chars): "
                + body.substring(0, Math.min(300, body.length())));

        assertTrue(parsed.has("totalElements"),
            "The response must carry totalElements. Body (first 300 chars): "
                + body.substring(0, Math.min(300, body.length())));
        assertTrue(parsed.path("totalElements").asLong() >= 1,
            "pm.test('paged') — totalElements must be at least 1. Body: " + body);

        JsonNode content = parsed.path("content");
        assertTrue(content.isArray(),
            "The paginated envelope must carry a 'content' array. Body: " + body);
        assertTrue(content.size() <= 20,
            "pm.test('content.length<=20') — a page of size 20 must not return more than 20 items, "
                + "got " + content.size() + ".");
    }
}
