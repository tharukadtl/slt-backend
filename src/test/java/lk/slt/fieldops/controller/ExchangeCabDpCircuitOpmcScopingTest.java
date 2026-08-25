package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Cab;
import lk.slt.fieldops.entity.Circuit;
import lk.slt.fieldops.entity.Dp;
import lk.slt.fieldops.entity.Exchange;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CabRepository;
import lk.slt.fieldops.repository.CircuitRepository;
import lk.slt.fieldops.repository.DpRepository;
import lk.slt.fieldops.repository.ExchangeRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * QA_Compliance_Consolidated_Report.md — Exchange/Cab/Dp/Circuit hierarchy gaps #2 and #3.
 *
 * <p><b>Gap #2 (this class's main subject).</b> {@code ExchangeService.getByOpmc}, {@code CabService},
 * {@code DpService}, and {@code CircuitService.getAll} previously had no OPMC scoping at all: {@code getAll()}
 * was a bare {@code findAll()}, and the parent-scoped list methods ({@code getByOpmc}/{@code getByExchange}/
 * {@code getByCab}/{@code getByDp}) trusted a caller-supplied id with no check that it belonged to the
 * caller's own OPMC. Fixed via {@code OpmcAccessGuard.resolveOpmcFilter}, same mechanism and same
 * scoped-empty-not-403 pattern as {@code PaymentController.getPending}/{@code getAll}
 * (see {@code PaymentOpmcScopingTest}) — a non-Super-Admin caller's result set is filtered to their own
 * OPMC's rows regardless of what id (if any) they pass; Super Admin stays unscoped.
 *
 * <p><b>Gap #3 (CircuitController role breadth), the last group only.</b> Confirmed against the real
 * caller before narrowing, not assumed: H1c's cascading Circuit-attachment picker
 * ({@code SLTMobileApp/src/screens/teamlead/AssignJobsScreen.tsx}) lives only in the Team Lead screen, and
 * its own attach endpoint ({@code PATCH /api/faults/{id}/circuit}) was already Admin/Team-Lead-only. So
 * {@code TECHNICIAN} is dropped from {@code CircuitController}'s read endpoints, bringing it in line with
 * its Exchange/Cab/Dp siblings (which never opened to TECHNICIAN in the first place).
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain, matching {@code PaymentOpmcScopingTest}'s
 * convention: real JWT filter, real {@code @PreAuthorize}, real MySQL, {@code @Transactional} rollback.
 * Each test builds the full Opmc -> Exchange -> Cab -> Dp -> Circuit chain for TWO different OPMCs so a
 * cross-OPMC leak at any level would be caught, not just at the level under direct test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExchangeCabDpCircuitOpmcScopingTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository userRepo;
    @Autowired private OpmcRepository opmcRepo;
    @Autowired private ExchangeRepository exchangeRepo;
    @Autowired private CabRepository cabRepo;
    @Autowired private DpRepository dpRepo;
    @Autowired private CircuitRepository circuitRepo;
    @Autowired private ObjectMapper json;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "hier" + userId, role, opmcId);
    }

    private User newUser(User.Role role, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("hier" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("HIER OPMC " + n);
        o.setCode("HIE" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private Exchange newExchange(Opmc opmc) {
        long n = uniq();
        Exchange e = new Exchange();
        e.setName("HIER Exchange " + n);
        e.setCode("HEX" + n);
        e.setOpmc(opmc);
        return exchangeRepo.save(e);
    }

    private Cab newCab(Exchange exchange) {
        long n = uniq();
        Cab c = new Cab();
        c.setName("HIER Cab " + n);
        c.setCode("HCB" + n);
        c.setExchange(exchange);
        return cabRepo.save(c);
    }

    private Dp newDp(Cab cab) {
        long n = uniq();
        Dp dp = new Dp();
        dp.setName("HIER Dp " + n);
        dp.setCode("HDP" + n);
        dp.setCab(cab);
        return dpRepo.save(dp);
    }

    private Circuit newCircuit(Dp dp) {
        long n = uniq();
        Circuit c = new Circuit();
        c.setCode("HCI" + n);
        c.setDp(dp);
        return circuitRepo.save(c);
    }

    /** One full Opmc -> Exchange -> Cab -> Dp -> Circuit chain. */
    private static class Chain {
        Opmc opmc; Exchange exchange; Cab cab; Dp dp; Circuit circuit;
    }

    private Chain newChain() {
        Chain c = new Chain();
        c.opmc = newOpmc();
        c.exchange = newExchange(c.opmc);
        c.cab = newCab(c.exchange);
        c.dp = newDp(c.cab);
        c.circuit = newCircuit(c.dp);
        return c;
    }

    private void flushAll() {
        userRepo.flush();
        opmcRepo.flush();
        exchangeRepo.flush();
        cabRepo.flush();
        dpRepo.flush();
        circuitRepo.flush();
    }

    private boolean containsId(JsonNode body, Long id) {
        for (JsonNode item : body) {
            if (id.equals(item.path("id").asLong())) return true;
        }
        return false;
    }

    private JsonNode getJson(String url, Long userId, String role, Long callerOpmcId) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", bearer(userId, role, callerOpmcId)))
            .andReturn().getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Exchange — GET /api/exchanges (bare getAll) and GET /api/exchanges?opmcId= (getByOpmc)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void exchange_getAll_teamLeadScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        Chain own = newChain();
        Chain other = newChain();
        User teamLead = newUser(User.Role.TEAM_LEAD, own.opmc.getId());
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.opmc.getId());
        flushAll();

        JsonNode tlBody = getJson("/api/exchanges", teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertTrue(containsId(tlBody, own.exchange.getId()),
            "TEAM_LEAD must see their own OPMC's Exchange. Body: " + tlBody);
        assertFalse(containsId(tlBody, other.exchange.getId()),
            "TEAM_LEAD must NOT see a different OPMC's Exchange. Body: " + tlBody);

        JsonNode saBody = getJson("/api/exchanges", superAdmin.getId(), "SUPER_ADMIN", own.opmc.getId());
        assertTrue(containsId(saBody, own.exchange.getId()) && containsId(saBody, other.exchange.getId()),
            "SUPER_ADMIN must remain unscoped, seeing both OPMCs' Exchanges. Body: " + saBody);
    }

    @Test
    void exchange_getByOpmc_foreignOpmcId_scopedEmptyForTeamLead_realDataForSuperAdmin() throws Exception {
        Chain own = newChain();
        Chain other = newChain();
        User teamLead = newUser(User.Role.TEAM_LEAD, own.opmc.getId());
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.opmc.getId());
        flushAll();

        JsonNode tlForeign = getJson("/api/exchanges?opmcId=" + other.opmc.getId(),
            teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertFalse(containsId(tlForeign, other.exchange.getId()),
            "TEAM_LEAD passing a different OPMC's id must get scoped-empty, not that OPMC's real "
                + "Exchange data. Body: " + tlForeign);

        JsonNode tlOwn = getJson("/api/exchanges?opmcId=" + own.opmc.getId(),
            teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertTrue(containsId(tlOwn, own.exchange.getId()),
            "TEAM_LEAD passing their OWN OPMC's id must still see it. Body: " + tlOwn);

        JsonNode saForeign = getJson("/api/exchanges?opmcId=" + other.opmc.getId(),
            superAdmin.getId(), "SUPER_ADMIN", own.opmc.getId());
        assertTrue(containsId(saForeign, other.exchange.getId()),
            "SUPER_ADMIN passing another OPMC's id must retain full access. Body: " + saForeign);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Cab — GET /api/cabs and GET /api/cabs?exchangeId= (getByExchange)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void cab_getAll_teamLeadScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        Chain own = newChain();
        Chain other = newChain();
        User teamLead = newUser(User.Role.TEAM_LEAD, own.opmc.getId());
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.opmc.getId());
        flushAll();

        JsonNode tlBody = getJson("/api/cabs", teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertTrue(containsId(tlBody, own.cab.getId()), "TEAM_LEAD must see their own OPMC's Cab. Body: " + tlBody);
        assertFalse(containsId(tlBody, other.cab.getId()),
            "TEAM_LEAD must NOT see a different OPMC's Cab (resolved via Exchange). Body: " + tlBody);

        JsonNode saBody = getJson("/api/cabs", superAdmin.getId(), "SUPER_ADMIN", own.opmc.getId());
        assertTrue(containsId(saBody, own.cab.getId()) && containsId(saBody, other.cab.getId()),
            "SUPER_ADMIN must remain unscoped, seeing both OPMCs' Cabs. Body: " + saBody);
    }

    @Test
    void cab_getByExchange_foreignExchangeId_scopedEmptyForTeamLead_realDataForSuperAdmin() throws Exception {
        Chain own = newChain();
        Chain other = newChain();
        User teamLead = newUser(User.Role.TEAM_LEAD, own.opmc.getId());
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.opmc.getId());
        flushAll();

        JsonNode tlForeign = getJson("/api/cabs?exchangeId=" + other.exchange.getId(),
            teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertFalse(containsId(tlForeign, other.cab.getId()),
            "TEAM_LEAD passing another OPMC's Exchange id must get scoped-empty, not real Cab data "
                + "under it. Body: " + tlForeign);

        JsonNode saForeign = getJson("/api/cabs?exchangeId=" + other.exchange.getId(),
            superAdmin.getId(), "SUPER_ADMIN", own.opmc.getId());
        assertTrue(containsId(saForeign, other.cab.getId()),
            "SUPER_ADMIN must retain full access when passing another OPMC's Exchange id. Body: " + saForeign);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Dp — GET /api/dps and GET /api/dps?cabId= (getByCab)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void dp_getAll_teamLeadScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        Chain own = newChain();
        Chain other = newChain();
        User teamLead = newUser(User.Role.TEAM_LEAD, own.opmc.getId());
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.opmc.getId());
        flushAll();

        JsonNode tlBody = getJson("/api/dps", teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertTrue(containsId(tlBody, own.dp.getId()), "TEAM_LEAD must see their own OPMC's DP. Body: " + tlBody);
        assertFalse(containsId(tlBody, other.dp.getId()),
            "TEAM_LEAD must NOT see a different OPMC's DP (resolved via Cab -> Exchange). Body: " + tlBody);

        JsonNode saBody = getJson("/api/dps", superAdmin.getId(), "SUPER_ADMIN", own.opmc.getId());
        assertTrue(containsId(saBody, own.dp.getId()) && containsId(saBody, other.dp.getId()),
            "SUPER_ADMIN must remain unscoped, seeing both OPMCs' DPs. Body: " + saBody);
    }

    @Test
    void dp_getByCab_foreignCabId_scopedEmptyForTeamLead_realDataForSuperAdmin() throws Exception {
        Chain own = newChain();
        Chain other = newChain();
        User teamLead = newUser(User.Role.TEAM_LEAD, own.opmc.getId());
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.opmc.getId());
        flushAll();

        JsonNode tlForeign = getJson("/api/dps?cabId=" + other.cab.getId(),
            teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertFalse(containsId(tlForeign, other.dp.getId()),
            "TEAM_LEAD passing another OPMC's Cab id must get scoped-empty, not real DP data under "
                + "it. Body: " + tlForeign);

        JsonNode saForeign = getJson("/api/dps?cabId=" + other.cab.getId(),
            superAdmin.getId(), "SUPER_ADMIN", own.opmc.getId());
        assertTrue(containsId(saForeign, other.dp.getId()),
            "SUPER_ADMIN must retain full access when passing another OPMC's Cab id. Body: " + saForeign);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Circuit — GET /api/circuits and GET /api/circuits?dpId= (getByDp), plus gap #3's role narrowing
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void circuit_getAll_teamLeadScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        Chain own = newChain();
        Chain other = newChain();
        User teamLead = newUser(User.Role.TEAM_LEAD, own.opmc.getId());
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.opmc.getId());
        flushAll();

        JsonNode tlBody = getJson("/api/circuits", teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertTrue(containsId(tlBody, own.circuit.getId()),
            "TEAM_LEAD must see their own OPMC's Circuit. Body: " + tlBody);
        assertFalse(containsId(tlBody, other.circuit.getId()),
            "TEAM_LEAD must NOT see a different OPMC's Circuit (resolved via DP -> Cab -> Exchange). "
                + "Body: " + tlBody);

        JsonNode saBody = getJson("/api/circuits", superAdmin.getId(), "SUPER_ADMIN", own.opmc.getId());
        assertTrue(containsId(saBody, own.circuit.getId()) && containsId(saBody, other.circuit.getId()),
            "SUPER_ADMIN must remain unscoped, seeing both OPMCs' Circuits. Body: " + saBody);
    }

    @Test
    void circuit_getByDp_foreignDpId_scopedEmptyForTeamLead_realDataForSuperAdmin() throws Exception {
        Chain own = newChain();
        Chain other = newChain();
        User teamLead = newUser(User.Role.TEAM_LEAD, own.opmc.getId());
        User superAdmin = newUser(User.Role.SUPER_ADMIN, own.opmc.getId());
        flushAll();

        JsonNode tlForeign = getJson("/api/circuits?dpId=" + other.dp.getId(),
            teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertFalse(containsId(tlForeign, other.circuit.getId()),
            "TEAM_LEAD passing another OPMC's DP id must get scoped-empty, not real Circuit data "
                + "under it. Body: " + tlForeign);

        JsonNode saForeign = getJson("/api/circuits?dpId=" + other.dp.getId(),
            superAdmin.getId(), "SUPER_ADMIN", own.opmc.getId());
        assertTrue(containsId(saForeign, other.circuit.getId()),
            "SUPER_ADMIN must retain full access when passing another OPMC's DP id. Body: " + saForeign);
    }

    @Test
    void circuit_technicianNoLongerHasReadAccess_teamLeadStillDoes() throws Exception {
        Chain own = newChain();
        User technician = newUser(User.Role.TECHNICIAN, own.opmc.getId());
        User teamLead = newUser(User.Role.TEAM_LEAD, own.opmc.getId());
        flushAll();

        MvcResult listResult = mvc.perform(get("/api/circuits")
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN", own.opmc.getId())))
            .andReturn();
        assertEquals(403, listResult.getResponse().getStatus(),
            "TECHNICIAN must now be Forbidden from GET /api/circuits (gap #3) — H1c's picker is "
                + "Team-Lead-only, confirmed against the real caller. Body: "
                + listResult.getResponse().getContentAsString());

        MvcResult getByIdResult = mvc.perform(get("/api/circuits/{id}", own.circuit.getId())
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN", own.opmc.getId())))
            .andReturn();
        assertEquals(403, getByIdResult.getResponse().getStatus(),
            "TECHNICIAN must now be Forbidden from GET /api/circuits/{id} too. Body: "
                + getByIdResult.getResponse().getContentAsString());

        JsonNode tlBody = getJson("/api/circuits", teamLead.getId(), "TEAM_LEAD", own.opmc.getId());
        assertTrue(containsId(tlBody, own.circuit.getId()),
            "TEAM_LEAD's read access must be unaffected by narrowing TECHNICIAN out. Body: " + tlBody);
    }
}
