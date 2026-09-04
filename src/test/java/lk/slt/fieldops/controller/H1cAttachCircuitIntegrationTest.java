package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.AttachCircuitRequest;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.Cab;
import lk.slt.fieldops.entity.Circuit;
import lk.slt.fieldops.entity.Dp;
import lk.slt.fieldops.entity.Exchange;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CabRepository;
import lk.slt.fieldops.repository.CircuitRepository;
import lk.slt.fieldops.repository.DpRepository;
import lk.slt.fieldops.repository.ExchangeRepository;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultRepository;
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
 *
 * <p><b>2026-09-03, CI-portable-database fix.</b> Every test previously assumed OPMC id=1 and two
 * specific Circuit ids from the real 349,180-row CIRCUIT.csv import already existed
 * ({@code REAL_OPMC_ID}/{@code REAL_CIRCUIT_ID_1}/{@code REAL_CIRCUIT_ID_2}) — real rows that only
 * existed because this suite ran against the same long-lived local dev database all session, and
 * entirely incidental to what every test here actually exercises (role gating, 404-on-nonexistent,
 * history recording) — none of it depends on the Circuit being real, imported master data rather
 * than a fresh fixture row. Fixed by creating a real, per-test {@code Opmc}/{@code Exchange}/
 * {@code Cab}/{@code Dp}/{@code Circuit} chain via {@code newOpmc()}/{@code newExchange()}/
 * {@code newCab()}/{@code newDp()}/{@code newCircuit()} — same established self-contained-fixture
 * pattern as ~40 sibling files. {@code circuitPicker_fullCascade_teamLeadUnlocksEndToEnd} in
 * particular no longer needs to scan every real OPMC hoping to find one with real hierarchy data
 * underneath it — it creates its own, deterministically.
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
    @Autowired private OpmcRepository         opmcRepo;
    @Autowired private ExchangeRepository     exchangeRepo;
    @Autowired private CabRepository          cabRepo;
    @Autowired private DpRepository           dpRepo;
    @Autowired private CircuitRepository      circuitRepo;
    @Autowired private ObjectMapper           json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, opmcId);
    }

    /** A fresh, genuinely persisted OPMC — no test may assume any OPMC id pre-exists. */
    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("H1c Test OPMC " + n);
        o.setCode("H1C" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private Exchange newExchange(Opmc opmc) {
        long n = uniq();
        Exchange e = new Exchange();
        e.setName("H1c Test Exchange " + n);
        e.setCode("H1CEX" + n);
        e.setOpmc(opmc);
        e.setIsActive(true);
        return exchangeRepo.save(e);
    }

    private Cab newCab(Exchange exchange) {
        long n = uniq();
        Cab c = new Cab();
        c.setName("H1c Test Cab " + n);
        c.setCode("H1CCAB" + n);
        c.setExchange(exchange);
        c.setIsActive(true);
        return cabRepo.save(c);
    }

    private Dp newDp(Cab cab) {
        long n = uniq();
        Dp d = new Dp();
        d.setName("H1c Test Dp " + n);
        d.setCode("H1CDP" + n);
        d.setCab(cab);
        d.setIsActive(true);
        return dpRepo.save(d);
    }

    /** A fresh, genuinely persisted Circuit — no test may assume any real imported Circuit id pre-exists. */
    private Circuit newCircuit(Dp dp) {
        long n = uniq();
        Circuit c = new Circuit();
        c.setCode("H1CCIRC" + n);
        c.setDp(dp);
        c.setIsActive(true);
        return circuitRepo.save(c);
    }

    /** The full Exchange -> Cab -> Dp -> Circuit chain under one OPMC, in one call. */
    private Circuit newCircuit(Opmc opmc) {
        return newCircuit(newDp(newCab(newExchange(opmc))));
    }

    private User newUser(User.Role role, String fullName, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("h1c" + n);
        u.setPasswordHash("x");
        u.setFirstName("H1c");
        u.setLastName("Test");
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private Long reportFault(User client, Long opmcId) throws Exception {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");
        req.setDescription("H1c attach-circuit test fault");
        req.setLocationAddress("Test address");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(opmcId);
        req.setPriority("MEDIUM");

        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT", opmcId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus());
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void attachCircuit_asAdmin_persistsCircuitIdAndCodeAndWritesHistory() throws Exception {
        Opmc opmc = newOpmc();
        User client = newUser(User.Role.CLIENT, "H1c Client", opmc.getId());
        User admin  = newUser(User.Role.ADMIN,  "H1c Admin", opmc.getId());
        Circuit circuit = newCircuit(opmc);
        Long faultId = reportFault(client, opmc.getId());
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(circuit.getId());

        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Attach must succeed. Body: " + body);
        JsonNode dto = json.readTree(body);
        assertEquals(circuit.getId(), dto.get("circuitId").asLong());
        assertEquals(circuit.getCode(), dto.get("circuitCode").asText());

        // Verify against the database directly, not just the API response.
        em.flush(); em.clear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertEquals(circuit.getId(), persisted.getCircuitId());
        assertEquals(circuit.getCode(), persisted.getCircuitCode());

        // A fault_history row must exist for this change (this codebase's audit-trail convention).
        List<FaultHistory> history = historyRepo.findAll().stream()
            .filter(h -> h.getFault() != null && faultId.equals(h.getFault().getId()))
            .filter(h -> "CIRCUIT_ATTACHED".equals(h.getEventType()))
            .toList();
        assertEquals(1, history.size(), "Exactly one CIRCUIT_ATTACHED history row must be written");
        assertEquals(circuit.getCode(), history.get(0).getNewValue());
        assertNull(history.get(0).getPreviousValue(), "No prior circuit was attached");
    }

    @Test
    void attachCircuit_asTeamLead_succeeds() throws Exception {
        // Confirms the H1c role decision (SUPER_ADMIN, ADMIN, TEAM_LEAD) live, not just via the
        // @PreAuthorize annotation text.
        Opmc opmc = newOpmc();
        User client   = newUser(User.Role.CLIENT, "H1c Client 2", opmc.getId());
        User teamLead = newUser(User.Role.TEAM_LEAD, "H1c Team Lead", opmc.getId());
        Circuit circuit = newCircuit(opmc);
        Long faultId  = reportFault(client, opmc.getId());
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(circuit.getId());

        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "TEAM_LEAD must be allowed to attach a Circuit. Body: " + res.getResponse().getContentAsString());
    }

    @Test
    void attachCircuit_asTechnician_forbidden() throws Exception {
        Opmc opmc = newOpmc();
        User client     = newUser(User.Role.CLIENT, "H1c Client 3", opmc.getId());
        User technician = newUser(User.Role.TECHNICIAN, "H1c Tech", opmc.getId());
        Circuit circuit = newCircuit(opmc);
        Long faultId    = reportFault(client, opmc.getId());
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(circuit.getId());

        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "TECHNICIAN can read Circuit data but must not be able to attach one to a Fault");
    }

    @Test
    void attachCircuit_nonexistentCircuit_returns404NotSilentSuccess() throws Exception {
        Opmc opmc = newOpmc();
        User client = newUser(User.Role.CLIENT, "H1c Client 4", opmc.getId());
        User admin  = newUser(User.Role.ADMIN,  "H1c Admin 2", opmc.getId());
        Long faultId = reportFault(client, opmc.getId());
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(999_999_999L);

        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
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
        Opmc opmc = newOpmc();
        User client = newUser(User.Role.CLIENT, "H1c Client 5", opmc.getId());
        User admin  = newUser(User.Role.ADMIN,  "H1c Admin 3", opmc.getId());
        Circuit circuit1 = newCircuit(opmc);
        Circuit circuit2 = newCircuit(opmc);
        Long faultId = reportFault(client, opmc.getId());
        em.flush(); em.clear();

        AttachCircuitRequest req1 = new AttachCircuitRequest();
        req1.setCircuitId(circuit1.getId());
        mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req1)))
            .andReturn();
        em.flush(); em.clear();

        AttachCircuitRequest req2 = new AttachCircuitRequest();
        req2.setCircuitId(circuit2.getId());
        MvcResult res2 = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req2)))
            .andReturn();
        assertEquals(200, res2.getResponse().getStatus());

        em.flush(); em.clear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertEquals(circuit2.getId(), persisted.getCircuitId());
        assertEquals(circuit2.getCode(), persisted.getCircuitCode());

        List<FaultHistory> history = historyRepo.findAll().stream()
            .filter(h -> h.getFault() != null && faultId.equals(h.getFault().getId()))
            .filter(h -> "CIRCUIT_ATTACHED".equals(h.getEventType()))
            .toList();
        assertEquals(2, history.size(), "Both attach events must be recorded");
        FaultHistory second = history.stream()
            .filter(h -> circuit2.getCode().equals(h.getNewValue())).findFirst().orElseThrow();
        assertEquals(circuit1.getCode(), second.getPreviousValue(), "Second attach must record the prior circuit code");
    }

    @Test
    void attachCircuit_onCompletedFault_rejected() throws Exception {
        Opmc opmc = newOpmc();
        User client = newUser(User.Role.CLIENT, "H1c Client 6", opmc.getId());
        User admin  = newUser(User.Role.ADMIN,  "H1c Admin 4", opmc.getId());
        Circuit circuit = newCircuit(opmc);
        Long faultId = reportFault(client, opmc.getId());
        em.flush();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setStatus(Fault.FaultStatus.COMPLETED);
        faultRepo.save(fault);
        em.flush(); em.clear();

        AttachCircuitRequest req = new AttachCircuitRequest();
        req.setCircuitId(circuit.getId());
        MvcResult res = mvc.perform(patch("/api/faults/{id}/circuit", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
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
        Opmc opmc = newOpmc();
        User teamLead = newUser(User.Role.TEAM_LEAD, "H1c Picker Team Lead", opmc.getId());

        // Exactly the call AssignJobsScreen.tsx:237 makes when the picker is opened.
        MvcResult res = mvc.perform(get("/api/opmcs")
                .param("status", "ACTIVE")
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD", opmc.getId())))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(),
            "GET /api/opmcs?status=ACTIVE (the picker's first cascade step) must no longer 403 a "
                + "Team Lead — SecurityConfig's filter-chain rule was blocking it before "
                + "OpmcController's own TEAM_LEAD @PreAuthorize was ever reached. Body: " + body);

        JsonNode list = json.readTree(body);
        boolean containsOwnOpmc = false;
        for (JsonNode o : list) {
            if (opmc.getId().equals(o.path("id").asLong())) { containsOwnOpmc = true; break; }
        }
        assertTrue(containsOwnOpmc,
            "The response must be a real, genuine OPMC list, not an empty/stubbed one — the fixture "
                + "OPMC just created must be present. Body: " + body);
    }

    @Test
    void circuitPicker_fullCascade_teamLeadUnlocksEndToEnd() throws Exception {
        // Live proof, not just compiled: walk the exact OPMC -> Exchange -> Cab -> Dp -> Circuit
        // sequence AssignJobsScreen.tsx drives (:237, :249, :260, :271, :283), each response feeding
        // the next call's filter param, as a single Team Lead session — confirming the fix unblocks
        // the whole picker, not merely the first request in isolation.
        //
        // 2026-09-03: previously scanned every real OPMC hoping to find one with a real Exchange
        // hierarchy underneath it (the H1a/H1d import populated the hierarchy under only some of
        // the real OPMC.csv-derived rows), trying a fresh Team Lead per candidate since
        // OpmcAccessGuard.resolveOpmcFilter scopes a Team Lead to their own OPMC's hierarchy only.
        // Now creates its own OPMC with its own full Exchange->Cab->Dp->Circuit chain underneath —
        // deterministic, and no real master data required.
        Opmc opmc = newOpmc();
        Exchange exchange = newExchange(opmc);
        Cab cab = newCab(exchange);
        Dp dp = newDp(cab);
        Circuit circuit = newCircuit(dp);
        User teamLead = newUser(User.Role.TEAM_LEAD, "H1c Cascade Team Lead", opmc.getId());
        String auth = bearer(teamLead.getId(), "TEAM_LEAD", opmc.getId());
        em.flush(); em.clear();

        MvcResult opmcRes = mvc.perform(get("/api/opmcs").param("status", "ACTIVE")
                .header("Authorization", auth))
            .andReturn();
        assertEquals(200, opmcRes.getResponse().getStatus(),
            "Step 1 GET /api/opmcs?status=ACTIVE. Body: " + opmcRes.getResponse().getContentAsString());
        JsonNode opmcs = json.readTree(opmcRes.getResponse().getContentAsString());
        assertTrue(opmcs.isArray() && opmcs.size() > 0, "There must be at least one active OPMC.");

        MvcResult exRes = mvc.perform(get("/api/exchanges").param("opmcId", String.valueOf(opmc.getId()))
                .header("Authorization", auth)).andReturn();
        assertEquals(200, exRes.getResponse().getStatus(),
            "Step 2 GET /api/exchanges?opmcId={id}. Body: " + exRes.getResponse().getContentAsString());
        JsonNode exchanges = json.readTree(exRes.getResponse().getContentAsString());
        assertTrue(exchanges.isArray() && exchanges.size() > 0,
            "The fixture OPMC's own Exchange must be found. Body: " + exRes.getResponse().getContentAsString());
        long exchangeId = exchanges.get(0).path("id").asLong();
        assertEquals(exchange.getId().longValue(), exchangeId);

        MvcResult cabRes = mvc.perform(get("/api/cabs").param("exchangeId", String.valueOf(exchangeId))
                .header("Authorization", auth)).andReturn();
        assertEquals(200, cabRes.getResponse().getStatus(),
            "Step 3 GET /api/cabs?exchangeId={id}. Body: " + cabRes.getResponse().getContentAsString());
        JsonNode cabs = json.readTree(cabRes.getResponse().getContentAsString());
        assertTrue(cabs.isArray() && cabs.size() > 0,
            "The selected Exchange must have at least one Cab. Body: "
                + cabRes.getResponse().getContentAsString());
        long cabId = cabs.get(0).path("id").asLong();
        assertEquals(cab.getId().longValue(), cabId);

        MvcResult dpRes = mvc.perform(get("/api/dps").param("cabId", String.valueOf(cabId))
                .header("Authorization", auth)).andReturn();
        assertEquals(200, dpRes.getResponse().getStatus(),
            "Step 4 GET /api/dps?cabId={id}. Body: " + dpRes.getResponse().getContentAsString());
        JsonNode dps = json.readTree(dpRes.getResponse().getContentAsString());
        assertTrue(dps.isArray() && dps.size() > 0,
            "The selected Cab must have at least one DP. Body: "
                + dpRes.getResponse().getContentAsString());
        long dpId = dps.get(0).path("id").asLong();
        assertEquals(dp.getId().longValue(), dpId);

        MvcResult circuitRes = mvc.perform(get("/api/circuits").param("dpId", String.valueOf(dpId))
                .header("Authorization", auth)).andReturn();
        assertEquals(200, circuitRes.getResponse().getStatus(),
            "Step 5 GET /api/circuits?dpId={id}. Body: " + circuitRes.getResponse().getContentAsString());
        JsonNode circuits = json.readTree(circuitRes.getResponse().getContentAsString());
        assertTrue(circuits.isArray() && circuits.size() > 0,
            "The selected DP must have at least one Circuit. Body: "
                + circuitRes.getResponse().getContentAsString());
        assertEquals(circuit.getId().longValue(), circuits.get(0).path("id").asLong());
    }

    @Test
    void circuitPicker_narrowedFilterChain_doesNotReopenOpmcWriteActionsForTeamLead() throws Exception {
        // Defense-in-depth for the exact SecurityConfig change: the new per-verb "/api/opmcs/**"
        // matchers must still block every write action for TEAM_LEAD, same as ADMIN already is
        // (OpmcWriteActionsRoleRestrictionTest covers ADMIN) — only the read side was narrowed.
        Opmc opmc = newOpmc();
        User teamLead = newUser(User.Role.TEAM_LEAD, "H1c Write Guard Team Lead", opmc.getId());
        String auth = bearer(teamLead.getId(), "TEAM_LEAD", opmc.getId());

        MvcResult create = mvc.perform(post("/api/opmcs")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Should Not Exist\",\"code\":\"XX" + uniq() + "\",\"address\":\"x\"}"))
            .andReturn();
        assertEquals(403, create.getResponse().getStatus(),
            "TEAM_LEAD must still be Forbidden from creating an OPMC. Body: "
                + create.getResponse().getContentAsString());

        MvcResult deactivate = mvc.perform(patch("/api/opmcs/{id}/deactivate", opmc.getId())
                .header("Authorization", auth))
            .andReturn();
        assertEquals(403, deactivate.getResponse().getStatus(),
            "TEAM_LEAD must still be Forbidden from deactivating an OPMC. Body: "
                + deactivate.getResponse().getContentAsString());
    }
}
