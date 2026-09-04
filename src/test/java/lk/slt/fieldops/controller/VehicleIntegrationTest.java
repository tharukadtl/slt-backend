package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.Vehicle;
import lk.slt.fieldops.entity.VehicleAssignment;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.VehicleAssignmentRepository;
import lk.slt.fieldops.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * RES-006 and RES-014 (05_RESOURCE_MGMT, FR-14) — assigning a vehicle to a technician, and
 * unassigning it back into the available pool.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module and adding a test framework is a project decision, not this suite's. MockMvc through the
 * real filter chain is the module's established convention (see {@link JobIntegrationTest},
 * {@link BillDisputeAmendmentIntegrationTest}) and drives the identical path: real JWT filter, real
 * {@code @PreAuthorize}, real service, real MySQL. Both tests are {@code @Transactional} so their
 * rows roll back.</p>
 *
 * <p><b>Endpoints and field names vs. the sheet.</b> The sheet calls
 * {@code POST /api/vehicles/{id}/assign} and {@code POST /api/vehicles/{id}/unassign}. Neither
 * exists. The implemented action is a single {@code PATCH /api/vehicles/{id}/assign-technician},
 * where a {@code technicianId} assigns and a null {@code technicianId} unassigns
 * ({@code VehicleController} / {@code VehicleService.assignTechnician}). The sheet's
 * {@code assignedToId} is {@code assignedTechnicianId} on the real {@code Vehicle} entity. Both
 * tests drive the real endpoint and assert the real field names — the deviation is documented here
 * rather than failed on.</p>
 *
 * <p><b>What these rows are really probing, and where it is expected to be red.</b>
 * {@code assignTechnician} writes {@code vehicles.assigned_technician_id} and nothing else: it never
 * moves {@code vehicles.status} to {@code IN_USE} on assign or back to {@code AVAILABLE} on
 * unassign, and it never writes a {@code vehicle_assignments} row. Separately,
 * {@code GET /api/vehicles} declares only {@code opmcId} and {@code activeOnly} parameters, so a
 * {@code ?status=AVAILABLE} filter is silently ignored and the whole fleet comes back with a 200.
 * Those sub-checks are written to state the expectation each row sets; they deliberately do not
 * assert the absence of the behaviour, which would lock the gap in as correct.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VehicleIntegrationTest {

    @Autowired private MockMvc                     mvc;
    @Autowired private JwtTokenProvider            jwt;
    @Autowired private UserRepository              userRepo;
    @Autowired private VehicleRepository           vehicleRepo;
    @Autowired private VehicleAssignmentRepository assignmentRepo;
    @Autowired private ObjectMapper                json;
    @Autowired private jakarta.persistence.EntityManager em;

    /** Existing row every branch-scoped FK in this schema resolves against. */
    private static final Long REAL_BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_BRANCH_ID);
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
        u.setOpmcId(REAL_BRANCH_ID);
        return userRepo.save(u);
    }

    private Vehicle newVehicle(Vehicle.VehicleStatus status) {
        long n = uniq();
        Vehicle v = new Vehicle();
        v.setRegistrationNumber("WP T-" + (n % 100000));
        v.setMake("Toyota");
        v.setModel("HiAce");
        v.setModelYear(2020);
        v.setVehicleType(Vehicle.VehicleType.VAN);
        v.setFuelType(Vehicle.FuelType.DIESEL);
        v.setOpmcId(REAL_BRANCH_ID);
        v.setStatus(status);
        v.setCurrentOdometer(15200);
        return vehicleRepo.save(v);
    }

    private MvcResult assignTechnician(Long adminId, Long vehicleId, Long technicianId)
            throws Exception {
        Map<String, Object> body = Collections.singletonMap("technicianId", technicianId);
        return mvc.perform(patch("/api/vehicles/" + vehicleId + "/assign-technician")
                .header("Authorization", bearer(adminId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
            .andReturn();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-006 — assign vehicle to technician
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void assignVehicle_returns200InUse() throws Exception {
        // ── Arrange: an admin, a technician, an AVAILABLE vehicle ────────────────────────────
        User admin = newUser(User.Role.ADMIN, "Admin Amelia");
        User tech  = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        Vehicle vehicle = newVehicle(Vehicle.VehicleStatus.AVAILABLE);
        vehicleRepo.flush();
        userRepo.flush();

        // ── Act: step 1 ──────────────────────────────────────────────────────────────────────
        MvcResult res = assignTechnician(admin.getId(), vehicle.getId(), tech.getId());
        String body = res.getResponse().getContentAsString();

        // ── Step 2 ───────────────────────────────────────────────────────────────────────────
        assertEquals(200, res.getResponse().getStatus(),
            "Assigning a vehicle must return 200. Body: " + body);

        JsonNode assigned = json.readTree(body);
        em.flush();
        em.clear();
        Vehicle reloaded = vehicleRepo.findById(vehicle.getId()).orElseThrow();

        assertAll("assigning a vehicle books it out to the technician",

            // ── Step 3: the technician is recorded on the vehicle ───────────────────────────
            () -> assertEquals(tech.getId().longValue(),
                assigned.path("assignedTechnicianId").asLong(),
                "The response must show the vehicle assigned to technician " + tech.getId()
                    + ", body: " + body),
            () -> assertEquals(tech.getFullName(),
                assigned.path("assignedTechnicianName").asText(),
                "The assignee's name must be denormalised onto the vehicle for list display"),
            () -> assertEquals(tech.getId(), reloaded.getAssignedTechnicianId(),
                "The assignment must be persisted, not just echoed"),

            // ── Step 4: the vehicle is no longer in the available pool ──────────────────────
            () -> assertEquals(Vehicle.VehicleStatus.IN_USE, reloaded.getStatus(),
                "A vehicle handed to a technician must leave the AVAILABLE pool. "
                    + "VehicleService.assignTechnician writes assigned_technician_id only and "
                    + "never touches vehicles.status, so the same vehicle stays advertised as "
                    + "AVAILABLE and can be handed to a second person (see RES-007). Status was: "
                    + reloaded.getStatus()),

            // ── Step 5: the assignment is recorded in history ───────────────────────────────
            () -> {
                List<VehicleAssignment> history =
                    assignmentRepo.findByVehicleIdOrderByAssignmentDateDesc(vehicle.getId());
                assertFalse(history.isEmpty(),
                    "Assigning a vehicle must leave a vehicle_assignments row so mileage and "
                        + "custody are auditable. VehicleService.assignVehicle (which does write "
                        + "one) has no caller anywhere in the codebase — JobService.performBod "
                        + "stores the vehicle id on day_sessions instead and never calls it — so "
                        + "vehicle_assignments is never written by any code path.");
                assertEquals(vehicle.getId(), history.get(0).getVehicleId());
            }
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-014 — vehicle unassign returns to available pool
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void unassignVehicle_statusAvailable() throws Exception {
        // ── Arrange: a vehicle genuinely booked out — assigned AND marked IN_USE ─────────────
        User admin = newUser(User.Role.ADMIN, "Admin Amelia");
        User tech  = newUser(User.Role.TECHNICIAN, "Tech Nimal");
        Vehicle vehicle   = newVehicle(Vehicle.VehicleStatus.AVAILABLE);
        Vehicle inRepair  = newVehicle(Vehicle.VehicleStatus.UNDER_REPAIR);
        vehicleRepo.flush();
        userRepo.flush();

        assertEquals(200, assignTechnician(admin.getId(), vehicle.getId(), tech.getId())
            .getResponse().getStatus(), "Setup: the vehicle must assign first");

        MvcResult statusRes = mvc.perform(patch("/api/vehicles/" + vehicle.getId() + "/status")
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_USE\"}"))
            .andReturn();
        assertEquals(200, statusRes.getResponse().getStatus(),
            "Setup: marking the vehicle IN_USE must succeed. Body: "
                + statusRes.getResponse().getContentAsString());

        em.flush();
        em.clear();

        // ── Act: step 1 — unassign ───────────────────────────────────────────────────────────
        MvcResult res = assignTechnician(admin.getId(), vehicle.getId(), null);
        String body = res.getResponse().getContentAsString();

        // ── Step 2 ───────────────────────────────────────────────────────────────────────────
        assertEquals(200, res.getResponse().getStatus(),
            "Unassigning a vehicle must return 200. Body: " + body);

        JsonNode freed = json.readTree(body);
        em.flush();
        em.clear();
        Vehicle reloaded = vehicleRepo.findById(vehicle.getId()).orElseThrow();

        assertAll("unassigning a vehicle puts it back in the available pool",

            // ── Step 3: no technician holds it any more ─────────────────────────────────────
            () -> assertTrue(freed.path("assignedTechnicianId").isNull(),
                "The response must show no technician assigned, body: " + body),
            () -> assertNull(reloaded.getAssignedTechnicianId(),
                "The unassignment must be persisted, not just echoed"),

            // ── Step 4: it is AVAILABLE again ───────────────────────────────────────────────
            () -> assertEquals(Vehicle.VehicleStatus.AVAILABLE, reloaded.getStatus(),
                "Handing a vehicle back must return it to the AVAILABLE pool. "
                    + "VehicleService.assignTechnician clears assigned_technician_id only and "
                    + "never touches vehicles.status, so a returned vehicle stays IN_USE forever "
                    + "unless an admin separately remembers to PATCH its status. Status was: "
                    + reloaded.getStatus()),

            // ── Step 5: and it shows up in the available list, which excludes the others ────
            () -> {
                MvcResult listRes = mvc.perform(get("/api/vehicles?status=AVAILABLE")
                        .header("Authorization", bearer(admin.getId(), "ADMIN")))
                    .andReturn();
                assertEquals(200, listRes.getResponse().getStatus(),
                    "Listing available vehicles must return 200");

                JsonNode list = json.readTree(listRes.getResponse().getContentAsString());

                boolean containsFreed = false;
                boolean containsRepair = false;
                for (JsonNode v : list) {
                    if (v.path("id").asLong() == vehicle.getId())  containsFreed  = true;
                    if (v.path("id").asLong() == inRepair.getId()) containsRepair = true;
                }

                assertTrue(containsFreed,
                    "The freed vehicle must appear in the AVAILABLE list");
                assertFalse(containsRepair,
                    "?status=AVAILABLE must exclude the UNDER_REPAIR vehicle. "
                        + "GET /api/vehicles declares only opmcId and activeOnly, so an unknown "
                        + "status parameter is silently ignored and the whole fleet is returned "
                        + "with a 200 — a caller filtering by status gets a wrong answer with no "
                        + "error.");
            }
        );
    }
}
