package lk.slt.fieldops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.OpmcRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Two access-control gaps closed in {@code UserService}:
 *
 * <p><b>1. Symmetric role-change guard.</b> {@code assertCanGrantRole} previously only checked the
 * *target* role, so an ADMIN could grant into ADMIN/SUPER_ADMIN and — because demotion was never
 * checked — also demote a SUPER_ADMIN down to any role, since the current role wasn't examined at
 * all. It now checks both {@code currentRole} and {@code targetRole}: moving a role either into or
 * out of ADMIN/SUPER_ADMIN requires the caller to be SUPER_ADMIN. {@code createUser} passes a null
 * {@code currentRole} since no prior role exists on creation, so it is unaffected.</p>
 *
 * <p><b>2. OPMC boundary on user management.</b> {@code createUser}, {@code updateUser} and
 * {@code deactivateUser} previously let any ADMIN act on a user in any OPMC. They now reuse
 * {@code WorkGroupController.assertSameOpmcUnlessSuperAdmin}'s exact pattern: an ADMIN may only act
 * on users inside their own OPMC; SUPER_ADMIN remains fully unscoped.</p>
 *
 * <p><b>Tool / placement.</b> MockMvc through the real filter chain, matching {@code UserServiceTest}
 * (RES-018) and {@code UserServiceBulkImportTest} (RES-016/017) in this package: real JWT filter, real
 * {@code @PreAuthorize}, real {@code UserService}, real MySQL. {@code @Transactional} rolls back every
 * row this test creates.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserServiceAccessControlTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private OpmcRepository   opmcRepo;
    @Autowired private ObjectMapper     json;

    /** Existing row every OPMC-scoped FK in this schema resolves against. */
    private static final Long REAL_OPMC_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_OPMC_ID);
    }

    private User newUser(User.Role role, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("uac" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    /** A second, real OPMC row distinct from REAL_OPMC_ID, for cross-OPMC boundary tests. */
    private Opmc newOtherOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("UAC Other OPMC " + n);
        o.setCode("UAC" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private String updateRoleBody(String fullName, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        body.put("role", role);
        return json.writeValueAsString(body);
    }

    private String updateNameOnlyBody(String fullName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        return json.writeValueAsString(body);
    }

    private String createBody(String username, String fullName, String role, Long opmcId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", "Passw0rd!");
        body.put("fullName", fullName);
        body.put("email",    username + "@slt.lk");
        body.put("phone",    "07" + (10000000L + (uniq() % 80000000L)));
        body.put("role",     role);
        if (opmcId != null) body.put("opmcId", opmcId);
        return json.writeValueAsString(body);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // 1. Symmetric role-change guard
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void plainAdminCannotDemoteASuperAdmin() throws Exception {
        User admin      = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        userRepo.flush();

        MvcResult res = mvc.perform(put("/api/users/" + superAdmin.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateRoleBody("Demoted Person", "CLIENT")))
            .andReturn();
        String body = res.getResponse().getContentAsString();

        assertEquals(400, res.getResponse().getStatus(),
            "A plain ADMIN demoting a SUPER_ADMIN out of a privileged role must be rejected. "
                + "Body: " + body);
        assertTrue(body.contains("Only a Super Admin"),
            "Rejection must explain the Super-Admin-only rule, body: " + body);

        User reloaded = userRepo.findById(superAdmin.getId()).orElseThrow();
        assertEquals(User.Role.SUPER_ADMIN, reloaded.getRole(),
            "The target's role must be unchanged after a rejected demotion attempt");
    }

    @Test
    void superAdminCanDemoteASuperAdmin() throws Exception {
        User callerSuperAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        User target           = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        userRepo.flush();

        MvcResult res = mvc.perform(put("/api/users/" + target.getId())
                .header("Authorization", bearer(callerSuperAdmin.getId(), "SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateRoleBody("Demoted By Super", "CLIENT")))
            .andReturn();
        String body = res.getResponse().getContentAsString();

        assertEquals(200, res.getResponse().getStatus(),
            "A SUPER_ADMIN must retain full unscoped access to demote another SUPER_ADMIN. "
                + "Body: " + body);

        User reloaded = userRepo.findById(target.getId()).orElseThrow();
        assertEquals(User.Role.CLIENT, reloaded.getRole(),
            "The demotion must actually take effect when performed by a SUPER_ADMIN");
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // 2. OPMC boundary on user management
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void plainAdminCannotCreateUserInAnotherOpmc() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        userRepo.flush();

        String username = "uaccreate" + uniq();
        MvcResult res = mvc.perform(post("/api/users")
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(username, "Cross Opmc Person", "CLIENT", other.getId())))
            .andReturn();
        String body = res.getResponse().getContentAsString();

        assertEquals(403, res.getResponse().getStatus(),
            "A plain ADMIN creating a user in a different OPMC must be rejected. Body: " + body);
        assertFalse(userRepo.findByUsername(username).isPresent(),
            "No user may be created cross-OPMC by a plain ADMIN");
    }

    @Test
    void plainAdminCannotUpdateCrossOpmcUser() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        User target = newUser(User.Role.CLIENT, other.getId());
        userRepo.flush();

        MvcResult res = mvc.perform(put("/api/users/" + target.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateNameOnlyBody("Renamed By Wrong Admin")))
            .andReturn();
        String body = res.getResponse().getContentAsString();

        assertEquals(403, res.getResponse().getStatus(),
            "A plain ADMIN updating a user in a different OPMC must be rejected. Body: " + body);

        User reloaded = userRepo.findById(target.getId()).orElseThrow();
        assertNotEquals("Renamed By Wrong Admin", reloaded.getFullName(),
            "The cross-OPMC target must be unchanged after a rejected update attempt");
    }

    @Test
    void plainAdminCannotDeactivateCrossOpmcUser() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        User target = newUser(User.Role.CLIENT, other.getId());
        userRepo.flush();

        MvcResult res = mvc.perform(delete("/api/users/" + target.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        String body = res.getResponse().getContentAsString();

        assertEquals(403, res.getResponse().getStatus(),
            "A plain ADMIN deactivating a user in a different OPMC must be rejected. Body: " + body);

        User reloaded = userRepo.findById(target.getId()).orElseThrow();
        assertTrue(reloaded.getIsActive(),
            "The cross-OPMC target must remain active after a rejected deactivation attempt");
    }

    @Test
    void superAdminRetainsUnscopedAccessAcrossOpmcs() throws Exception {
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        userRepo.flush();

        // create in another OPMC
        String username = "uacsacreate" + uniq();
        MvcResult createRes = mvc.perform(post("/api/users")
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(username, "Cross Opmc By Super", "CLIENT", other.getId())))
            .andReturn();
        assertEquals(201, createRes.getResponse().getStatus(),
            "A SUPER_ADMIN must be able to create a user in any OPMC. Body: "
                + createRes.getResponse().getContentAsString());
        JsonNode created = json.readTree(createRes.getResponse().getContentAsString());
        Long createdId = created.path("id").asLong();

        // update in another OPMC
        MvcResult updateRes = mvc.perform(put("/api/users/" + createdId)
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateNameOnlyBody("Renamed By Super")))
            .andReturn();
        assertEquals(200, updateRes.getResponse().getStatus(),
            "A SUPER_ADMIN must be able to update a user in any OPMC. Body: "
                + updateRes.getResponse().getContentAsString());
        assertEquals("Renamed By Super",
            userRepo.findById(createdId).orElseThrow().getFullName());

        // deactivate in another OPMC
        MvcResult deactivateRes = mvc.perform(delete("/api/users/" + createdId)
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN")))
            .andReturn();
        assertEquals(200, deactivateRes.getResponse().getStatus(),
            "A SUPER_ADMIN must be able to deactivate a user in any OPMC. Body: "
                + deactivateRes.getResponse().getContentAsString());
        assertFalse(userRepo.findById(createdId).orElseThrow().getIsActive());
    }
}
