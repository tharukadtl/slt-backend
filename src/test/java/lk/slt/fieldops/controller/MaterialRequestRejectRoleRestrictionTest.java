package lk.slt.fieldops.controller;

import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.MaterialRequest;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.MaterialRepository;
import lk.slt.fieldops.repository.MaterialRequestRepository;
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

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA_Compliance_Consolidated_Report.md Critical finding (raw-capture §B item 7) —
 * {@code POST /api/inventory/material-requests/{id}/reject} was {@code
 * @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")}, missing {@code TEAM_LEAD}, which its sibling
 * {@code /approve} endpoint has had since the v1.9 Stage D rework
 * ({@code @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")}). Every Team Lead reject
 * attempt genuinely 403'd — the reject UI was fully built and reachable
 * ({@code TeamMaterialRequestsScreen.tsx}) but every submission died in the catch block.
 *
 * <p><b>Fix, 2026-08-21.</b> {@code InventoryController.rejectRequest}'s annotation widened to match
 * {@code approveRequest} exactly. That alone would have under-scoped the fix, though: {@code
 * MaterialRequestService.approveRequest} has always enforced a Work Group boundary for a TEAM_LEAD
 * caller (only their own Work Group's requests), and {@code rejectRequest} had no equivalent check at
 * all — so widening the role gate alone would have let any Team Lead reject any OPMC's request, not
 * just ones they have real standing over. Found while implementing this fix, not investigated
 * separately; closed in the same change by adding the identical boundary check {@code
 * approveRequest} already has, to {@code rejectRequest} too.
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain, matching {@code
 * OpmcWriteActionsRoleRestrictionTest}'s convention: real JWT filter, real {@code @PreAuthorize}, real
 * service, real MySQL, {@code @Transactional} rollback per test.</p>
 *
 * <p><b>2026-09-03, CI-portable-database fix.</b> Every test previously assumed OPMC id=1 already
 * existed ({@code REAL_OPMC_ID = 1L}, looked up via {@code opmcRepo.findById(REAL_OPMC_ID)
 * .orElseThrow()} inside {@code newWorkGroup}) — a real row that only ever existed because this
 * suite ran against the same long-lived local dev database all session. Fixed by threading a
 * per-test, freshly-created {@code Opmc} through everything instead, matching the established
 * {@code newOpmc()} pattern used correctly in ~40 sibling files.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MaterialRequestRejectRoleRestrictionTest {

    @Autowired private MockMvc                   mvc;
    @Autowired private JwtTokenProvider           jwt;
    @Autowired private UserRepository             userRepo;
    @Autowired private OpmcRepository             opmcRepo;
    @Autowired private WorkGroupRepository        workGroupRepo;
    @Autowired private MaterialRepository         materialRepo;
    @Autowired private MaterialRequestRepository  requestRepo;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "mrr" + userId, role, opmcId);
    }

    /** A fresh, genuinely persisted OPMC — no test may assume any OPMC id pre-exists. */
    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("MRR Test OPMC " + n);
        o.setCode("MR" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private User newUser(User.Role role, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("mrr" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private WorkGroup newWorkGroup(Opmc opmc, User teamLead) {
        WorkGroup wg = new WorkGroup();
        wg.setName("MRR Test WG " + uniq());
        wg.setOpmc(opmc);
        wg.setTeamLead(teamLead);
        wg.setIsActive(true);
        return workGroupRepo.save(wg);
    }

    private Material newMaterial(Long opmcId) {
        Material m = new Material();
        m.setName("MRR Test Cable");
        m.setSku("MRR-SKU-" + uniq());
        m.setUnit("m");
        m.setUnitPrice(BigDecimal.valueOf(100));
        m.setCurrentStock(BigDecimal.valueOf(50));
        m.setMinimumThreshold(BigDecimal.TEN);
        m.setChargeType(Material.ChargeType.CHARGEABLE);
        m.setOpmcId(opmcId);
        m.setIsActive(true);
        return materialRepo.save(m);
    }

    private MaterialRequest newPendingRequest(Long workGroupId, Material material, User requester) {
        MaterialRequest req = MaterialRequest.builder()
            .requestedBy(requester.getId())
            .requestedByName(requester.getFullName())
            .workGroupId(workGroupId)
            .status(MaterialRequest.RequestStatus.PENDING)
            .urgency("NORMAL")
            .itemsData(material.getId() + ":2:0")
            .totalEstimatedCost(200.0)
            .totalApprovedCost(0.0)
            .build();
        return requestRepo.save(req);
    }

    private String rejectBody() {
        return "{\"reason\":\"Not needed anymore\",\"notes\":\"MRR test\",\"notifyRequester\":false}";
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // A Team Lead may now reject a request from their OWN Work Group — the 403 is gone
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void teamLeadCanRejectOwnWorkGroupRequest() throws Exception {
        Opmc opmc = newOpmc();
        User teamLead = newUser(User.Role.TEAM_LEAD, opmc.getId());
        User technician = newUser(User.Role.TECHNICIAN, opmc.getId());
        userRepo.flush();

        WorkGroup workGroup = newWorkGroup(opmc, teamLead);
        workGroupRepo.flush();
        teamLead.setWorkgroup(workGroup);
        userRepo.save(teamLead);
        userRepo.flush();

        Material material = newMaterial(opmc.getId());
        materialRepo.flush();
        MaterialRequest request = newPendingRequest(workGroup.getId(), material, technician);
        requestRepo.flush();

        MvcResult result = mvc.perform(post("/api/inventory/material-requests/{id}/reject", request.getId())
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectBody()))
            .andReturn();

        assertEquals(200, result.getResponse().getStatus(),
            "A Team Lead rejecting a request from their own Work Group must now succeed "
                + "(previously 403'd unconditionally). Body: " + result.getResponse().getContentAsString());

        MaterialRequest saved = requestRepo.findById(request.getId()).orElseThrow();
        assertEquals(MaterialRequest.RequestStatus.REJECTED, saved.getStatus(),
            "The request must actually be persisted as REJECTED, not just return 200");
        assertEquals(teamLead.getId(), saved.getReviewedBy(),
            "The rejecting Team Lead must be recorded as the reviewer");
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // A Team Lead still has no standing over another Work Group's request — the widened role
    // gate did not accidentally remove the Work Group boundary approveRequest already enforces
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void teamLeadCannotRejectOtherWorkGroupRequest() throws Exception {
        Opmc opmc = newOpmc();
        User teamLeadA = newUser(User.Role.TEAM_LEAD, opmc.getId());
        User teamLeadB = newUser(User.Role.TEAM_LEAD, opmc.getId());
        User technician = newUser(User.Role.TECHNICIAN, opmc.getId());
        userRepo.flush();

        WorkGroup workGroupA = newWorkGroup(opmc, teamLeadA);
        WorkGroup workGroupB = newWorkGroup(opmc, teamLeadB);
        workGroupRepo.flush();
        teamLeadA.setWorkgroup(workGroupA);
        teamLeadB.setWorkgroup(workGroupB);
        userRepo.save(teamLeadA);
        userRepo.save(teamLeadB);
        userRepo.flush();

        Material material = newMaterial(opmc.getId());
        materialRepo.flush();
        // Request belongs to Work Group B — Team Lead A has no standing over it.
        MaterialRequest request = newPendingRequest(workGroupB.getId(), material, technician);
        requestRepo.flush();

        MvcResult result = mvc.perform(post("/api/inventory/material-requests/{id}/reject", request.getId())
                .header("Authorization", bearer(teamLeadA.getId(), "TEAM_LEAD", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectBody()))
            .andReturn();

        assertEquals(403, result.getResponse().getStatus(),
            "A Team Lead must still be refused rejecting a request from a DIFFERENT Work Group — "
                + "widening the role gate to TEAM_LEAD must not have removed this boundary. Body: "
                + result.getResponse().getContentAsString());

        MaterialRequest unchanged = requestRepo.findById(request.getId()).orElseThrow();
        assertEquals(MaterialRequest.RequestStatus.PENDING, unchanged.getStatus(),
            "The out-of-scope request must remain untouched, still PENDING");
    }
}
