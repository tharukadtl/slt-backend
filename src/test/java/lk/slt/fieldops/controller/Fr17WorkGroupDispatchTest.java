package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.DaySession;
import lk.slt.fieldops.entity.DaySessionMember;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.DaySessionMemberRepository;
import lk.slt.fieldops.repository.DaySessionRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * JOB-030 / JOB-031 (03_JOB_LIFECYCLE, FR-17) -- both were logged as "genuinely never-written, no
 * substitute exists anywhere" in the 2026-09-02 completeness recount. Investigated before writing:
 * BOTH described behaviors turned out to be already fully built, not product gaps --
 * {@code JobService.createJob} (:385-392) carries a comment literally reading "JOB-030" naming the
 * exact cross-Work-Group dispatch guard this test proves, and
 * {@code FaultAssignmentDTO.AssignRequest.workGroupId} is {@code @NotNull} (confirmed directly),
 * which is what makes JOB-031's retired old-shape payload fail. Neither had ever actually been
 * exercised by a test -- confirmed by an exhaustive grep across the whole test tree for
 * "does not belong to your Work Group", "selfAssignFault", "targetType" before writing this file.
 *
 * <p><b>Tool substitution.</b> JOB-030's own Automation Mapping cited a mobile Jest component file
 * ({@code TeamLeadQueue.selfAssign.test.tsx}) but every assertion in the row (technicianId on the
 * dispatch result, a 403 on cross-group dispatch) is REST/backend semantics with no mobile UI
 * involved -- repointed to a real MockMvc integration test here, matching this session's established
 * convention of following the real implementation location over a stale citation. JOB-031's Tool
 * column (REST Assured) is honored directly; MockMvc through the real filter chain remains the
 * established substitute for REST Assured throughout this module.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Fr17WorkGroupDispatchTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private OpmcRepository opmcRepo;
    @Autowired private WorkGroupRepository workGroupRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private FaultRepository faultRepo;
    @Autowired private DaySessionRepository sessionRepo;
    @Autowired private DaySessionMemberRepository memberRepo;
    @Autowired private ObjectMapper json;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "wgd" + userId, role, opmcId);
    }

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("WGD OPMC " + n);
        o.setCode("WGD" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private WorkGroup newWorkGroup(Opmc opmc) {
        long n = uniq();
        WorkGroup wg = new WorkGroup();
        wg.setName("WGD Work Group " + n);
        wg.setOpmc(opmc);
        wg.setIsActive(true);
        return workGroupRepo.save(wg);
    }

    private User newUser(User.Role role, Long opmcId, WorkGroup wg) {
        long n = uniq();
        User u = new User();
        u.setUsername("wgd" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        u.setWorkgroup(wg);
        return userRepo.save(u);
    }

    /** An ACTIVE today-session for {@code lead}, with every given technician as an active member. */
    private void activeSessionFor(User lead, User... technicians) {
        DaySession session = new DaySession();
        session.setTeamLeadId(lead.getId());
        session.setSessionDate(LocalDate.now());
        session.setStatus(DaySession.SessionStatus.ACTIVE);
        session.setBodTime(LocalDateTime.now());
        DaySession saved = sessionRepo.save(session);

        for (User tech : technicians) {
            DaySessionMember member = new DaySessionMember();
            member.setSessionId(saved.getId());
            member.setTechnicianId(tech.getId());
            member.setIsActive(true);
            memberRepo.save(member);
        }
        sessionRepo.flush();
        memberRepo.flush();
    }

    private Long faultInWorkGroupQueue(Long opmcId, WorkGroup wg) throws Exception {
        User client = newUser(User.Role.CLIENT, opmcId, null);
        String body = "{"
            + "\"category\":\"BROADBAND\","
            + "\"description\":\"WGD dispatch test fault\","
            + "\"locationAddress\":\"Test address\","
            + "\"latitude\":6.9271,"
            + "\"longitude\":79.8612,"
            + "\"opmcId\":" + opmcId + ","
            + "\"priority\":\"MEDIUM\"}";
        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT", opmcId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus(),
            "Fault setup POST failed. Body: " + res.getResponse().getContentAsString());
        Long faultId = json.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setWorkGroupId(wg.getId());
        fault.setWorkGroupName(wg.getName());
        faultRepo.save(fault);
        faultRepo.flush();
        return faultId;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // JOB-030 — self-assign, in-group dispatch, and cross-group dispatch rejection
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void selfAssign_claimsTheFaultDirectly() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wg = newWorkGroup(opmc);
        User lead = newUser(User.Role.TEAM_LEAD, opmc.getId(), wg);
        Long faultId = faultInWorkGroupQueue(opmc.getId(), wg);

        MvcResult res = mvc.perform(post("/api/faults/{id}/self-assign", faultId)
                .header("Authorization", bearer(lead.getId(), "TEAM_LEAD", opmc.getId())))
            .andReturn();
        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);

        JsonNode dto = json.readTree(body);
        assertEquals(lead.getId(), dto.get("teamLeadId").asLong(),
            "Self-assign must record the calling Team Lead as the assignee");
    }

    @Test
    void dispatchToTechnicianInTheSameWorkGroup_succeeds() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wg = newWorkGroup(opmc);
        User lead = newUser(User.Role.TEAM_LEAD, opmc.getId(), wg);
        User inGroupTech = newUser(User.Role.TECHNICIAN, opmc.getId(), wg);
        activeSessionFor(lead, inGroupTech);
        Long faultId = faultInWorkGroupQueue(opmc.getId(), wg);

        String body = "{\"faultId\":" + faultId + ",\"technicianId\":" + inGroupTech.getId() + "}";
        MvcResult res = mvc.perform(post("/api/jobs")
                .header("Authorization", bearer(lead.getId(), "TEAM_LEAD", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        String respBody = res.getResponse().getContentAsString();
        assertEquals(201, res.getResponse().getStatus(), "Body: " + respBody);

        JsonNode dto = json.readTree(respBody);
        assertEquals(inGroupTech.getId(), dto.get("technicianId").asLong(),
            "A real Job must be created for the in-group technician");
    }

    @Test
    void dispatchToTechnicianOutsideTheWorkGroup_isRejectedWith403() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wgA = newWorkGroup(opmc);
        WorkGroup wgB = newWorkGroup(opmc);
        User leadA = newUser(User.Role.TEAM_LEAD, opmc.getId(), wgA);
        User leadB = newUser(User.Role.TEAM_LEAD, opmc.getId(), wgB);
        User outsiderTech = newUser(User.Role.TECHNICIAN, opmc.getId(), wgB);
        // leadA needs their own active BOD session -- createJob's first check -- and the outsider
        // must be active in SOME session today (their own team's) for the request to reach the
        // Work-Group-mismatch check at all, rather than failing earlier on either guard --
        // confirmed by reading JobService.createJob's check order.
        activeSessionFor(leadA);
        activeSessionFor(leadB, outsiderTech);
        Long faultId = faultInWorkGroupQueue(opmc.getId(), wgA);

        String body = "{\"faultId\":" + faultId + ",\"technicianId\":" + outsiderTech.getId() + "}";
        MvcResult res = mvc.perform(post("/api/jobs")
                .header("Authorization", bearer(leadA.getId(), "TEAM_LEAD", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        String respBody = res.getResponse().getContentAsString();

        assertEquals(403, res.getResponse().getStatus(),
            "Dispatching to a Technician outside the caller's own Work Group must be rejected. Body: " + respBody);
        assertTrue(respBody.contains("does not belong to your Work Group"),
            "The rejection must name the real reason. Body: " + respBody);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // JOB-031 — the retired direct Admin-to-Technician assignment shape is genuinely rejected
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void oldDirectTechnicianAssignmentShape_isRejected() throws Exception {
        Opmc opmc = newOpmc();
        User admin = newUser(User.Role.ADMIN, opmc.getId(), null);
        User technician = newUser(User.Role.TECHNICIAN, opmc.getId(), null);
        Long faultId = faultInWorkGroupQueue(opmc.getId(), newWorkGroup(opmc));

        // The retired shape: no workGroupId at all, just a direct technician target -- exactly what
        // JobsPage.js used to send before Critical #31's fix (QA_Compliance_Consolidated_Report.md).
        String oldShapeBody = "{\"targetType\":\"TECHNICIAN\",\"targetId\":" + technician.getId() + "}";

        MvcResult res = mvc.perform(post("/api/faults/{id}/assign", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(oldShapeBody))
            .andReturn();

        assertEquals(400, res.getResponse().getStatus(),
            "The retired direct-to-Technician shape must be rejected, not silently accepted. Body: "
                + res.getResponse().getContentAsString());

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        assertTrue(fault.getAssignedTeamLeadId() == null || fault.getWorkGroupId() != null,
            "No Job/assignment bypassing the Work Group hierarchy must have been created by the old shape");
    }
}
