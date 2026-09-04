package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * AUTH-019 and AUTH-020 — PATCH /api/users/profile must persist every field it accepts, and must
 * reject a blank {@code fullName} without partially writing anything.
 *
 * <p><b>Harness.</b> {@code @SpringBootTest @AutoConfigureMockMvc @Transactional} against the real
 * MySQL database with tokens minted by the app's own {@link JwtTokenProvider} — the
 * {@code BillDisputeAmendmentIntegrationTest} convention. MockMvc replaces the sheet's REST
 * Assured, which is not a dependency of this module.</p>
 *
 * <p><b>Deviation on the read-back.</b> AUTH-019 step 3 says to confirm persistence with a fresh
 * {@code GET /api/users/me}. No such endpoint exists — {@code UserController} exposes
 * {@code GET /api/users} and {@code GET /api/users/{id}}, both restricted to
 * SUPER_ADMIN/ADMIN/TEAM_LEAD, so the TECHNICIAN making the PATCH cannot read itself back over
 * HTTP at all. The row's actual intent ("genuine persistence, not an echo of the request") is
 * preserved by flushing and detaching the persistence context and re-loading the row from the
 * database, which cannot be satisfied by a response echo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserProfileIntegrationTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private ObjectMapper     json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    /** Persist a TECHNICIAN with a known baseline profile. */
    private User newTechnician() {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername("profiletest_" + n);
        u.setPasswordHash("x");
        u.setFirstName("Base");
        u.setLastName("Line");
        u.setFullName("Baseline Name");
        u.setEmail("baseline" + n + "@slt.lk");
        u.setRole(User.Role.TECHNICIAN);
        u.setIsActive(true);
        u.setPreferredLanguage(User.Language.ENGLISH);
        u.setNotifyPromotions(false);
        return userRepo.save(u);
    }

    private String bearer(User u) {
        return "Bearer " + jwt.createAccessToken(
            u.getId(), u.getUsername(), u.getRole().name(), u.getOpmcId());
    }

    private void flushAndClear() {
        userRepo.flush();
        em.clear();
    }

    private MvcResult patchProfile(User caller, Object body) throws Exception {
        return mvc.perform(patch("/api/users/profile")
                .header("Authorization", bearer(caller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
            .andReturn();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTH-019 — all four fields survive a round trip to the database
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void updateProfile_allFields_persistsCorrectly() throws Exception {
        User tech = newTechnician();
        Long id = tech.getId();
        flushAndClear();

        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("statusUpdates",      false);
        prefs.put("technicianAssigned", false);
        prefs.put("jobCompleted",       true);
        prefs.put("billing",            false);
        prefs.put("promotions",         true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName",                "New Name");
        body.put("email",                   "x@y.com");
        body.put("language",                "TAMIL");
        body.put("notificationPreferences", prefs);

        // ── Steps 1 & 2 ────────────────────────────────────────────────────────────────────
        MvcResult res = patchProfile(tech, body);
        assertEquals(200, res.getResponse().getStatus(),
            "A valid profile update must return 200. Body: "
                + res.getResponse().getContentAsString());

        // ── Step 3: re-read from the DB, not from the response ────────────────────────────
        flushAndClear();
        User reloaded = userRepo.findById(id).orElseThrow(
            () -> new AssertionError("The user row disappeared after the update"));

        // ── Step 4: all four fields match exactly what was sent ───────────────────────────
        assertEquals("New Name", reloaded.getFullName(),
            "fullName must be persisted");
        assertEquals("x@y.com", reloaded.getEmail(),
            "email must be persisted");
        assertEquals(User.Language.TAMIL, reloaded.getPreferredLanguage(),
            "language must be persisted as the TAMIL enum value");

        assertFalse(reloaded.getNotifyStatusUpdates(),
            "notificationPreferences.statusUpdates must be persisted");
        assertFalse(reloaded.getNotifyTechnicianAssigned(),
            "notificationPreferences.technicianAssigned must be persisted");
        assertTrue(reloaded.getNotifyJobCompleted(),
            "notificationPreferences.jobCompleted must be persisted");
        assertFalse(reloaded.getNotifyBilling(),
            "notificationPreferences.billing must be persisted");
        assertTrue(reloaded.getNotifyPromotions(),
            "notificationPreferences.promotions must be persisted — this one flipped from its "
                + "false default, so an unchanged value here would mean the block was ignored");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTH-020 — a blank fullName is rejected and nothing is written
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void updateProfile_blankName_returns400() throws Exception {
        User tech = newTechnician();
        Long   id            = tech.getId();
        String baselineName  = tech.getFullName();
        String baselineEmail = tech.getEmail();
        flushAndClear();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "");
        // A legitimate-looking change alongside the invalid one: if validation were skipped, this
        // would land in the DB and the "no partial write" assertion below would catch it.
        body.put("email", "should-not-be-written@slt.lk");

        // ── Steps 1 & 2 ────────────────────────────────────────────────────────────────────
        MvcResult res = patchProfile(tech, body);
        int    status = res.getResponse().getStatus();
        String raw    = res.getResponse().getContentAsString();

        assertEquals(400, status,
            "A blank fullName must be rejected with 400 Bad Request, got " + status
                + ". Body: " + raw);

        // ── Step 3: the error names the offending field ───────────────────────────────────
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = json.readValue(raw, Map.class);
        assertEquals("Validation Failed", payload.get("error"),
            "The error must be reported as a validation failure. Body: " + raw);

        Object fields = payload.get("fields");
        assertNotNull(fields, "The 400 body must carry a 'fields' map. Body: " + raw);
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldMap = (Map<String, Object>) fields;
        assertNotNull(fieldMap.get("fullName"),
            "The validation error must reference the fullName field. Body: " + raw);
        assertEquals("Full name is required", fieldMap.get("fullName"),
            "The message must be the DTO's @NotBlank message. Body: " + raw);

        // ── Step 4: no partial write ──────────────────────────────────────────────────────
        flushAndClear();
        User reloaded = userRepo.findById(id).orElseThrow();
        assertEquals(baselineName, reloaded.getFullName(),
            "fullName must be untouched after a rejected update");
        assertEquals(baselineEmail, reloaded.getEmail(),
            "email must be untouched — validation runs before the service, so nothing at all "
                + "from the rejected body may reach the database");
    }
}
