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
 * QA_Compliance_Consolidated_Report.md Stage G Minor — {@code GET /api/attendance/today/{userId}}
 * (`AttendanceController.java:43-45`) had only a role-level {@code @PreAuthorize} and no check that
 * the requested {@code userId} belonged to the caller (or was otherwise within the caller's scope) —
 * any TECHNICIAN could read any other TECHNICIAN's daily attendance summary.
 *
 * <p><b>Boundary confirmed against the SRS before implementing, per instruction, not assumed:</b>
 * 5.4.3 (FR-16) scopes Team Lead oversight to "all **team members**" — Work Group, per 5.5.0's
 * hierarchy ("Work Group: a team consisting of exactly one Team Lead and one or more Technicians,
 * belonging to one OPMC"), narrower than the whole OPMC; 5.5.0 states Super Admin is unscoped and
 * OPMC-level Admin is scoped to their own OPMC. So: anyone may read their own summary; TECHNICIAN
 * may read no one else's; TEAM_LEAD may read their own Work Group's technicians only (not the whole
 * OPMC); ADMIN may read anyone in their own OPMC (the same {@link lk.slt.fieldops.shared.OpmcAccessGuard}
 * boundary used everywhere else in this codebase); SUPER_ADMIN is unscoped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AttendanceOwnershipTest {

    @Autowired private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired private JwtTokenProvider    jwt;
    @Autowired private UserRepository      userRepo;
    @Autowired private WorkGroupRepository workGroupRepo;
    @Autowired private OpmcRepository      opmcRepo;

    private static final Long REAL_OPMC_ID = 1L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_OPMC_ID);
    }

    private User newUser(User.Role role, Long opmcId, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("ato" + n);
        u.setPasswordHash("x");
        u.setFirstName("Ato");
        u.setLastName(role.name());
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    /**
     * Mirrors what a real Admin does: {@code WorkGroupService} only sets {@code WorkGroup.teamLead}
     * (confirmed by reading it) — a Team Lead's own {@code users.workgroup_id} is a separate field,
     * independently set via {@code UserService.createUser}/{@code updateUser}'s client-supplied
     * {@code workgroupId} (also confirmed by reading it), the same field {@code MaterialRequestService}
     * already reads to resolve a Team Lead's own Work Group. Both sides must be linked here too.
     */
    private WorkGroup newWorkGroup(Long opmcId, User teamLead) {
        WorkGroup wg = new WorkGroup();
        wg.setName("ATO WG " + uniq());
        wg.setOpmc(opmcRepo.findById(opmcId).orElseThrow());
        wg.setTeamLead(teamLead);
        wg.setIsActive(true);
        wg = workGroupRepo.save(wg);
        teamLead.setWorkgroup(wg);
        userRepo.save(teamLead);
        return wg;
    }

    private Opmc newOtherOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("ATO Other OPMC " + n);
        o.setCode("ATO" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private int getStatus(Long targetUserId, Long callerId, String role) throws Exception {
        MvcResult res = mvc.perform(get("/api/attendance/today/" + targetUserId)
                .header("Authorization", bearer(callerId, role)))
            .andReturn();
        return res.getResponse().getStatus();
    }

    @Test
    void anyoneCanReadTheirOwnTodaySummary() throws Exception {
        User tech = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Own Tech");
        assertEquals(200, getStatus(tech.getId(), tech.getId(), "TECHNICIAN"));
    }

    @Test
    void technicianCannotReadAnotherTechniciansTodaySummary() throws Exception {
        User tech  = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Requesting Tech");
        User other = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Other Tech");
        assertEquals(403, getStatus(other.getId(), tech.getId(), "TECHNICIAN"));
    }

    @Test
    void teamLeadCanReadOwnWorkGroupTechniciansTodaySummary() throws Exception {
        User teamLead = newUser(User.Role.TEAM_LEAD, REAL_OPMC_ID, "WG TL");
        WorkGroup wg  = newWorkGroup(REAL_OPMC_ID, teamLead);
        User tech = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "WG Tech");
        tech.setWorkgroup(wg);
        userRepo.save(tech);

        assertEquals(200, getStatus(tech.getId(), teamLead.getId(), "TEAM_LEAD"));
    }

    @Test
    void teamLeadCannotReadAnotherWorkGroupsTechniciansTodaySummary() throws Exception {
        User teamLead  = newUser(User.Role.TEAM_LEAD, REAL_OPMC_ID, "WG TL 2");
        newWorkGroup(REAL_OPMC_ID, teamLead);
        User otherLead = newUser(User.Role.TEAM_LEAD, REAL_OPMC_ID, "Other WG TL");
        WorkGroup otherWg = newWorkGroup(REAL_OPMC_ID, otherLead);
        User foreignTech = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Foreign WG Tech");
        foreignTech.setWorkgroup(otherWg);
        userRepo.save(foreignTech);

        assertEquals(403, getStatus(foreignTech.getId(), teamLead.getId(), "TEAM_LEAD"));
    }

    @Test
    void teamLeadCannotReadTechnicianWithNoWorkGroup() throws Exception {
        User teamLead = newUser(User.Role.TEAM_LEAD, REAL_OPMC_ID, "WG TL 3");
        newWorkGroup(REAL_OPMC_ID, teamLead);
        User unassigned = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Unassigned Tech");

        assertEquals(403, getStatus(unassigned.getId(), teamLead.getId(), "TEAM_LEAD"));
    }

    @Test
    void adminCanReadOwnOpmcTechniciansTodaySummary() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Admin Own");
        User tech  = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Admin's Tech");
        assertEquals(200, getStatus(tech.getId(), admin.getId(), "ADMIN"));
    }

    @Test
    void adminCannotReadAnotherOpmcsTechniciansTodaySummary() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Admin Foreign");
        Opmc other = newOtherOpmc();
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Foreign OPMC Tech");
        assertEquals(403, getStatus(foreignTech.getId(), admin.getId(), "ADMIN"));
    }

    @Test
    void superAdminCanReadAnyTechniciansTodaySummary() throws Exception {
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID, "Super");
        Opmc other = newOtherOpmc();
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Super's Foreign Tech");
        assertEquals(200, getStatus(foreignTech.getId(), superAdmin.getId(), "SUPER_ADMIN"));
    }
}
