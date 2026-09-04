package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
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

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * RES-020 (05_RESOURCE_MGMT) -- logged as "genuinely never-written, no substitute exists anywhere"
 * in the 2026-09-02 completeness recount. Investigated before writing: the described behavior was
 * already fully built, not a product gap -- {@code WorkGroupService.mapRequestToEntity} (:122-128)
 * carries a comment literally reading "RES-020" naming the exact single-Team-Lead enforcement this
 * test proves, throwing a real {@code ConflictException} (confirmed mapped to HTTP 409 in
 * {@code GlobalExceptionHandler}). Never exercised by a test -- confirmed by an exhaustive grep for
 * "already leads another Work Group" across the whole test tree before writing this file.
 *
 * <p>Technician membership (row step 4) is not part of {@code CreateWorkGroupRequest} at all --
 * confirmed directly, that DTO has no {@code technicianIds} field. A Technician's Work Group
 * membership is a property of the User record itself ({@code User.workgroup}, set via
 * {@code PUT /api/users/{id}}'s {@code workgroupId} field) -- the same mechanism this whole session's
 * earlier Work Group onboarding work already established, not a WorkGroup-side roster.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkGroupSingleLeadEnforcementTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private OpmcRepository opmcRepo;
    @Autowired private WorkGroupRepository workGroupRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private ObjectMapper json;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "wsl" + userId, role, opmcId);
    }

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("WSL OPMC " + n);
        o.setCode("WSL" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private User newUser(User.Role role, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("wsl" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    @Test
    void createWorkGroup_withMembership_returns201() throws Exception {
        Opmc opmc = newOpmc();
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        User lead = newUser(User.Role.TEAM_LEAD, opmc.getId());
        User tech1 = newUser(User.Role.TECHNICIAN, opmc.getId());
        User tech2 = newUser(User.Role.TECHNICIAN, opmc.getId());

        String body = "{\"name\":\"RES-020 Work Group\",\"opmcId\":" + opmc.getId()
            + ",\"teamLeadId\":" + lead.getId() + "}";
        MvcResult res = mvc.perform(post("/api/workgroups")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        String respBody = res.getResponse().getContentAsString();
        assertEquals(201, res.getResponse().getStatus(), "Body: " + respBody);
        long wgId = json.readTree(respBody).get("id").asLong();

        // Step 4 — add technicians as members via the real mechanism (User.workgroupId).
        // fullName is the DTO's one @NotBlank field and must be resent even though it's unchanged.
        for (User tech : new User[]{tech1, tech2}) {
            String updateBody = "{\"fullName\":\"" + tech.getFullName() + "\",\"workgroupId\":" + wgId + "}";
            MvcResult addRes = mvc.perform(put("/api/users/{id}", tech.getId())
                    .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updateBody))
                .andReturn();
            assertEquals(200, addRes.getResponse().getStatus(),
                "Adding a Technician to the Work Group must succeed. Body: "
                    + addRes.getResponse().getContentAsString());
        }
        User freshTech1 = userRepo.findById(tech1.getId()).orElseThrow();
        assertEquals(wgId, freshTech1.getWorkgroup().getId(),
            "Membership must actually be persisted, not just accepted");

        // "Remove" tech1 -- investigated before writing: UserService.updateUser (:198-201)
        // deliberately requires every TECHNICIAN/TEAM_LEAD to hold a non-null Work Group (the
        // RES-024 fix from earlier this session), so a bare removal-to-none is not a supported
        // operation by design, not a bug. Sending workgroupId:null is also a documented no-op
        // (:190, only assigns when non-null -- the same preserve-on-absent shape as the OPMC fix
        // elsewhere in this report), confirming this is intentional, not an oversight. The real,
        // supported "membership update" the row's Expected Result describes is moving a Technician
        // to a DIFFERENT Work Group -- tested here instead of a no-op null that would misreport a
        // deliberate constraint as a defect.
        long secondWgId = json.readTree(
            mvc.perform(post("/api/workgroups")
                    .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"RES-020 Second Group\",\"opmcId\":" + opmc.getId() + "}"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        MvcResult moveRes = mvc.perform(put("/api/users/{id}", tech1.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + tech1.getFullName() + "\",\"workgroupId\":" + secondWgId + "}"))
            .andReturn();
        assertEquals(200, moveRes.getResponse().getStatus(),
            "Moving a Technician to a different Work Group must succeed. Body: "
                + moveRes.getResponse().getContentAsString());
        User freshTech1After = userRepo.findById(tech1.getId()).orElseThrow();
        assertEquals(secondWgId, freshTech1After.getWorkgroup().getId(),
            "Membership must actually update to the new Work Group, not just accept the request");
    }

    @Test
    void aTeamLeadCannotLeadTwoWorkGroupsSimultaneously() throws Exception {
        Opmc opmc = newOpmc();
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        User lead = newUser(User.Role.TEAM_LEAD, opmc.getId());

        String firstBody = "{\"name\":\"RES-020 First Group\",\"opmcId\":" + opmc.getId()
            + ",\"teamLeadId\":" + lead.getId() + "}";
        MvcResult first = mvc.perform(post("/api/workgroups")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody))
            .andReturn();
        assertEquals(201, first.getResponse().getStatus(),
            "Body: " + first.getResponse().getContentAsString());

        String secondBody = "{\"name\":\"RES-020 Second Group\",\"opmcId\":" + opmc.getId()
            + ",\"teamLeadId\":" + lead.getId() + "}";
        MvcResult second = mvc.perform(post("/api/workgroups")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondBody))
            .andReturn();
        assertEquals(409, second.getResponse().getStatus(),
            "A second Work Group with the same Team Lead must be rejected as a conflict. Body: "
                + second.getResponse().getContentAsString());
    }
}
