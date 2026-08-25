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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * QA_Compliance_Consolidated_Report.md — Exchange/Cab/Dp/Circuit hierarchy gap #1: none of
 * ExchangeService/CabService/DpService/CircuitService had a reactivate()/activate() method — only
 * deactivate(Long id), asymmetric against Opmc and WorkGroup which both support the full cycle.
 *
 * <p>Fix: {@code activate(Long id)} added to all four services, same shape as
 * {@code WorkGroupService.activate()} (sets {@code isActive = true}, mirrors the existing
 * {@code deactivate()} exactly), with a {@code PATCH /{id}/activate} controller endpoint gated
 * {@code SUPER_ADMIN}/{@code ADMIN} — the same route shape and role gating already established for
 * {@code OpmcController}/{@code WorkGroupController}'s own activate endpoints, and the same role
 * gating these four controllers' own {@code deactivate} endpoints already use.
 *
 * <p><b>"Active-only queries" note.</b> Each repository already declares {@code findByIsActiveTrue()}
 * (Exchange/Cab/Dp/Circuit, same as {@code WorkGroupRepository}), but none of the four services calls
 * it — {@code getAll()} is a bare, unfiltered {@code findAll()}, so there is today no live
 * active-only-filtered LIST endpoint to observe a row disappearing from. That gap pre-dates this fix,
 * matches {@code WorkGroupRepository}'s own identical unused-method state, and is not part of gap #1
 * (which is specifically about the missing activate/reactivate path, not a missing filter). What this
 * test verifies instead is the one live, real channel that actually changes: {@code GET /{id}}'s
 * {@code isActive} field, confirmed false immediately after deactivate and true again after activate,
 * through the real endpoints — not by reading the entity directly.
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain, matching {@code ExchangeCabDpCircuitOpmcScopingTest}'s
 * convention: real JWT filter, real {@code @PreAuthorize}, real MySQL, {@code @Transactional} rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExchangeCabDpCircuitActivationTest {

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

    private String adminBearer() {
        User admin = newUser(User.Role.ADMIN);
        return "Bearer " + jwt.createAccessToken(admin.getId(), "act" + admin.getId(), "ADMIN", admin.getOpmcId());
    }

    private User newUser(User.Role role) {
        long n = uniq();
        User u = new User();
        u.setUsername("act" + n);
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
        o.setName("ACT OPMC " + n);
        o.setCode("ACT" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private Exchange newExchange() {
        long n = uniq();
        Exchange e = new Exchange();
        e.setName("ACT Exchange " + n);
        e.setCode("AEX" + n);
        e.setOpmc(newOpmc());
        return exchangeRepo.save(e);
    }

    private Cab newCab() {
        long n = uniq();
        Cab c = new Cab();
        c.setName("ACT Cab " + n);
        c.setCode("ACB" + n);
        c.setExchange(newExchange());
        return cabRepo.save(c);
    }

    private Dp newDp() {
        long n = uniq();
        Dp dp = new Dp();
        dp.setName("ACT Dp " + n);
        dp.setCode("ADP" + n);
        dp.setCab(newCab());
        return dpRepo.save(dp);
    }

    private Circuit newCircuit() {
        long n = uniq();
        Circuit c = new Circuit();
        c.setCode("ACI" + n);
        c.setDp(newDp());
        return circuitRepo.save(c);
    }

    private boolean isActive(String getUrl, Long id, String auth) throws Exception {
        MvcResult r = mvc.perform(get(getUrl, id).header("Authorization", auth)).andReturn();
        assertEquals(200, r.getResponse().getStatus(),
            "GET " + getUrl + " must succeed. Body: " + r.getResponse().getContentAsString());
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        return body.path("isActive").asBoolean();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void exchange_deactivateThenActivate_roundTripsIsActive() throws Exception {
        String auth = adminBearer();
        Exchange e = newExchange();
        exchangeRepo.flush();

        assertTrue(isActive("/api/exchanges/{id}", e.getId(), auth), "Must start active.");

        MvcResult deactivate = mvc.perform(delete("/api/exchanges/{id}", e.getId())
                .header("Authorization", auth)).andReturn();
        assertEquals(200, deactivate.getResponse().getStatus(),
            "Deactivate must succeed. Body: " + deactivate.getResponse().getContentAsString());
        assertFalse(isActive("/api/exchanges/{id}", e.getId(), auth),
            "Must read inactive immediately after deactivate.");

        MvcResult activate = mvc.perform(patch("/api/exchanges/{id}/activate", e.getId())
                .header("Authorization", auth)).andReturn();
        assertEquals(200, activate.getResponse().getStatus(),
            "Activate (gap #1 fix) must succeed. Body: " + activate.getResponse().getContentAsString());
        assertTrue(isActive("/api/exchanges/{id}", e.getId(), auth),
            "Must read active again immediately after activate — this is the round trip gap #1 lacked "
                + "entirely (deactivate had no way back).");
    }

    @Test
    void cab_deactivateThenActivate_roundTripsIsActive() throws Exception {
        String auth = adminBearer();
        Cab c = newCab();
        cabRepo.flush();

        assertTrue(isActive("/api/cabs/{id}", c.getId(), auth), "Must start active.");

        mvc.perform(delete("/api/cabs/{id}", c.getId()).header("Authorization", auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertFalse(isActive("/api/cabs/{id}", c.getId(), auth),
            "Must read inactive immediately after deactivate.");

        mvc.perform(patch("/api/cabs/{id}/activate", c.getId()).header("Authorization", auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertTrue(isActive("/api/cabs/{id}", c.getId(), auth),
            "Must read active again immediately after activate.");
    }

    @Test
    void dp_deactivateThenActivate_roundTripsIsActive() throws Exception {
        String auth = adminBearer();
        Dp dp = newDp();
        dpRepo.flush();

        assertTrue(isActive("/api/dps/{id}", dp.getId(), auth), "Must start active.");

        mvc.perform(delete("/api/dps/{id}", dp.getId()).header("Authorization", auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertFalse(isActive("/api/dps/{id}", dp.getId(), auth),
            "Must read inactive immediately after deactivate.");

        mvc.perform(patch("/api/dps/{id}/activate", dp.getId()).header("Authorization", auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertTrue(isActive("/api/dps/{id}", dp.getId(), auth),
            "Must read active again immediately after activate.");
    }

    @Test
    void circuit_deactivateThenActivate_roundTripsIsActive() throws Exception {
        String auth = adminBearer();
        Circuit c = newCircuit();
        circuitRepo.flush();

        assertTrue(isActive("/api/circuits/{id}", c.getId(), auth), "Must start active.");

        mvc.perform(delete("/api/circuits/{id}", c.getId()).header("Authorization", auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertFalse(isActive("/api/circuits/{id}", c.getId(), auth),
            "Must read inactive immediately after deactivate.");

        mvc.perform(patch("/api/circuits/{id}/activate", c.getId()).header("Authorization", auth))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertTrue(isActive("/api/circuits/{id}", c.getId(), auth),
            "Must read active again immediately after activate.");
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Role gating on the new endpoint — must match deactivate's own SUPER_ADMIN/ADMIN convention,
    // not silently open to a wider role set.
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void activate_forbiddenForTeamLead_allowedForAdmin() throws Exception {
        User teamLead = newUser(User.Role.TEAM_LEAD);
        String teamLeadAuth = "Bearer " + jwt.createAccessToken(
            teamLead.getId(), "act" + teamLead.getId(), "TEAM_LEAD", teamLead.getOpmcId());
        Exchange e = newExchange();
        exchangeRepo.flush();
        userRepo.flush();

        mvc.perform(delete("/api/exchanges/{id}", e.getId()).header("Authorization", adminBearer()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        MvcResult forbidden = mvc.perform(patch("/api/exchanges/{id}/activate", e.getId())
                .header("Authorization", teamLeadAuth)).andReturn();
        assertEquals(403, forbidden.getResponse().getStatus(),
            "TEAM_LEAD must be Forbidden from activate — same SUPER_ADMIN/ADMIN gate as deactivate. "
                + "Body: " + forbidden.getResponse().getContentAsString());

        MvcResult allowed = mvc.perform(patch("/api/exchanges/{id}/activate", e.getId())
                .header("Authorization", adminBearer())).andReturn();
        assertEquals(200, allowed.getResponse().getStatus(),
            "ADMIN must be able to activate. Body: " + allowed.getResponse().getContentAsString());
    }
}
