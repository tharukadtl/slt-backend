package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.FaultHistoryRepository;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Stage D — the Admin -> Work Group -> Team Lead fault-assignment rework
 * (FaultAssignmentService.assignFault/reassignFault/bulkAssign, FaultsPage.js's Assign/Reassign/
 * BulkAssign tabs) had zero test coverage anywhere, committed or not — confirmed by an exhaustive
 * search while auditing what {@code 9b5d15e} checkpointed. This class closes that gap for the
 * backend surface (frontend-admin's own live-verification convention is Cypress, matching
 * {@code attachCircuit.cy.js}/{@code attachCause.cy.js} — this class covers the REST endpoints
 * those UI tabs call, real JWT filter chain, real MySQL, same standard as everything else in this
 * session).
 *
 * <p><b>Cross-OPMC boundary — investigated before writing, not assumed.</b> Read
 * {@code FaultAssignmentService.assignFault}/{@code reassignFault}/{@code bulkAssign} in full: the
 * only OPMC check present anywhere in any of the three is that the *target Work Group's* OPMC must
 * equal the *fault's own* OPMC ({@code workGroup.getOpmc().getId().equals(fault.getOpmcId())}) — a
 * structural consistency check between the fault and the Work Group it's being pointed at. Neither
 * these three service methods nor {@code FaultController}'s {@code @PreAuthorize} annotations
 * ({@code hasAnyRole('ADMIN','SUPER_ADMIN')}, no narrower) check the *caller's own* OPMC against
 * the fault's OPMC anywhere. {@link #crossOpmcAssign_adminFromDifferentOpmc_liveBehaviorDocumented}
 * verifies this live rather than trusting the static read, and documents exactly what happens —
 * see that test's own docstring for the result and what it means.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FaultWorkGroupAssignmentIntegrationTest {

    @Autowired private MockMvc               mvc;
    @Autowired private JwtTokenProvider       jwt;
    @Autowired private FaultRepository        faultRepo;
    @Autowired private FaultHistoryRepository historyRepo;
    @Autowired private OpmcRepository         opmcRepo;
    @Autowired private WorkGroupRepository    workGroupRepo;
    @Autowired private UserRepository         userRepo;
    @Autowired private ObjectMapper           json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "wga" + userId, role, opmcId);
    }

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("WGA OPMC " + n);
        o.setCode("WGA" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private WorkGroup newWorkGroup(Opmc opmc, boolean active) {
        long n = uniq();
        WorkGroup wg = new WorkGroup();
        wg.setName("WGA Work Group " + n);
        wg.setOpmc(opmc);
        wg.setIsActive(active);
        return workGroupRepo.save(wg);
    }

    private User newUser(User.Role role, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("wga" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private Long reportFaultAs(Long opmcId) throws Exception {
        User client = newUser(User.Role.CLIENT, opmcId);
        String body = "{"
            + "\"category\":\"BROADBAND\","
            + "\"description\":\"WGA assignment test fault\","
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
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void flushAndClear() {
        faultRepo.flush();
        em.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Assign — persists, verified via fresh GET, not just the response
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void assignFault_persistsWorkGroupAssignment_verifiedViaFreshGet() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wg = newWorkGroup(opmc, true);
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        Long faultId = reportFaultAs(opmc.getId());

        String body = "{\"workGroupId\":" + wg.getId() + ",\"priority\":\"HIGH\","
            + "\"notifyTeamLead\":false,\"notifyCustomer\":false}";
        MvcResult res = mvc.perform(post("/api/faults/{id}/assign", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        String resBody = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + resBody);
        JsonNode dto = json.readTree(resBody);
        assertEquals(wg.getId(), dto.get("workGroupId").asLong());
        assertEquals("ASSIGNED", dto.get("faultStatus").asText());

        // Verify against a fresh, independent GET, not the assign response itself.
        flushAndClear();
        MvcResult read = mvc.perform(get("/api/faults/{id}", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId())))
            .andReturn();
        assertEquals(200, read.getResponse().getStatus());
        JsonNode readDto = json.readTree(read.getResponse().getContentAsString());
        assertEquals(wg.getId(), readDto.get("workGroupId").asLong(),
            "A fresh GET must show the persisted workGroupId, not just the assign response");
        assertEquals(wg.getName(), readDto.get("workGroupName").asText());
        assertEquals("ASSIGNED", readDto.get("status").asText());

        // And the persisted row directly.
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertEquals(wg.getId(), persisted.getWorkGroupId());
        assertEquals(Fault.FaultStatus.ASSIGNED, persisted.getStatus());
    }

    @Test
    void assignFault_toInactiveWorkGroup_rejected() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup inactiveWg = newWorkGroup(opmc, false);
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        Long faultId = reportFaultAs(opmc.getId());

        String body = "{\"workGroupId\":" + inactiveWg.getId() + "}";
        MvcResult res = mvc.perform(post("/api/faults/{id}/assign", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        assertNotEquals(200, res.getResponse().getStatus(),
            "Assigning to an inactive Work Group must not silently succeed");

        flushAndClear();
        assertNull(faultRepo.findById(faultId).orElseThrow().getWorkGroupId());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Bulk-assign — multiple faults, each verified independently
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void bulkAssign_multipleFaults_allPersistIndependently() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wg = newWorkGroup(opmc, true);
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        Long fault1 = reportFaultAs(opmc.getId());
        Long fault2 = reportFaultAs(opmc.getId());
        Long fault3 = reportFaultAs(opmc.getId());

        String body = "{\"faultIds\":[" + fault1 + "," + fault2 + "," + fault3 + "],"
            + "\"workGroupId\":" + wg.getId() + ",\"notifyTeamLead\":false}";
        MvcResult res = mvc.perform(post("/api/faults/bulk-assign")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        String resBody = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + resBody);
        JsonNode dto = json.readTree(resBody);
        assertEquals(3, dto.get("totalRequested").asInt());
        assertEquals(3, dto.get("successCount").asInt(), "Body: " + resBody);
        assertEquals(0, dto.get("failureCount").asInt(), "Body: " + resBody);

        // Each fault verified independently via a fresh GET -- not just the bulk response's own
        // success count, which could in principle be wrong about which faults actually changed.
        flushAndClear();
        for (Long faultId : List.of(fault1, fault2, fault3)) {
            MvcResult read = mvc.perform(get("/api/faults/{id}", faultId)
                    .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId())))
                .andReturn();
            JsonNode readDto = json.readTree(read.getResponse().getContentAsString());
            assertEquals(wg.getId(), readDto.get("workGroupId").asLong(),
                "Fault " + faultId + " must show the bulk-assigned workGroupId on a fresh GET");
            assertEquals("ASSIGNED", readDto.get("status").asText());
        }
    }

    @Test
    void bulkAssign_partialFailure_reportsWhichFaultsFailedWithoutBlockingTheRest() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wg = newWorkGroup(opmc, true);
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        Long realFault = reportFaultAs(opmc.getId());
        Long nonexistentFault = 999_999_999L;

        String body = "{\"faultIds\":[" + realFault + "," + nonexistentFault + "],"
            + "\"workGroupId\":" + wg.getId() + ",\"notifyTeamLead\":false}";
        MvcResult res = mvc.perform(post("/api/faults/bulk-assign")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        String resBody = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + resBody);
        JsonNode dto = json.readTree(resBody);
        assertEquals(1, dto.get("successCount").asInt(), "Body: " + resBody);
        assertEquals(1, dto.get("failureCount").asInt(), "Body: " + resBody);

        flushAndClear();
        assertEquals(wg.getId(), faultRepo.findById(realFault).orElseThrow().getWorkGroupId(),
            "The real fault must still be assigned even though the batch had one bad id");
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Reassign — moves between Work Groups, verified via fresh GET + history
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void reassignFault_movesFaultBetweenWorkGroups_verifiedViaFreshGet() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wgFrom = newWorkGroup(opmc, true);
        WorkGroup wgTo   = newWorkGroup(opmc, true);
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        Long faultId = reportFaultAs(opmc.getId());

        mvc.perform(post("/api/faults/{id}/assign", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workGroupId\":" + wgFrom.getId() + ",\"notifyTeamLead\":false,\"notifyCustomer\":false}"))
            .andReturn();
        flushAndClear();

        String reassignBody = "{\"newWorkGroupId\":" + wgTo.getId()
            + ",\"reason\":\"Original Work Group overloaded\",\"notifyTeamLead\":false,"
            + "\"notifyCustomer\":false,\"notifyPreviousTeamLead\":false}";
        MvcResult res = mvc.perform(post("/api/faults/{id}/reassign", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reassignBody))
            .andReturn();
        String resBody = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + resBody);
        assertEquals(wgTo.getId(), json.readTree(resBody).get("workGroupId").asLong());

        flushAndClear();
        MvcResult read = mvc.perform(get("/api/faults/{id}", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId())))
            .andReturn();
        JsonNode readDto = json.readTree(read.getResponse().getContentAsString());
        assertEquals(wgTo.getId(), readDto.get("workGroupId").asLong(),
            "A fresh GET must show the NEW workGroupId after reassignment, not the original one");
        assertEquals(wgTo.getName(), readDto.get("workGroupName").asText());

        boolean hasReassignRow = historyRepo.findAll().stream()
            .anyMatch(h -> h.getFault() != null && faultId.equals(h.getFault().getId())
                && h.getNewValue() != null && h.getNewValue().equals(wgTo.getName()));
        assertTrue(hasReassignRow,
            "A fault_history row recording the move to the new Work Group must exist");
    }

    @Test
    void reassignFault_withoutReason_rejected() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wgFrom = newWorkGroup(opmc, true);
        WorkGroup wgTo   = newWorkGroup(opmc, true);
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        Long faultId = reportFaultAs(opmc.getId());

        mvc.perform(post("/api/faults/{id}/assign", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workGroupId\":" + wgFrom.getId() + ",\"notifyTeamLead\":false,\"notifyCustomer\":false}"))
            .andReturn();
        flushAndClear();

        // reason is @NotBlank on ReassignRequest -- omitting it must be a validation failure, not
        // a silent no-reason reassignment.
        String body = "{\"newWorkGroupId\":" + wgTo.getId() + "}";
        MvcResult res = mvc.perform(post("/api/faults/{id}/reassign", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        assertEquals(400, res.getResponse().getStatus(),
            "A reassignment with no reason must be rejected. Body: " + res.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // TECHNICIAN forbidden — assignment is Admin/Super-Admin only
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void assignFault_asTechnician_forbidden() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wg = newWorkGroup(opmc, true);
        User technician = newUser(User.Role.TECHNICIAN, opmc.getId());
        Long faultId = reportFaultAs(opmc.getId());

        MvcResult res = mvc.perform(post("/api/faults/{id}/assign", faultId)
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workGroupId\":" + wg.getId() + "}"))
            .andReturn();
        assertEquals(403, res.getResponse().getStatus());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Cross-OPMC boundary — live-verified, not assumed. See this test's own docstring for the
    // result and what it means; the class javadoc records the code-reading that led here.
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * An ADMIN whose own {@code opmcId} is OPMC B assigns a fault that belongs to a DIFFERENT
     * OPMC A, to a Work Group that itself belongs to OPMC A (satisfying the one OPMC check
     * {@code assignFault} does perform — Work-Group-to-fault consistency). If a caller-scoping
     * boundary like the one this session built for Exchange/Cab/Dp/Circuit
     * ({@code OpmcAccessGuard.resolveOpmcFilter}) or WorkGroup/Opmc's own
     * {@code assertSameOpmcUnlessSuperAdmin} existed here too, this must be rejected (403/other
     * non-200) regardless of the Work-Group-to-fault check passing.
     *
     * <p><b>Live result, confirmed by running this test: the request SUCCEEDS (200), and the
     * cross-OPMC assignment persists.</b> Static reading already showed neither
     * {@code FaultController}'s {@code @PreAuthorize} annotations
     * (role-only: {@code hasAnyRole('ADMIN','SUPER_ADMIN')}) nor
     * {@code FaultAssignmentService.assignFault}/{@code reassignFault}/{@code bulkAssign} check the
     * caller's own OPMC against the fault's OPMC anywhere — only that the target Work Group's OPMC
     * matches the fault's OPMC. This test turns that static reading into a live-confirmed fact:
     * an OPMC-scoped ADMIN can assign, reassign, or bulk-assign ANY fault in the system, as long as
     * they pick a Work Group belonging to THAT fault's own OPMC — their own OPMC membership is
     * never checked. This is a real, live-confirmed gap, not fixed here per explicit instruction
     * (this task is test-writing only, no new feature code) — see
     * QA_Compliance_Consolidated_Report.md for where this is logged as a finding.
     */
    @Test
    void crossOpmcAssign_adminFromDifferentOpmc_liveBehaviorDocumented() throws Exception {
        Opmc faultOpmc = newOpmc();
        Opmc adminOpmc = newOpmc();
        assertNotEquals(faultOpmc.getId(), adminOpmc.getId(), "The two OPMCs must genuinely differ");

        WorkGroup wgInFaultOpmc = newWorkGroup(faultOpmc, true);
        User adminFromDifferentOpmc = newUser(User.Role.ADMIN, adminOpmc.getId());
        Long faultId = reportFaultAs(faultOpmc.getId());

        String body = "{\"workGroupId\":" + wgInFaultOpmc.getId() + ",\"notifyTeamLead\":false,\"notifyCustomer\":false}";
        MvcResult res = mvc.perform(post("/api/faults/{id}/assign", faultId)
                .header("Authorization", bearer(adminFromDifferentOpmc.getId(), "ADMIN", adminOpmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();

        // Documents live behavior as found, not a guess: no caller-OPMC boundary currently exists
        // for fault assignment. If this test ever starts failing (a non-200 here), that means the
        // boundary has since been added -- update this test's expectation and its docstring rather
        // than treating the failure as a regression.
        assertEquals(200, res.getResponse().getStatus(),
            "Documents current live behavior: an ADMIN from a different OPMC than the fault's own "
                + "is NOT blocked from assigning it, as long as the target Work Group belongs to "
                + "the fault's OPMC. Body: " + res.getResponse().getContentAsString());

        flushAndClear();
        assertEquals(wgInFaultOpmc.getId(), faultRepo.findById(faultId).orElseThrow().getWorkGroupId(),
            "The cross-OPMC assignment genuinely persists, confirming this isn't just a lenient "
                + "response with no real effect");
    }
}
