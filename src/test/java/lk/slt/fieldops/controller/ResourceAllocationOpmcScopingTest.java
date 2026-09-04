package lk.slt.fieldops.controller;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Stage F #2 (QA_Compliance_Consolidated_Report.md) — {@code ResourceAllocationController.getForOpmc}
 * and {@code getForWorkGroup} previously let any ADMIN read any OPMC's/Work Group's allocations by
 * ID, with no boundary check (unlike {@code WorkGroupController.getById}, RES-023). Both now reuse
 * {@code OpmcAccessGuard.assertSameOpmc}.
 *
 * <p><b>Tool/placement.</b> MockMvc through the real filter chain, matching
 * {@code UserServiceAccessControlTest}'s convention for this same fix family: real JWT filter, real
 * {@code @PreAuthorize}, real MySQL, {@code @Transactional} rollback.</p>
 *
 * <p><b>2026-09-03, CI-portable-database fix.</b> Every test previously assumed OPMC id=1 already
 * existed ({@code REAL_OPMC_ID = 1L}, dereferenced via {@code opmcRepo.findById(REAL_OPMC_ID)
 * .orElseThrow()} in {@code adminCanReadOwnOpmcWorkGroupAllocations}, and used as the "own OPMC" path
 * parameter everywhere else) — a real row that only ever existed because this suite ran against the
 * same long-lived local dev database all session. Fixed by creating a real, per-test {@code Opmc} and
 * using its id everywhere "own OPMC" is needed, matching the established {@code newOpmc()} pattern
 * used correctly in ~40 sibling files.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ResourceAllocationOpmcScopingTest {

    @Autowired private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository userRepo;
    @Autowired private OpmcRepository opmcRepo;
    @Autowired private WorkGroupRepository workGroupRepo;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, opmcId);
    }

    /** A fresh, genuinely persisted OPMC — no test may assume any OPMC id pre-exists. */
    private Opmc newOwnOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("RAO Own OPMC " + n);
        o.setCode("RAOO" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private User newUser(User.Role role, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("rao" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private Opmc newOtherOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("RAO Other OPMC " + n);
        o.setCode("RAO" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private WorkGroup newWorkGroup(Opmc opmc) {
        long n = uniq();
        WorkGroup wg = new WorkGroup();
        wg.setName("RAO Test WG " + n);
        wg.setOpmc(opmc);
        wg.setIsActive(true);
        return workGroupRepo.save(wg);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // GET /api/resource-allocations/opmc/{id}
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void adminCannotReadAnotherOpmcsAllocations() throws Exception {
        Opmc own = newOwnOpmc();
        User admin = newUser(User.Role.ADMIN, own.getId());
        Opmc other = newOtherOpmc();
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/resource-allocations/opmc/" + other.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", own.getId())))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "An Admin reading another OPMC's resource allocations must be rejected. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void adminCanReadOwnOpmcsAllocations() throws Exception {
        Opmc own = newOwnOpmc();
        User admin = newUser(User.Role.ADMIN, own.getId());
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/resource-allocations/opmc/" + own.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", own.getId())))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "An Admin reading their own OPMC's resource allocations must succeed. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void superAdminCanReadAnyOpmcsAllocations() throws Exception {
        Opmc own = newOwnOpmc();
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.getId());
        Opmc other = newOtherOpmc();
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/resource-allocations/opmc/" + other.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN", own.getId())))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "A Super Admin must retain unscoped access to any OPMC's resource allocations. Body: "
                + res.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // GET /api/resource-allocations/work-group/{id}
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void adminCannotReadCrossOpmcWorkGroupAllocations() throws Exception {
        Opmc own = newOwnOpmc();
        User admin = newUser(User.Role.ADMIN, own.getId());
        Opmc other = newOtherOpmc();
        WorkGroup foreignWg = newWorkGroup(other);
        userRepo.flush();
        workGroupRepo.flush();

        MvcResult res = mvc.perform(get("/api/resource-allocations/work-group/" + foreignWg.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", own.getId())))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "An Admin reading a Work Group's allocations in a different OPMC must be rejected. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void adminCanReadOwnOpmcWorkGroupAllocations() throws Exception {
        Opmc own = newOwnOpmc();
        User admin = newUser(User.Role.ADMIN, own.getId());
        WorkGroup ownWg = newWorkGroup(own);
        userRepo.flush();
        workGroupRepo.flush();

        MvcResult res = mvc.perform(get("/api/resource-allocations/work-group/" + ownWg.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", own.getId())))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "An Admin reading their own OPMC's Work Group allocations must succeed. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void superAdminCanReadCrossOpmcWorkGroupAllocations() throws Exception {
        Opmc own = newOwnOpmc();
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.getId());
        Opmc other = newOtherOpmc();
        WorkGroup foreignWg = newWorkGroup(other);
        userRepo.flush();
        workGroupRepo.flush();

        MvcResult res = mvc.perform(get("/api/resource-allocations/work-group/" + foreignWg.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN", own.getId())))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "A Super Admin must retain unscoped access to any OPMC's Work Group allocations. Body: "
                + res.getResponse().getContentAsString());
    }
}
