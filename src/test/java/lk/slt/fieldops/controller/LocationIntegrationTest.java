package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.LocationUpdateRequest;
import lk.slt.fieldops.entity.TechnicianLocation;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.TechnicianLocationRepository;
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
 * FAULT-012 (02_FAULT_TRACKING, FR-6) — a technician's GPS position is stored, retrievable, and
 * upserted rather than accumulated.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured, which is not a dependency of this
 * module; MockMvc through the real filter chain is the established convention here
 * ({@link BillDisputeAmendmentIntegrationTest}) and exercises the same controller, service,
 * {@code @PreAuthorize} rules and MySQL rows.</p>
 *
 * <p><b>Endpoint reality.</b> The row names {@code POST /api/locations} and
 * {@code GET /api/technicians/5/location}. The real endpoints ({@link LocationController}) are
 * {@code POST /api/location/update} and {@code GET /api/location/technician/{id}}. The GET is not
 * self-service — {@code LocationService.assertCanViewTechnicianLocation} admits ADMIN /
 * SUPER_ADMIN, the technician's own TEAM_LEAD for today's session, or a CLIENT with an active job
 * — so the read below is made as an ADMIN.</p>
 *
 * <p><b>What the upsert sub-check measures.</b> {@code LocationService.updateLocation} does not
 * update in place: it flips every prior row for the user to {@code is_active = false} and inserts
 * a new one. So there is exactly one <i>active</i> row per technician (which is what every read
 * path resolves), but the total row count grows with each ping — the row's literal wording,
 * "UPSERT: only 1 row per tech", is asserted separately from the active-row invariant so the two
 * results are distinguishable.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LocationIntegrationTest {

    @Autowired private MockMvc                      mvc;
    @Autowired private JwtTokenProvider             jwt;
    @Autowired private TechnicianLocationRepository locationRepo;
    @Autowired private UserRepository               userRepo;
    @Autowired private ObjectMapper                 json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_BRANCH_ID = 1L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_BRANCH_ID);
    }

    /** technician_locations.user_id is an FK to users, so the technician must really exist. */
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
        return userRepo.save(u);
    }

    private LocationUpdateRequest at(double latitude, double longitude, String status) {
        return LocationUpdateRequest.builder()
            .latitude(latitude)
            .longitude(longitude)
            .address("Colombo 03")
            .status(status)
            .build();
    }

    private long rowsFor(Long techId) {
        em.flush();
        return em.createQuery(
                "SELECT COUNT(t) FROM TechnicianLocation t WHERE t.user.id = :uid", Long.class)
            .setParameter("uid", techId)
            .getSingleResult();
    }

    private long activeRowsFor(Long techId) {
        em.flush();
        return em.createQuery(
                "SELECT COUNT(t) FROM TechnicianLocation t WHERE t.user.id = :uid "
                    + "AND t.isActive = true", Long.class)
            .setParameter("uid", techId)
            .getSingleResult();
    }

    @Test
    void updateLocation_upsertPerTech() throws Exception {
        User tech  = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        User admin = newUser(User.Role.ADMIN,      "Ops Admin");
        Long techId = tech.getId();

        // ── Steps 1-2: the technician posts a GPS fix ────────────────────────────────────
        MvcResult first = mvc.perform(post("/api/location/update")
                .header("Authorization", bearer(techId, "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(at(6.9271, 79.8612, "TRAVELLING"))))
            .andReturn();

        String firstBody = first.getResponse().getContentAsString();
        int firstStatus = first.getResponse().getStatus();
        assertTrue(firstStatus == 200 || firstStatus == 201,
            "A location update must be accepted with 200 or 201, got " + firstStatus
                + ". Body: " + firstBody);

        // ── Steps 3-4: it is retrievable, with the values that were sent ────────────────
        MvcResult read = mvc.perform(get("/api/location/technician/{id}", techId)
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();

        String readBody = read.getResponse().getContentAsString();
        assertEquals(200, read.getResponse().getStatus(), "Body: " + readBody);

        JsonNode location = json.readTree(readBody);
        assertEquals(6.9271, location.get("latitude").asDouble(), 0.00001);
        assertEquals(79.8612, location.get("longitude").asDouble(), 0.00001);
        assertEquals("TRAVELLING", location.get("technicianStatus").asText(),
            "The status sent with the ping must be stored and returned. Body: " + readBody);
        assertEquals(techId, location.get("userId").asLong());

        // ── Step 5: a second ping from the same technician ──────────────────────────────
        MvcResult second = mvc.perform(post("/api/location/update")
                .header("Authorization", bearer(techId, "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(at(6.9500, 79.8700, "ON_JOB"))))
            .andReturn();
        assertEquals(200, second.getResponse().getStatus(),
            "Body: " + second.getResponse().getContentAsString());

        // The read path must now resolve to the NEW position, not the old one.
        MvcResult reread = mvc.perform(get("/api/location/technician/{id}", techId)
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        String rereadBody = reread.getResponse().getContentAsString();
        assertEquals(200, reread.getResponse().getStatus(), "Body: " + rereadBody);
        JsonNode updated = json.readTree(rereadBody);
        assertEquals(6.9500, updated.get("latitude").asDouble(), 0.00001,
            "The second ping must supersede the first. Body: " + rereadBody);
        assertEquals("ON_JOB", updated.get("technicianStatus").asText());

        // ── Step 6: upsert — one row per technician ─────────────────────────────────────
        long activeRows = activeRowsFor(techId);
        long totalRows  = rowsFor(techId);

        assertAll("one location row per technician",
            () -> assertEquals(1, activeRows,
                "Exactly one ACTIVE location row per technician is the invariant every read path "
                    + "relies on (findActiveByUserId), was " + activeRows),
            () -> assertEquals(1, totalRows,
                "The row specifies an UPSERT — only 1 row per technician. "
                    + "LocationService.updateLocation instead deactivates prior rows and INSERTs a "
                    + "new one, so the table grows by one row per GPS ping. Rows after 2 pings: "
                    + totalRows)
        );

        // Sanity: the surviving active row is the newer position.
        TechnicianLocation active = locationRepo.findActiveByUserId(techId).orElseThrow();
        assertEquals(6.9500, active.getLatitude(), 0.00001);
        assertEquals(TechnicianLocation.TechnicianStatus.ON_JOB, active.getTechnicianStatus());
    }
}
