package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.AttachCircuitRequest;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * H1c — manually attach a Circuit to a Fault (Admin/Team Lead), via PATCH /api/faults/{id}/circuit.
 * Live, real-backend proof through the real endpoint, same standard as H1b's spec: real JWT
 * filter chain, real FaultService, real MySQL. Each test is @Transactional and rolls back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class H1cAttachCircuitIntegrationTest {

    @Autowired private MockMvc               mvc;
    @Autowired private JwtTokenProvider       jwt;
    @Autowired private FaultRepository        faultRepo;
    @Autowired private FaultHistoryRepository historyRepo;
    @Autowired private UserRepository         userRepo;
    @Autowired private ObjectMapper           json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_OPMC_ID = 1L;
    // Real Circuit rows from the H1a/H1d master-data import (docs/master-data/CIRCUIT.csv).
    private static final Long REAL_CIRCUIT_ID_1 = 19610L; // code "1"
    private static final Long REAL_CIRCUIT_ID_2 = 19619L; // code "10"

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_OPMC_ID);
    }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("h1c" + n);
        u.setPasswordHash("x");
        u.setFirstName("H1c");
        u.setLastName("Test");
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_OPMC_ID);
        return userRepo.save(u);
    }

    private Long reportFault(User client) throws Exception {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");
        req.setDescription("H1c attach-circuit test fault");
        req.setLocationAddress("Test address");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(REAL_OPMC_ID);
        req.setPriority("MEDIUM");

        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus());
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void attachCircuit_asAdmin_persistsCircuitIdAndCodeAndWritesHistory() throws Exception {
        User client = newUser(User.Role.CLIENT, "H1c Client");
        User admin  = newUser(User.Role.ADMIN,  "H1c Admin");
        Long faultId = reportFault(client);
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(REAL_CIRCUIT_ID_1);

        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Attach must succeed. Body: " + body);
        JsonNode dto = json.readTree(body);
        assertEquals(REAL_CIRCUIT_ID_1, dto.get("circuitId").asLong());
        assertEquals("1", dto.get("circuitCode").asText());

        // Verify against the database directly, not just the API response.
        em.flush(); em.clear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertEquals(REAL_CIRCUIT_ID_1, persisted.getCircuitId());
        assertEquals("1", persisted.getCircuitCode());

        // A fault_history row must exist for this change (this codebase's audit-trail convention).
        List<FaultHistory> history = historyRepo.findAll().stream()
            .filter(h -> h.getFault() != null && faultId.equals(h.getFault().getId()))
            .filter(h -> "CIRCUIT_ATTACHED".equals(h.getEventType()))
            .toList();
        assertEquals(1, history.size(), "Exactly one CIRCUIT_ATTACHED history row must be written");
        assertEquals("1", history.get(0).getNewValue());
        assertNull(history.get(0).getPreviousValue(), "No prior circuit was attached");
    }

    @Test
    void attachCircuit_asTeamLead_succeeds() throws Exception {
        // Confirms the H1c role decision (SUPER_ADMIN, ADMIN, TEAM_LEAD) live, not just via the
        // @PreAuthorize annotation text.
        User client   = newUser(User.Role.CLIENT, "H1c Client 2");
        User teamLead = newUser(User.Role.TEAM_LEAD, "H1c Team Lead");
        Long faultId  = reportFault(client);
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(REAL_CIRCUIT_ID_1);

        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "TEAM_LEAD must be allowed to attach a Circuit. Body: " + res.getResponse().getContentAsString());
    }

    @Test
    void attachCircuit_asTechnician_forbidden() throws Exception {
        User client     = newUser(User.Role.CLIENT, "H1c Client 3");
        User technician = newUser(User.Role.TECHNICIAN, "H1c Tech");
        Long faultId    = reportFault(client);
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(REAL_CIRCUIT_ID_1);

        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "TECHNICIAN can read Circuit data but must not be able to attach one to a Fault");
    }

    @Test
    void attachCircuit_nonexistentCircuit_returns404NotSilentSuccess() throws Exception {
        User client = newUser(User.Role.CLIENT, "H1c Client 4");
        User admin  = newUser(User.Role.ADMIN,  "H1c Admin 2");
        Long faultId = reportFault(client);
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(999_999_999L);

        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertNotEquals(200, res.getResponse().getStatus(),
            "A nonexistent circuitId must not silently succeed. Body: " + res.getResponse().getContentAsString());

        em.flush(); em.clear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertNull(persisted.getCircuitId(), "circuitId must remain unset after a rejected attach");
    }

    @Test
    void attachCircuit_changingAnAlreadyAttachedCircuit_recordsPreviousValue() throws Exception {
        User client = newUser(User.Role.CLIENT, "H1c Client 5");
        User admin  = newUser(User.Role.ADMIN,  "H1c Admin 3");
        Long faultId = reportFault(client);
        em.flush(); em.clear();

        AttachCircuitRequest req1 = new AttachCircuitRequest();
        req1.setCircuitId(REAL_CIRCUIT_ID_1);
        mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req1)))
            .andReturn();
        em.flush(); em.clear();

        AttachCircuitRequest req2 = new AttachCircuitRequest();
        req2.setCircuitId(REAL_CIRCUIT_ID_2);
        MvcResult res2 = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req2)))
            .andReturn();
        assertEquals(200, res2.getResponse().getStatus());

        em.flush(); em.clear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertEquals(REAL_CIRCUIT_ID_2, persisted.getCircuitId());
        assertEquals("10", persisted.getCircuitCode());

        List<FaultHistory> history = historyRepo.findAll().stream()
            .filter(h -> h.getFault() != null && faultId.equals(h.getFault().getId()))
            .filter(h -> "CIRCUIT_ATTACHED".equals(h.getEventType()))
            .toList();
        assertEquals(2, history.size(), "Both attach events must be recorded");
        FaultHistory second = history.stream()
            .filter(h -> "10".equals(h.getNewValue())).findFirst().orElseThrow();
        assertEquals("1", second.getPreviousValue(), "Second attach must record the prior circuit code");
    }

    @Test
    void attachCircuit_onCompletedFault_rejected() throws Exception {
        User client = newUser(User.Role.CLIENT, "H1c Client 6");
        User admin  = newUser(User.Role.ADMIN,  "H1c Admin 4");
        Long faultId = reportFault(client);
        em.flush();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setStatus(Fault.FaultStatus.COMPLETED);
        faultRepo.save(fault);
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(REAL_CIRCUIT_ID_1);
        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertNotEquals(200, res.getResponse().getStatus(),
            "A COMPLETED fault must reject a new Circuit attachment, not silently accept it");
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // §B-6 (QA_Compliance_Consolidated_Report.md) — the picker's OPMC prerequisite step
    // (AssignJobsScreen.tsx:237, GET /api/opmcs?status=ACTIVE) was a 403 for every Team Lead before
    // this fix, because SecurityConfig's old blanket "/api/opmcs/**" filter-chain matcher ran before
    // method security and blocked TEAM_LEAD outright, regardless of what OpmcController's own
    // @PreAuthorize said. Not previously covered here at all — this class only ever exercised the
    // terminal PATCH.
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void circuitPicker_opmcPrerequisiteStep_teamLeadNoLonger403() throws Exception {
        User teamLead = newUser(User.Role.TEAM_LEAD, "H1c Picker Team Lead");

        // Exactly the call AssignJobsScreen.tsx:237 makes when the picker is opened.
        MvcResult res = mvc.perform(get("/api/opmcs")
                .param("status", "ACTIVE")
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD")))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(),
            "GET /api/opmcs?status=ACTIVE (the picker's first cascade step) must no longer 403 a "
                + "Team Lead — SecurityConfig's filter-chain rule was blocking it before "
                + "OpmcController's own TEAM_LEAD @PreAuthorize was ever reached. Body: " + body);

        JsonNode list = json.readTree(body);
        boolean containsRealOpmc = false;
        for (JsonNode o : list) {
            if (REAL_OPMC_ID.equals(o.path("id").asLong())) { containsRealOpmc = true; break; }
        }
        assertTrue(containsRealOpmc,
            "The response must be the real OPMC list, not an empty/stubbed one — REAL_OPMC_ID must "
                + "be present. Body: " + body);
    }

    @Test
    void circuitPicker_fullCascade_teamLeadUnlocksEndToEnd() throws Exception {
        // Live proof, not just compiled: walk the exact OPMC -> Exchange -> Cab -> Dp -> Circuit
        // sequence AssignJobsScreen.tsx drives (:237, :249, :260, :271, :283), each response feeding
        // the next call's filter param, as a single Team Lead session — confirming the fix unblocks
        // the whole picker, not merely the first request in isolation.
        //
        // Updated 2026-08-25 for Exchange/Cab/Dp/Circuit gap #2 (OpmcAccessGuard.resolveOpmcFilter
        // added to these endpoints): a Team Lead is now genuinely scoped to their OWN OPMC's
        // hierarchy, resolved from their DB user row (OpmcAccessGuard.findCaller), not from any JWT
        // claim. The single fixed-REAL_OPMC_ID team lead this test used to reuse across every
        // candidate can no longer walk another OPMC's real hierarchy — that was exactly the gap
        // being closed, so a single caller finding real data under an OPMC they don't belong to
        // would now be a regression, not a pass. Each candidate is tried with a FRESH Team Lead
        // whose own opmcId is that candidate, matching how the real picker is actually used (a
        // Team Lead only ever browses their own OPMC).
        MvcResult opmcRes = mvc.perform(get("/api/opmcs").param("status", "ACTIVE")
                .header("Authorization", bearer(newUser(User.Role.TEAM_LEAD, "H1c Cascade Discovery").getId(), "TEAM_LEAD")))
            .andReturn();
        assertEquals(200, opmcRes.getResponse().getStatus(),
            "Step 1 GET /api/opmcs?status=ACTIVE. Body: " + opmcRes.getResponse().getContentAsString());
        JsonNode opmcs = json.readTree(opmcRes.getResponse().getContentAsString());
        assertTrue(opmcs.isArray() && opmcs.size() > 0, "There must be at least one active OPMC.");

        // REAL_OPMC_ID (this file's own synthetic fixture OPMC) has no real master-data Exchange
        // rows under it — the H1a/H1d import populated the real hierarchy under the OPMC.csv-derived
        // rows instead. Walk the live list (exactly what the picker's own Step 2 call would do next)
        // to find one that does, rather than hardcoding a second magic id alongside REAL_OPMC_ID —
        // trying each candidate as a Team Lead who genuinely belongs to it, since scoping now means
        // that's the only identity that can ever see it.
        JsonNode exchanges = null;
        String auth = null;
        for (JsonNode o : opmcs) {
            long candidateId = o.path("id").asLong();
            User candidateTeamLead = newUser(User.Role.TEAM_LEAD, "H1c Cascade Team Lead " + candidateId);
            candidateTeamLead.setOpmcId(candidateId);
            userRepo.save(candidateTeamLead);
            String candidateAuth = bearer(candidateTeamLead.getId(), "TEAM_LEAD");

            MvcResult r = mvc.perform(get("/api/exchanges").param("opmcId", String.valueOf(candidateId))
                    .header("Authorization", candidateAuth)).andReturn();
            assertEquals(200, r.getResponse().getStatus(),
                "Step 2 GET /api/exchanges?opmcId={id}. Body: " + r.getResponse().getContentAsString());
            JsonNode candidateExchanges = json.readTree(r.getResponse().getContentAsString());
            if (candidateExchanges.isArray() && candidateExchanges.size() > 0) {
                exchanges = candidateExchanges;
                auth = candidateAuth;
                break;
            }
        }
        assertNotNull(exchanges,
            "At least one active OPMC must have a real Exchange to walk the cascade further, for a "
                + "Team Lead who genuinely belongs to that OPMC.");
        long exchangeId = exchanges.get(0).path("id").asLong();

        MvcResult cabRes = mvc.perform(get("/api/cabs").param("exchangeId", String.valueOf(exchangeId))
                .header("Authorization", auth)).andReturn();
        assertEquals(200, cabRes.getResponse().getStatus(),
            "Step 3 GET /api/cabs?exchangeId={id}. Body: " + cabRes.getResponse().getContentAsString());
        JsonNode cabs = json.readTree(cabRes.getResponse().getContentAsString());
        assertTrue(cabs.isArray() && cabs.size() > 0,
            "The selected Exchange must have at least one real Cab. Body: "
                + cabRes.getResponse().getContentAsString());
        long cabId = cabs.get(0).path("id").asLong();

        MvcResult dpRes = mvc.perform(get("/api/dps").param("cabId", String.valueOf(cabId))
                .header("Authorization", auth)).andReturn();
        assertEquals(200, dpRes.getResponse().getStatus(),
            "Step 4 GET /api/dps?cabId={id}. Body: " + dpRes.getResponse().getContentAsString());
        JsonNode dps = json.readTree(dpRes.getResponse().getContentAsString());
        assertTrue(dps.isArray() && dps.size() > 0,
            "The selected Cab must have at least one real DP. Body: "
                + dpRes.getResponse().getContentAsString());
        long dpId = dps.get(0).path("id").asLong();

        MvcResult circuitRes = mvc.perform(get("/api/circuits").param("dpId", String.valueOf(dpId))
                .header("Authorization", auth)).andReturn();
        assertEquals(200, circuitRes.getResponse().getStatus(),
            "Step 5 GET /api/circuits?dpId={id}. Body: " + circuitRes.getResponse().getContentAsString());
        assertTrue(json.readTree(circuitRes.getResponse().getContentAsString()).isArray(),
            "The final Circuit list must be a valid (possibly empty) array, not an error. Body: "
                + circuitRes.getResponse().getContentAsString());
    }

    @Test
    void circuitPicker_narrowedFilterChain_doesNotReopenOpmcWriteActionsForTeamLead() throws Exception {
        // Defense-in-depth for the exact SecurityConfig change: the new per-verb "/api/opmcs/**"
        // matchers must still block every write action for TEAM_LEAD, same as ADMIN already is
        // (OpmcWriteActionsRoleRestrictionTest covers ADMIN) — only the read side was narrowed.
        User teamLead = newUser(User.Role.TEAM_LEAD, "H1c Write Guard Team Lead");
        String auth = bearer(teamLead.getId(), "TEAM_LEAD");

        MvcResult create = mvc.perform(post("/api/opmcs")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Should Not Exist\",\"code\":\"XX" + uniq() + "\",\"address\":\"x\"}"))
            .andReturn();
        assertEquals(403, create.getResponse().getStatus(),
            "TEAM_LEAD must still be Forbidden from creating an OPMC. Body: "
                + create.getResponse().getContentAsString());

        MvcResult deactivate = mvc.perform(patch("/api/opmcs/{id}/deactivate", REAL_OPMC_ID)
                .header("Authorization", auth))
            .andReturn();
        assertEquals(403, deactivate.getResponse().getStatus(),
            "TEAM_LEAD must still be Forbidden from deactivating an OPMC. Body: "
                + deactivate.getResponse().getContentAsString());
    }
}
