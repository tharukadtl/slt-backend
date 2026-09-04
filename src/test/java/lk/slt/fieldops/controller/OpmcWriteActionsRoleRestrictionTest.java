package lk.slt.fieldops.controller;

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

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA_Compliance_Consolidated_Report.md Major finding — {@code OpmcController}'s lifecycle-management
 * write actions (create/update/activate/deactivate) were reachable by a plain ADMIN via
 * {@code @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")}. Per the confirmed decision, OPMC
 * lifecycle management is now SUPER_ADMIN only; the annotation on those four methods was narrowed to
 * {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}. The read endpoints (getById/getAll/getActive/search)
 * were intentionally left untouched — this decision was about lifecycle management, not visibility.
 *
 * <p><b>2026-08-21 follow-up (Critical, raw-capture §A item 1):</b> {@code DELETE /api/opmcs/{id}} —
 * a fifth write action, internally calling the same {@code deactivate} service method as
 * {@code PATCH /{id}/deactivate} above — was left at {@code hasAnyRole('SUPER_ADMIN','ADMIN')} when
 * the original fix above was made, since it fell outside the four actions that fix's decision named.
 * Independently re-discovered during a later three-module audit and now closed the same way, same
 * reasoning: narrowed to {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}, matching its four siblings
 * exactly. See {@link #adminIsForbiddenOnDelete()} / {@link #superAdminCanDelete()} below.</p>
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain, matching
 * {@code ResourceAllocationOpmcScopingTest}'s convention: real JWT filter, real {@code @PreAuthorize},
 * real MySQL, {@code @Transactional} rollback. {@code SecurityConfig}'s {@code /api/opmcs/**} URL
 * matcher still admits both SUPER_ADMIN and ADMIN, so any 403 seen here is genuinely coming from the
 * narrowed {@code @PreAuthorize}, not the filter-chain matcher.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpmcWriteActionsRoleRestrictionTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository userRepo;
    @Autowired private OpmcRepository opmcRepo;

    private static final Long REAL_OPMC_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "owar" + userId, role, REAL_OPMC_ID);
    }

    private User newUser(User.Role role) {
        long n = uniq();
        User u = new User();
        u.setUsername("owar" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_OPMC_ID);
        return userRepo.save(u);
    }

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("OWAR Test OPMC " + n);
        o.setCode("OW" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private String createBody(long n) {
        return "{\"name\":\"OWAR Create " + n + "\",\"code\":\"OC" + n
                + "\",\"address\":\"1 Test Road\"}";
    }

    private String updateBody(long n) {
        return "{\"name\":\"OWAR Updated " + n + "\",\"address\":\"2 Test Road\"}";
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // A plain ADMIN is now rejected on all four write actions
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void adminIsForbiddenOnAllFourWriteActions() throws Exception {
        User admin = newUser(User.Role.ADMIN);
        Opmc target = newOpmc();
        userRepo.flush();
        opmcRepo.flush();

        String adminBearer = bearer(admin.getId(), "ADMIN");
        long n = uniq();

        MvcResult create = mvc.perform(post("/api/opmcs")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(n)))
            .andReturn();
        assertEquals(403, create.getResponse().getStatus(),
            "An ADMIN must be Forbidden from creating an OPMC. Body: "
                + create.getResponse().getContentAsString());

        MvcResult update = mvc.perform(put("/api/opmcs/{id}", target.getId())
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(n)))
            .andReturn();
        assertEquals(403, update.getResponse().getStatus(),
            "An ADMIN must be Forbidden from updating an OPMC. Body: "
                + update.getResponse().getContentAsString());

        MvcResult activate = mvc.perform(patch("/api/opmcs/{id}/activate", target.getId())
                .header("Authorization", adminBearer))
            .andReturn();
        assertEquals(403, activate.getResponse().getStatus(),
            "An ADMIN must be Forbidden from activating an OPMC. Body: "
                + activate.getResponse().getContentAsString());

        MvcResult deactivate = mvc.perform(patch("/api/opmcs/{id}/deactivate", target.getId())
                .header("Authorization", adminBearer))
            .andReturn();
        assertEquals(403, deactivate.getResponse().getStatus(),
            "An ADMIN must be Forbidden from deactivating an OPMC. Body: "
                + deactivate.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // A SUPER_ADMIN retains full access to all four write actions
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void superAdminRetainsFullAccessOnAllFourWriteActions() throws Exception {
        User superAdmin = newUser(User.Role.SUPER_ADMIN);
        Opmc target = newOpmc();
        userRepo.flush();
        opmcRepo.flush();

        String superAdminBearer = bearer(superAdmin.getId(), "SUPER_ADMIN");
        long n = uniq();

        MvcResult create = mvc.perform(post("/api/opmcs")
                .header("Authorization", superAdminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(n)))
            .andReturn();
        assertEquals(201, create.getResponse().getStatus(),
            "A SUPER_ADMIN must be able to create an OPMC. Body: "
                + create.getResponse().getContentAsString());

        MvcResult update = mvc.perform(put("/api/opmcs/{id}", target.getId())
                .header("Authorization", superAdminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(n)))
            .andReturn();
        assertEquals(200, update.getResponse().getStatus(),
            "A SUPER_ADMIN must be able to update an OPMC. Body: "
                + update.getResponse().getContentAsString());

        MvcResult activate = mvc.perform(patch("/api/opmcs/{id}/activate", target.getId())
                .header("Authorization", superAdminBearer))
            .andReturn();
        assertEquals(200, activate.getResponse().getStatus(),
            "A SUPER_ADMIN must be able to activate an OPMC. Body: "
                + activate.getResponse().getContentAsString());

        MvcResult deactivate = mvc.perform(patch("/api/opmcs/{id}/deactivate", target.getId())
                .header("Authorization", superAdminBearer))
            .andReturn();
        assertEquals(200, deactivate.getResponse().getStatus(),
            "A SUPER_ADMIN must be able to deactivate an OPMC. Body: "
                + deactivate.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // 2026-08-21 follow-up: DELETE /api/opmcs/{id}, the fifth write action
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void adminIsForbiddenOnDelete() throws Exception {
        User admin = newUser(User.Role.ADMIN);
        Opmc target = newOpmc();
        userRepo.flush();
        opmcRepo.flush();

        MvcResult result = mvc.perform(delete("/api/opmcs/{id}", target.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        assertEquals(403, result.getResponse().getStatus(),
            "An ADMIN must be Forbidden from DELETE /api/opmcs/{id} (deactivates internally, same as "
                + "the four write actions above). Body: " + result.getResponse().getContentAsString());
    }

    @Test
    void superAdminCanDelete() throws Exception {
        User superAdmin = newUser(User.Role.SUPER_ADMIN);
        Opmc target = newOpmc();
        userRepo.flush();
        opmcRepo.flush();

        MvcResult result = mvc.perform(delete("/api/opmcs/{id}", target.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN")))
            .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
            "A SUPER_ADMIN must still be able to DELETE /api/opmcs/{id}. Body: "
                + result.getResponse().getContentAsString());
    }
}
