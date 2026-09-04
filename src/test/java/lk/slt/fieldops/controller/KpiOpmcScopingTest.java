package lk.slt.fieldops.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Stage F #2 (QA_Compliance_Consolidated_Report.md) — {@code KpiController.getTeamKpi} previously
 * trusted a client-supplied {@code opmcId} with no check against the caller's own OPMC, and
 * {@code getLeaderboard} (backing both {@code GET /api/kpi/leaderboard} and REP-08) ranked
 * technicians across every OPMC regardless of caller. Both now go through
 * {@code OpmcAccessGuard}.
 *
 * <p><b>Stage G Major, resolved 2026-08-29:</b> {@code getTechnicianScore}
 * ({@code GET /api/kpi/score/{userId}}) and {@code getTechnicianTargets}
 * ({@code GET /api/kpi/targets/technician/{id}}) had no {@code OpmcAccessGuard} check at all —
 * missed by the Stage F #2 sweep that fixed the two methods above, in the same file, the same era.
 * Both now resolve the target user's OPMC via {@code UserService.getById} first, then call
 * {@code assertSameOpmc}, matching {@code ResourceAllocationController}/{@code PaymentController}'s
 * own {@code assertSameOpmc(someService.getById(id).getOpmcId(), callerId)} shape.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class KpiOpmcScopingTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private OpmcRepository   opmcRepo;
    @Autowired private ObjectMapper     json;

    private static final Long REAL_OPMC_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_OPMC_ID);
    }

    private User newUser(User.Role role, Long opmcId, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("kos" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private Opmc newOtherOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("KOS Other OPMC " + n);
        o.setCode("KOS" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // GET /api/kpi/team
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void adminCannotReadAnotherOpmcsTeamKpi() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Team Kpi Admin");
        Opmc other = newOtherOpmc();
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/team")
                .param("period", "MONTHLY")
                .param("opmcId", String.valueOf(other.getId()))
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "An Admin requesting another OPMC's team KPI must be rejected. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void adminCanReadOwnOpmcsTeamKpi() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Team Kpi Admin Own");
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/team")
                .param("period", "MONTHLY")
                .param("opmcId", String.valueOf(REAL_OPMC_ID))
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "An Admin requesting their own OPMC's team KPI must succeed. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void superAdminCanReadAnyOpmcsTeamKpi() throws Exception {
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID, "Team Kpi Super");
        Opmc other = newOtherOpmc();
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/team")
                .param("period", "MONTHLY")
                .param("opmcId", String.valueOf(other.getId()))
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN")))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "A Super Admin must retain unscoped access to any OPMC's team KPI. Body: "
                + res.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // GET /api/kpi/leaderboard — all four calling roles
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private List<String> leaderboardNames(MvcResult res) throws Exception {
        JsonNode board = json.readTree(res.getResponse().getContentAsString());
        List<String> names = new ArrayList<>();
        board.forEach(entry -> names.add(entry.path("technicianName").asText()));
        return names;
    }

    @Test
    void leaderboardScopingAcrossAllFourRoles() throws Exception {
        Opmc other = newOtherOpmc();
        User ownTech    = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Leaderboard Own Tech " + uniq());
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Leaderboard Foreign Tech " + uniq());

        User admin      = newUser(User.Role.ADMIN,      REAL_OPMC_ID, "Leaderboard Admin");
        User teamLead    = newUser(User.Role.TEAM_LEAD,  REAL_OPMC_ID, "Leaderboard Team Lead");
        User technician  = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Leaderboard Caller Tech");
        User superAdmin  = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID, "Leaderboard Super Admin");
        userRepo.flush();

        MvcResult adminRes = mvc.perform(get("/api/kpi/leaderboard?period=MONTHLY")
                .header("Authorization", bearer(admin.getId(), "ADMIN"))).andReturn();
        MvcResult teamLeadRes = mvc.perform(get("/api/kpi/leaderboard?period=MONTHLY")
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD"))).andReturn();
        MvcResult technicianRes = mvc.perform(get("/api/kpi/leaderboard?period=MONTHLY")
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN"))).andReturn();
        MvcResult superAdminRes = mvc.perform(get("/api/kpi/leaderboard?period=MONTHLY")
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN"))).andReturn();

        assertAll("all four roles get 200, but scoped differently",
            () -> assertEquals(200, adminRes.getResponse().getStatus()),
            () -> assertEquals(200, teamLeadRes.getResponse().getStatus()),
            () -> assertEquals(200, technicianRes.getResponse().getStatus()),
            () -> assertEquals(200, superAdminRes.getResponse().getStatus()),

            () -> assertTrue(leaderboardNames(adminRes).contains(ownTech.getFullName()),
                "ADMIN must see their own OPMC's technician on the leaderboard"),
            () -> assertFalse(leaderboardNames(adminRes).contains(foreignTech.getFullName()),
                "ADMIN must not see a different OPMC's technician on the leaderboard"),

            () -> assertTrue(leaderboardNames(teamLeadRes).contains(ownTech.getFullName()),
                "TEAM_LEAD must see their own OPMC's technician on the leaderboard"),
            () -> assertFalse(leaderboardNames(teamLeadRes).contains(foreignTech.getFullName()),
                "TEAM_LEAD must not see a different OPMC's technician on the leaderboard"),

            () -> assertTrue(leaderboardNames(technicianRes).contains(ownTech.getFullName()),
                "TECHNICIAN must see their own OPMC's technician on the leaderboard"),
            () -> assertFalse(leaderboardNames(technicianRes).contains(foreignTech.getFullName()),
                "TECHNICIAN must not see a different OPMC's technician on the leaderboard"),

            () -> assertTrue(leaderboardNames(superAdminRes).contains(ownTech.getFullName())
                    && leaderboardNames(superAdminRes).contains(foreignTech.getFullName()),
                "SUPER_ADMIN must retain unscoped access, seeing technicians from every OPMC. Names: "
                    + leaderboardNames(superAdminRes))
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // GET /api/kpi/score/{userId}
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void teamLeadCannotReadAnotherOpmcsTechnicianScore() throws Exception {
        User teamLead = newUser(User.Role.TEAM_LEAD, REAL_OPMC_ID, "Score TL");
        Opmc other = newOtherOpmc();
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Score Foreign Tech");
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/score/" + foreignTech.getId())
                .param("period", "MONTHLY")
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD")))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "A TEAM_LEAD requesting another OPMC's technician score must be rejected. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void adminCannotReadAnotherOpmcsTechnicianScore() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Score Admin");
        Opmc other = newOtherOpmc();
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Score Foreign Tech2");
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/score/" + foreignTech.getId())
                .param("period", "MONTHLY")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "An ADMIN requesting another OPMC's technician score must be rejected. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void teamLeadCanReadOwnOpmcsTechnicianScore() throws Exception {
        User teamLead = newUser(User.Role.TEAM_LEAD, REAL_OPMC_ID, "Score TL Own");
        User ownTech = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Score Own Tech");
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/score/" + ownTech.getId())
                .param("period", "MONTHLY")
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD")))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "A TEAM_LEAD requesting their own OPMC's technician score must succeed. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void superAdminCanReadAnyOpmcsTechnicianScore() throws Exception {
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID, "Score Super");
        Opmc other = newOtherOpmc();
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Score Foreign Tech3");
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/score/" + foreignTech.getId())
                .param("period", "MONTHLY")
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN")))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "A SUPER_ADMIN must retain unscoped access to any OPMC's technician score. Body: "
                + res.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // GET /api/kpi/targets/technician/{id}
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void teamLeadCannotReadAnotherOpmcsTechnicianTargets() throws Exception {
        User teamLead = newUser(User.Role.TEAM_LEAD, REAL_OPMC_ID, "Targets TL");
        Opmc other = newOtherOpmc();
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Targets Foreign Tech");
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/targets/technician/" + foreignTech.getId())
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD")))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "A TEAM_LEAD requesting another OPMC's technician targets must be rejected. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void adminCannotReadAnotherOpmcsTechnicianTargets() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Targets Admin");
        Opmc other = newOtherOpmc();
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Targets Foreign Tech2");
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/targets/technician/" + foreignTech.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "An ADMIN requesting another OPMC's technician targets must be rejected. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void teamLeadCanReadOwnOpmcsTechnicianTargets() throws Exception {
        User teamLead = newUser(User.Role.TEAM_LEAD, REAL_OPMC_ID, "Targets TL Own");
        User ownTech = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Targets Own Tech");
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/targets/technician/" + ownTech.getId())
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD")))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "A TEAM_LEAD requesting their own OPMC's technician targets must succeed. Body: "
                + res.getResponse().getContentAsString());
    }

    @Test
    void superAdminCanReadAnyOpmcsTechnicianTargets() throws Exception {
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID, "Targets Super");
        Opmc other = newOtherOpmc();
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Targets Foreign Tech3");
        userRepo.flush();

        MvcResult res = mvc.perform(get("/api/kpi/targets/technician/" + foreignTech.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN")))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "A SUPER_ADMIN must retain unscoped access to any OPMC's technician targets. Body: "
                + res.getResponse().getContentAsString());
    }
}
