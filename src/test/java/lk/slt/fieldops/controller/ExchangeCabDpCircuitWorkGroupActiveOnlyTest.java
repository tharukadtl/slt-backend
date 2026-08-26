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
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.CabRepository;
import lk.slt.fieldops.repository.CircuitRepository;
import lk.slt.fieldops.repository.DpRepository;
import lk.slt.fieldops.repository.ExchangeRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
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
 * QA_Compliance_Consolidated_Report.md — Exchange/Cab/Dp/Circuit + WorkGroup Minor:
 * {@code findByIsActiveTrue()} was declared on all five repositories but never called anywhere, so
 * there was no way to list only active rows through any of these five list endpoints — a deactivated
 * row (see the activate()/deactivate() round trip covered by {@code ExchangeCabDpCircuitActivationTest})
 * kept appearing in every normal list result regardless.
 *
 * <p>Fix: an {@code activeOnly} query parameter added to all five list endpoints, same
 * name/Boolean-semantics/default-false convention as {@code GET /api/users}'s own {@code activeOnly}
 * parameter. Defaults to {@code false} — unchanged behavior, all rows regardless of {@code isActive}.
 *
 * <p><b>WorkGroup scoping note.</b> {@code WorkGroupService.getByOpmc} already, silently, only ever
 * returned active Work Groups (via {@code findActiveByOpmcId} — there is no unfiltered
 * {@code findByOpmcId} on that repository) — a pre-existing quirk found while implementing this,
 * predating this fix. Deliberately left as-is rather than expanded to avoid changing that endpoint's
 * long-standing default for existing callers; {@code activeOnly} is wired up for the bare (no
 * {@code opmcId}) {@code GET /api/workgroups} case only. See {@code WorkGroupService.getByOpmc}'s
 * javadoc for the same note.
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain, matching this session's established
 * convention for this hierarchy: real JWT filter, real {@code @PreAuthorize}, real MySQL,
 * {@code @Transactional} rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExchangeCabDpCircuitWorkGroupActiveOnlyTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository userRepo;
    @Autowired private OpmcRepository opmcRepo;
    @Autowired private ExchangeRepository exchangeRepo;
    @Autowired private CabRepository cabRepo;
    @Autowired private DpRepository dpRepo;
    @Autowired private CircuitRepository circuitRepo;
    @Autowired private WorkGroupRepository workGroupRepo;
    @Autowired private ObjectMapper json;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    // SUPER_ADMIN, not ADMIN: OpmcAccessGuard.resolveOpmcFilter scopes ADMIN to their own OPMC,
    // and this helper's admin belongs to a fresh, unrelated OPMC each call, not whichever OPMC a
    // given test's fixtures happen to use. These tests are about activeOnly, not OPMC scoping (that
    // has its own dedicated, correctly-scoped test below) — SUPER_ADMIN's genuine unscoped access
    // is what actually matches each test's intent.
    private String adminBearer() {
        User admin = newUser(User.Role.SUPER_ADMIN);
        return "Bearer " + jwt.createAccessToken(admin.getId(), "ao" + admin.getId(), "SUPER_ADMIN", admin.getOpmcId());
    }

    private User newUser(User.Role role) {
        long n = uniq();
        User u = new User();
        u.setUsername("ao" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(newOpmc().getId());
        return userRepo.save(u);
    }

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("AO OPMC " + n);
        o.setCode("AO" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private Exchange newExchange(Opmc opmc, boolean active) {
        long n = uniq();
        Exchange e = new Exchange();
        e.setName("AO Exchange " + n);
        e.setCode("AOX" + n);
        e.setOpmc(opmc);
        e.setIsActive(active);
        return exchangeRepo.save(e);
    }

    private Cab newCab(Exchange exchange, boolean active) {
        long n = uniq();
        Cab c = new Cab();
        c.setName("AO Cab " + n);
        c.setCode("AOB" + n);
        c.setExchange(exchange);
        c.setIsActive(active);
        return cabRepo.save(c);
    }

    private Dp newDp(Cab cab, boolean active) {
        long n = uniq();
        Dp dp = new Dp();
        dp.setName("AO Dp " + n);
        dp.setCode("AOP" + n);
        dp.setCab(cab);
        dp.setIsActive(active);
        return dpRepo.save(dp);
    }

    private Circuit newCircuit(Dp dp, boolean active) {
        long n = uniq();
        Circuit c = new Circuit();
        c.setCode("AOI" + n);
        c.setDp(dp);
        c.setIsActive(active);
        return circuitRepo.save(c);
    }

    private WorkGroup newWorkGroup(Opmc opmc, boolean active) {
        long n = uniq();
        WorkGroup wg = new WorkGroup();
        wg.setName("AO WG " + n);
        wg.setOpmc(opmc);
        wg.setIsActive(active);
        return workGroupRepo.save(wg);
    }

    private JsonNode getJson(String url, String auth) throws Exception {
        MvcResult r = mvc.perform(get(url).header("Authorization", auth)).andReturn();
        assertEquals(200, r.getResponse().getStatus(),
            "GET " + url + " must succeed. Body: " + r.getResponse().getContentAsString());
        return json.readTree(r.getResponse().getContentAsString());
    }

    private boolean containsId(JsonNode body, Long id) {
        for (JsonNode item : body) {
            if (id.equals(item.path("id").asLong())) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Default behavior unchanged (no activeOnly param) — same for all five entities, checked once
    // on Exchange and once on WorkGroup as representative of the hierarchy-entity and non-hierarchy
    // shapes respectively.
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void exchange_noActiveOnlyParam_defaultsToShowingBoth() throws Exception {
        Opmc opmc = newOpmc();
        Exchange active = newExchange(opmc, true);
        Exchange inactive = newExchange(opmc, false);
        String auth = adminBearer();

        JsonNode body = getJson("/api/exchanges", auth);
        assertTrue(containsId(body, active.getId()), "Active Exchange must appear. Body: " + body);
        assertTrue(containsId(body, inactive.getId()),
            "Inactive Exchange must still appear — default (no activeOnly) must be unchanged. Body: " + body);
    }

    @Test
    void workGroup_noActiveOnlyParam_defaultsToShowingBoth() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup active = newWorkGroup(opmc, true);
        WorkGroup inactive = newWorkGroup(opmc, false);
        String auth = adminBearer();

        JsonNode body = getJson("/api/workgroups", auth);
        assertTrue(containsId(body, active.getId()), "Active Work Group must appear. Body: " + body);
        assertTrue(containsId(body, inactive.getId()),
            "Inactive Work Group must still appear — default (no activeOnly) must be unchanged. Body: " + body);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // activeOnly=true — one test per entity, findByIsActiveTrue() genuinely wired up
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void exchange_activeOnlyTrue_excludesInactive() throws Exception {
        Opmc opmc = newOpmc();
        Exchange active = newExchange(opmc, true);
        Exchange inactive = newExchange(opmc, false);
        String auth = adminBearer();

        JsonNode body = getJson("/api/exchanges?activeOnly=true", auth);
        assertTrue(containsId(body, active.getId()), "Active Exchange must appear. Body: " + body);
        assertFalse(containsId(body, inactive.getId()), "Inactive Exchange must be excluded. Body: " + body);
    }

    @Test
    void cab_activeOnlyTrue_excludesInactive() throws Exception {
        Opmc opmc = newOpmc();
        Exchange exchange = newExchange(opmc, true);
        Cab active = newCab(exchange, true);
        Cab inactive = newCab(exchange, false);
        String auth = adminBearer();

        JsonNode body = getJson("/api/cabs?activeOnly=true", auth);
        assertTrue(containsId(body, active.getId()), "Active Cab must appear. Body: " + body);
        assertFalse(containsId(body, inactive.getId()), "Inactive Cab must be excluded. Body: " + body);
    }

    @Test
    void dp_activeOnlyTrue_excludesInactive() throws Exception {
        Opmc opmc = newOpmc();
        Exchange exchange = newExchange(opmc, true);
        Cab cab = newCab(exchange, true);
        Dp active = newDp(cab, true);
        Dp inactive = newDp(cab, false);
        String auth = adminBearer();

        JsonNode body = getJson("/api/dps?activeOnly=true", auth);
        assertTrue(containsId(body, active.getId()), "Active DP must appear. Body: " + body);
        assertFalse(containsId(body, inactive.getId()), "Inactive DP must be excluded. Body: " + body);
    }

    @Test
    void circuit_activeOnlyTrue_excludesInactive() throws Exception {
        Opmc opmc = newOpmc();
        Exchange exchange = newExchange(opmc, true);
        Cab cab = newCab(exchange, true);
        Dp dp = newDp(cab, true);
        Circuit active = newCircuit(dp, true);
        Circuit inactive = newCircuit(dp, false);
        String auth = adminBearer();

        JsonNode body = getJson("/api/circuits?activeOnly=true", auth);
        assertTrue(containsId(body, active.getId()), "Active Circuit must appear. Body: " + body);
        assertFalse(containsId(body, inactive.getId()), "Inactive Circuit must be excluded. Body: " + body);
    }

    @Test
    void workGroup_activeOnlyTrue_excludesInactive() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup active = newWorkGroup(opmc, true);
        WorkGroup inactive = newWorkGroup(opmc, false);
        String auth = adminBearer();

        JsonNode body = getJson("/api/workgroups?activeOnly=true", auth);
        assertTrue(containsId(body, active.getId()), "Active Work Group must appear. Body: " + body);
        assertFalse(containsId(body, inactive.getId()), "Inactive Work Group must be excluded. Body: " + body);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Composability — activeOnly combines correctly with the existing OPMC-scoping filter (gap #2),
    // proving the two filters compose rather than one silently overriding the other.
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void exchange_activeOnlyCombinesWithOpmcScoping() throws Exception {
        Opmc opmc = newOpmc();
        Exchange activeInOpmc = newExchange(opmc, true);
        Exchange inactiveInOpmc = newExchange(opmc, false);
        Opmc otherOpmc = newOpmc();
        Exchange activeInOtherOpmc = newExchange(otherOpmc, true);

        User teamLead = newUser(User.Role.TEAM_LEAD);
        teamLead.setOpmcId(opmc.getId());
        userRepo.save(teamLead);
        String auth = "Bearer " + jwt.createAccessToken(teamLead.getId(), "ao" + teamLead.getId(), "TEAM_LEAD", opmc.getId());

        JsonNode body = getJson("/api/exchanges?activeOnly=true", auth);
        assertTrue(containsId(body, activeInOpmc.getId()),
            "The active Exchange in the caller's own OPMC must appear. Body: " + body);
        assertFalse(containsId(body, inactiveInOpmc.getId()),
            "The inactive Exchange in the caller's own OPMC must be excluded by activeOnly. Body: " + body);
        assertFalse(containsId(body, activeInOtherOpmc.getId()),
            "An active Exchange in a DIFFERENT OPMC must still be excluded by OPMC scoping, "
                + "even though it would pass activeOnly on its own. Body: " + body);
    }
}
