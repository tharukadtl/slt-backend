package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code FaultAssignmentService.reassignFault} — the Minor coverage gap flagged in
 * {@code JobReassignIntegrationTest.java}'s corrected javadoc: this method (Admin/Super Admin moves
 * a <i>fault</i> to a different Work Group, distinct from {@code JobService.reassignJob}, which
 * {@code JobReassignIntegrationTest} covers) had zero test coverage, under either its current name
 * or the deleted {@code FaultAssignmentTechnicianJobTest}'s pre-Stage-D behaviour.
 *
 * <p><b>Investigated first, per this project's standing discipline, not assumed.</b> Reading
 * {@code FaultAssignmentService.reassignFault} directly (`:227-357`) against its sibling
 * {@code assignFault} (`:49-223`) and against {@code JobService.reassignJob} (covered by
 * {@code JobReassignIntegrationTest}) surfaced a real asymmetry: {@code assignFault} explicitly
 * refuses a {@code COMPLETED}/{@code CANCELLED} fault (`:98-108`), and {@code JobService.reassignJob}
 * refuses a terminal job — but {@code reassignFault} has **no such check at all**. It also never
 * touches {@code Fault.status} itself (unlike {@code assignFault}, which sets it to {@code ASSIGNED}).
 * What it *does* validate, all confirmed by reading the method body rather than guessing: the target
 * Work Group must exist, must be {@code isActive}, and must belong to the fault's own OPMC
 * (`:247-263`) — the same three checks {@code assignFault} enforces. Role-wise, the controller
 * (`FaultController.java:173-181`) gates {@code POST /api/faults/{id}/reassign} at
 * {@code hasAnyRole('ADMIN','SUPER_ADMIN')} — already correctly paired, unlike the
 * {@code JobController} reassign endpoint the sibling {@code JobModal} Critical fix found missing
 * {@code SUPER_ADMIN} — so no role-gate bug exists here to fix.</p>
 *
 * <p><b>The one real gap this investigation found — logged, then fixed 2026-08-26.</b> Reassigning a
 * {@code COMPLETED}/{@code CANCELLED} fault previously succeeded silently (no terminal-status guard
 * at all, unlike {@code assignFault}'s sibling check at `:98-108`). Checked for real-world impact
 * before scoring it Minor rather than Major: {@code FaultRepository.findOpenByWorkGroupId} (the query
 * behind a Team Lead's Work Group queue) already excludes {@code COMPLETED}/{@code CANCELLED} faults
 * regardless of {@code workGroupId}, so a reassigned COMPLETED fault never resurfaced in anyone's
 * active queue — the impact was a misleading {@code FaultHistory}/note entry and a spurious Team Lead
 * notification, not a functional regression. **Fixed**: {@code reassignFault} now carries the same
 * guard {@code assignFault} already had, added immediately after the admin lookup (`:265-283`),
 * throwing {@code RuntimeException("Cannot reassign a " + status + " fault")} for either terminal
 * status — mapped to 400 by {@code GlobalExceptionHandler}, same as every other plain
 * {@code RuntimeException} in this service. See
 * {@code reassignCompletedFault_isRefused}/{@code reassignCancelledFault_isRefused} below, and the
 * Resolution Log / this Minor's entry in {@code QA_Compliance_Consolidated_Report.md}.</p>
 *
 * <p><b>Tool/placement.</b> MockMvc through the real filter chain, matching
 * {@code JobReassignIntegrationTest}'s and this project's established convention for this fix
 * family: real JWT filter, real {@code @PreAuthorize}, real MySQL, {@code @Transactional} rollback.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FaultReassignIntegrationTest {

    @Autowired private MockMvc                 mvc;
    @Autowired private JwtTokenProvider         jwt;
    @Autowired private FaultRepository          faultRepo;
    @Autowired private FaultHistoryRepository   faultHistoryRepo;
    @Autowired private OpmcRepository           opmcRepo;
    @Autowired private WorkGroupRepository      workGroupRepo;
    @Autowired private UserRepository           userRepo;
    @Autowired private ObjectMapper             json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_OPMC_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "frt" + userId, role, REAL_OPMC_ID);
    }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("frt" + n);
        u.setPasswordHash("x");
        u.setFirstName("First");
        u.setLastName("Last");
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_OPMC_ID);
        return userRepo.save(u);
    }

    private Opmc newOtherOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("FRT Other OPMC " + n);
        o.setCode("FR" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private WorkGroup newWorkGroup(Opmc opmc, boolean active) {
        long n = uniq();
        WorkGroup wg = new WorkGroup();
        wg.setName("FRT Test WG " + n);
        wg.setOpmc(opmc);
        wg.setIsActive(active);
        return workGroupRepo.save(wg);
    }

    private ReportFaultRequest reportRequest() {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");
        req.setDescription("No internet since 08:00");
        req.setLocationAddress("No. 5 Main Street, Colombo 03");
        req.setLocationCity("Colombo");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(REAL_OPMC_ID);
        req.setPriority("HIGH");
        return req;
    }

    private Long faultFor(User client) throws Exception {
        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reportRequest())))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus(),
            "Fault setup POST failed. Body: " + res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private MvcResult reassign(Long faultId, User caller, String role, Long newWorkGroupId,
            String reason) throws Exception {
        FaultAssignmentDTO.ReassignRequest body = FaultAssignmentDTO.ReassignRequest.builder()
                .newWorkGroupId(newWorkGroupId)
                .reason(reason)
                .build();
        return mvc.perform(post("/api/faults/{id}/reassign", faultId)
                .header("Authorization", bearer(caller.getId(), role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
            .andReturn();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Valid reassignment
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void adminReassignsFaultToDifferentActiveWorkGroup_succeeds() throws Exception {
        Opmc realOpmc = opmcRepo.findById(REAL_OPMC_ID).orElseThrow();
        WorkGroup oldWorkGroup = newWorkGroup(realOpmc, true);
        WorkGroup newWorkGroup = newWorkGroup(realOpmc, true);
        User client = newUser(User.Role.CLIENT, "Client Chandima");
        User admin  = newUser(User.Role.ADMIN,  "Admin Anusha");
        User oldLead = newUser(User.Role.TEAM_LEAD, "Old Lead Somasiri");

        Long faultId = faultFor(client);

        // Pre-condition: fault already picked up under the old Work Group, self-assigned to a
        // Team Lead — reassignment must clear that claim, not just swap the Work Group id.
        // assigned_team_lead_id carries a real FK to users, so this must be a real, saved User.
        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setWorkGroupId(oldWorkGroup.getId());
        fault.setWorkGroupName(oldWorkGroup.getName());
        fault.setAssignedTeamLeadId(oldLead.getId());
        fault.setAssignedTeamLeadName(oldLead.getFullName());
        faultRepo.save(fault);
        faultRepo.flush();
        em.clear();

        MvcResult res = reassign(faultId, admin, "ADMIN", newWorkGroup.getId(),
                "Original Work Group is overloaded this shift");
        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);

        faultRepo.flush();
        em.clear();
        Fault reassigned = faultRepo.findById(faultId).orElseThrow();
        assertEquals(newWorkGroup.getId(), reassigned.getWorkGroupId(),
            "The fault must now belong to the new Work Group");
        assertEquals(newWorkGroup.getName(), reassigned.getWorkGroupName(),
            "The denormalised Work Group name must be refreshed too, not left stale");
        assertNull(reassigned.getAssignedTeamLeadId(),
            "Reassigning must clear the prior Team Lead's claim under the old Work Group");
        assertNull(reassigned.getAssignedTeamLeadName(),
            "The denormalised prior Team Lead name must be cleared too");

        List<FaultHistory> history = faultHistoryRepo.findByFaultId(faultId);
        assertTrue(history.stream().anyMatch(h -> "FAULT_REASSIGNED".equals(h.getEventType())),
            "A FAULT_REASSIGNED audit-trail row must be written. Events: "
                + history.stream().map(FaultHistory::getEventType).toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Who can call this
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void superAdminCanReassignFault_notForbidden() throws Exception {
        Opmc realOpmc = opmcRepo.findById(REAL_OPMC_ID).orElseThrow();
        WorkGroup newWorkGroup = newWorkGroup(realOpmc, true);
        User client = newUser(User.Role.CLIENT, "Client Chandima");
        User superAdmin = newUser(User.Role.SUPER_ADMIN, "Super Sarath");

        Long faultId = faultFor(client);

        MvcResult res = reassign(faultId, superAdmin, "SUPER_ADMIN", newWorkGroup.getId(),
                "Load balancing across Work Groups");
        String body = res.getResponse().getContentAsString();

        assertNotEquals(403, res.getResponse().getStatus(),
            "A SUPER_ADMIN must not be Forbidden from reassigning a fault. Body: " + body);
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
    }

    @Test
    void teamLeadCannotReassignFault_forbidden() throws Exception {
        Opmc realOpmc = opmcRepo.findById(REAL_OPMC_ID).orElseThrow();
        WorkGroup newWorkGroup = newWorkGroup(realOpmc, true);
        User client = newUser(User.Role.CLIENT, "Client Chandima");
        User teamLead = newUser(User.Role.TEAM_LEAD, "Lead Kamal");

        Long faultId = faultFor(client);

        MvcResult res = reassign(faultId, teamLead, "TEAM_LEAD", newWorkGroup.getId(),
                "Trying to reroute a fault myself");
        assertEquals(403, res.getResponse().getStatus(),
            "A TEAM_LEAD must be Forbidden from reassigning a fault — this is an Admin-level "
                + "action. Body: " + res.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Existing validation on the target Work Group
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void reassignToWorkGroupInDifferentOpmc_isRefused() throws Exception {
        Opmc otherOpmc = newOtherOpmc();
        WorkGroup foreignWorkGroup = newWorkGroup(otherOpmc, true);
        User client = newUser(User.Role.CLIENT, "Client Chandima");
        User admin  = newUser(User.Role.ADMIN,  "Admin Anusha");

        Long faultId = faultFor(client);

        MvcResult res = reassign(faultId, admin, "ADMIN", foreignWorkGroup.getId(),
                "Wrong OPMC attempt");
        String body = res.getResponse().getContentAsString();
        assertEquals(400, res.getResponse().getStatus(), "Body: " + body);
        assertTrue(body.contains("OPMC"),
            "The refusal must name the OPMC mismatch. Body: " + body);

        em.clear();
        assertNull(faultRepo.findById(faultId).orElseThrow().getWorkGroupId(),
            "A refused cross-OPMC reassignment must leave the fault unassigned, not half-applied");
    }

    @Test
    void reassignToInactiveWorkGroup_isRefused() throws Exception {
        Opmc realOpmc = opmcRepo.findById(REAL_OPMC_ID).orElseThrow();
        WorkGroup inactiveWorkGroup = newWorkGroup(realOpmc, false);
        User client = newUser(User.Role.CLIENT, "Client Chandima");
        User admin  = newUser(User.Role.ADMIN,  "Admin Anusha");

        Long faultId = faultFor(client);

        MvcResult res = reassign(faultId, admin, "ADMIN", inactiveWorkGroup.getId(),
                "Inactive Work Group attempt");
        String body = res.getResponse().getContentAsString();
        assertEquals(400, res.getResponse().getStatus(), "Body: " + body);
        assertTrue(body.contains("not active"),
            "The refusal must explain the Work Group is inactive. Body: " + body);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Terminal-status guard — fixed 2026-08-26, was previously missing entirely (see class javadoc)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Was {@code reassignCompletedFault_currentlySucceeds_documentingMissingTerminalStatusGuard},
     * pinning the pre-fix behavior (a COMPLETED fault's reassignment silently succeeded). Now
     * asserts the fix: {@code reassignFault} refuses it with the same shape {@code assignFault}
     * (its sibling) already used, and — the part the old version of this test actually proved was
     * broken — the fault's {@code workGroupId} is confirmed unchanged, not merely that the request
     * failed.
     */
    @Test
    void reassignCompletedFault_isRefused() throws Exception {
        Opmc realOpmc = opmcRepo.findById(REAL_OPMC_ID).orElseThrow();
        WorkGroup oldWorkGroup = newWorkGroup(realOpmc, true);
        WorkGroup newWorkGroup = newWorkGroup(realOpmc, true);
        User client = newUser(User.Role.CLIENT, "Client Chandima");
        User admin  = newUser(User.Role.ADMIN,  "Admin Anusha");

        Long faultId = faultFor(client);
        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setWorkGroupId(oldWorkGroup.getId());
        fault.setWorkGroupName(oldWorkGroup.getName());
        fault.setStatus(Fault.FaultStatus.COMPLETED);
        faultRepo.save(fault);
        faultRepo.flush();
        em.clear();

        MvcResult res = reassign(faultId, admin, "ADMIN", newWorkGroup.getId(),
                "Attempting to reassign an already-completed fault");
        String body = res.getResponse().getContentAsString();

        assertEquals(400, res.getResponse().getStatus(), "Body: " + body);
        assertTrue(body.contains("COMPLETED") || body.contains("completed"),
            "The refusal must name the terminal status. Body: " + body);

        faultRepo.flush();
        em.clear();
        Fault untouched = faultRepo.findById(faultId).orElseThrow();
        assertEquals(oldWorkGroup.getId(), untouched.getWorkGroupId(),
            "The rejected reassignment must leave the Work Group exactly as it was — "
                + "this is what the pre-fix version of this test proved was NOT true");
        assertEquals(Fault.FaultStatus.COMPLETED, untouched.getStatus(),
            "Status itself is untouched either way (reassignFault never sets it) — the guard "
                + "rejects the whole request before any mutation, not just the status field");
    }

    /** Same guard, the other terminal status — {@code assignFault}'s sibling check covers both. */
    @Test
    void reassignCancelledFault_isRefused() throws Exception {
        Opmc realOpmc = opmcRepo.findById(REAL_OPMC_ID).orElseThrow();
        WorkGroup oldWorkGroup = newWorkGroup(realOpmc, true);
        WorkGroup newWorkGroup = newWorkGroup(realOpmc, true);
        User client = newUser(User.Role.CLIENT, "Client Chandima");
        User admin  = newUser(User.Role.ADMIN,  "Admin Anusha");

        Long faultId = faultFor(client);
        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setWorkGroupId(oldWorkGroup.getId());
        fault.setWorkGroupName(oldWorkGroup.getName());
        fault.setStatus(Fault.FaultStatus.CANCELLED);
        faultRepo.save(fault);
        faultRepo.flush();
        em.clear();

        MvcResult res = reassign(faultId, admin, "ADMIN", newWorkGroup.getId(),
                "Attempting to reassign a cancelled fault");
        String body = res.getResponse().getContentAsString();

        assertEquals(400, res.getResponse().getStatus(), "Body: " + body);
        assertTrue(body.contains("CANCELLED") || body.contains("cancelled"),
            "The refusal must name the terminal status. Body: " + body);

        faultRepo.flush();
        em.clear();
        Fault untouched = faultRepo.findById(faultId).orElseThrow();
        assertEquals(oldWorkGroup.getId(), untouched.getWorkGroupId(),
            "The rejected reassignment must leave the Work Group exactly as it was");
    }
}
