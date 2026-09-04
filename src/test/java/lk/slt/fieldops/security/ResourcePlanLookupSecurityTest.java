package lk.slt.fieldops.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.ConfirmedResourcePlan;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.ConfirmedResourcePlanRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * API-013 (11_API_ENDPOINTS, FR-17) — no route lets a client-supplied parameter override the
 * branch-level assignment authorization derived from the caller's own identity.
 *
 * <p><b>Tool substitution.</b> The row's {@code Tool} column says REST Assured, which is not a
 * dependency of this module (only {@code spring-boot-starter-test} + {@code spring-security-test}).
 * MockMvc through the REAL {@code SecurityConfig} filter chain and the real {@code @PreAuthorize}
 * method-security AOP is the module's established convention
 * ({@code BillDisputeAmendmentIntegrationTest}) and exercises the identical code path — the
 * {@code 13_SECURITY_HARDENING} precedent, applied on sheets 02–09 and 12/13 as well. Column K is
 * left reading "REST Assured".</p>
 *
 * <p><b>What the row's own assertion cannot be written as, and what replaces it.</b> The sheet's
 * {@code Assertion Code} reads {@code .body('branchId', equalTo(2))}. {@code ConfirmedPlanDTO} has
 * <b>no {@code branchId} field at all</b> — it carries {@code shift}, {@code zoneId},
 * {@code zoneName}, {@code predictedFaultCount}, {@code suggestedTechnicians},
 * {@code suggestedVehicles}, {@code materials} and {@code confirmedAt}. The branch is therefore
 * asserted through data identity instead, which is a strictly stronger check than echoing a field:
 * two plans are seeded for the same date, one per branch, with deliberately distinguishable
 * {@code zoneName}s, and the Team Lead must see their own branch's plan and never the other
 * branch's — with or without a tampered parameter.</p>
 *
 * <p><b>Why the expected result is "structurally impossible" rather than "checked per request".</b>
 * {@code ResourcePlanController.lookup} declares exactly two inputs: {@code @RequestParam date} and
 * {@code @AuthenticationPrincipal Long userId}. There is no {@code branchId} parameter to bind, and
 * Spring silently discards unbound query parameters — so {@code ?branchId=N} and {@code ?branch=N}
 * are not "rejected", they are never read. The branch is resolved one layer down, in
 * {@code ResourcePlanConfirmationService.getConfirmedPlanForTeamLead}, by loading the caller's own
 * {@code users} row and reading {@code User.getOpmcId()}. This test pins that property so a future
 * refactor cannot quietly add a client-supplied branch override.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ResourcePlanLookupSecurityTest {

    @Autowired private MockMvc                          mvc;
    @Autowired private JwtTokenProvider                 jwt;
    @Autowired private UserRepository                   userRepo;
    @Autowired private ConfirmedResourcePlanRepository  planRepo;
    @Autowired private ObjectMapper                     json;

    /** The row's "Branch 2" (the caller's own) and "Branch 3" (the branch being tampered towards). */
    private static final Long OWN_BRANCH   = 2L;
    private static final Long OTHER_BRANCH = 3L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private User newTeamLead(Long branchId) {
        long n = uniq();
        User u = new User();
        u.setUsername("rpl" + n);
        u.setPasswordHash("x");
        u.setFirstName("Branch");
        u.setLastName("Lead");
        u.setFullName("Branch Lead " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(User.Role.TEAM_LEAD);
        u.setOpmcId(branchId);
        return userRepo.save(u);
    }

    /** Persists a confirmed plan directly — the confirm endpoint is ADMIN-only and not what is under test. */
    private ConfirmedResourcePlan newPlan(Long branchId, LocalDate date, String zoneName, Long confirmedBy) {
        ConfirmedResourcePlan p = new ConfirmedResourcePlan();
        p.setOpmcId(branchId);
        p.setPlanDate(date);
        p.setShift(ConfirmedResourcePlan.Shift.MORNING);
        p.setZoneId((int) (uniq() % 100000));
        p.setZoneName(zoneName);
        p.setPredictedFaultCount(12.0);
        p.setSuggestedTechnicians(4);
        p.setSuggestedVehicles(2);
        p.setConfirmedBy(confirmedBy);
        p.setConfirmedAt(LocalDateTime.now());
        // createdAt is stamped by the entity's own @PrePersist hook — there is no setter.
        return planRepo.save(p);
    }

    private List<String> zoneNamesOf(String body) throws Exception {
        List<String> names = new ArrayList<>();
        for (JsonNode plan : json.readTree(body)) {
            names.add(plan.path("zoneName").asText());
        }
        return names;
    }

    @Test
    void branchOverrideIgnored() throws Exception {
        LocalDate today = LocalDate.now();

        User ownLead   = newTeamLead(OWN_BRANCH);
        User otherLead = newTeamLead(OTHER_BRANCH);

        // Two plans for the same date, distinguishable by zoneName, one per branch.
        String ownZone   = "OWN-BRANCH-ZONE-" + uniq();
        String otherZone = "OTHER-BRANCH-ZONE-" + uniq();
        newPlan(OWN_BRANCH,   today, ownZone,   ownLead.getId());
        newPlan(OTHER_BRANCH, today, otherZone, otherLead.getId());
        planRepo.flush();

        String ownBearer = "Bearer " + jwt.createAccessToken(
            ownLead.getId(), ownLead.getUsername(), "TEAM_LEAD", OWN_BRANCH);

        // ── Baseline: the untampered call returns the caller's OWN branch plan ──────────────
        MvcResult baseline = mvc.perform(get("/api/resource-plans/lookup")
                .header("Authorization", ownBearer))
            .andReturn();
        String baselineBody = baseline.getResponse().getContentAsString();
        assertEquals(200, baseline.getResponse().getStatus(), "Body: " + baselineBody);

        List<String> baselineZones = zoneNamesOf(baselineBody);
        assertTrue(baselineZones.contains(ownZone),
            "The Team Lead's own branch plan must be returned by the untampered lookup. Body: "
                + baselineBody);
        assertFalse(baselineZones.contains(otherZone),
            "Branch " + OTHER_BRANCH + "'s plan must never appear for a Branch " + OWN_BRANCH
                + " Team Lead, even without any tampering. Body: " + baselineBody);

        // ── Steps 1-2: ?branchId=3 must change nothing ──────────────────────────────────────
        MvcResult tamperedId = mvc.perform(get("/api/resource-plans/lookup")
                .param("branchId", String.valueOf(OTHER_BRANCH))
                .header("Authorization", ownBearer))
            .andReturn();
        String tamperedIdBody = tamperedId.getResponse().getContentAsString();
        assertEquals(200, tamperedId.getResponse().getStatus(),
            "?branchId=" + OTHER_BRANCH + " must not error — the parameter is simply not bound. "
                + "Body: " + tamperedIdBody);

        List<String> tamperedIdZones = zoneNamesOf(tamperedIdBody);
        assertFalse(tamperedIdZones.contains(otherZone),
            "?branchId=" + OTHER_BRANCH + " must NOT surface another branch's resource plan. "
                + "Body: " + tamperedIdBody);
        assertTrue(tamperedIdZones.contains(ownZone),
            "?branchId=" + OTHER_BRANCH + " must still return the caller's OWN branch plan — the "
                + "parameter must be inert, not a filter that empties the result. Body: "
                + tamperedIdBody);
        assertEquals(baselineZones, tamperedIdZones,
            "?branchId=" + OTHER_BRANCH + " must produce a byte-for-byte equivalent result set to "
                + "the untampered call. Baseline: " + baselineZones + " Tampered: " + tamperedIdZones);

        // ── Step 3: repeat with the alternative spelling ?branch=3 ──────────────────────────
        MvcResult tamperedShort = mvc.perform(get("/api/resource-plans/lookup")
                .param("branch", String.valueOf(OTHER_BRANCH))
                .header("Authorization", ownBearer))
            .andReturn();
        String tamperedShortBody = tamperedShort.getResponse().getContentAsString();
        assertEquals(200, tamperedShort.getResponse().getStatus(), "Body: " + tamperedShortBody);
        assertEquals(baselineZones, zoneNamesOf(tamperedShortBody),
            "?branch=" + OTHER_BRANCH + " must be equally inert. Body: " + tamperedShortBody);

        // ── Step 3b: and with both spellings at once, plus the one parameter that IS bound ──
        MvcResult tamperedBoth = mvc.perform(get("/api/resource-plans/lookup")
                .param("branchId", String.valueOf(OTHER_BRANCH))
                .param("branch",   String.valueOf(OTHER_BRANCH))
                .param("date",     today.toString())
                .header("Authorization", ownBearer))
            .andReturn();
        String tamperedBothBody = tamperedBoth.getResponse().getContentAsString();
        assertEquals(200, tamperedBoth.getResponse().getStatus(), "Body: " + tamperedBothBody);
        assertEquals(baselineZones, zoneNamesOf(tamperedBothBody),
            "Combining both override spellings with the legitimate ?date parameter must still "
                + "yield only the caller's own branch. Body: " + tamperedBothBody);

        // ── Step 4: the other branch's Team Lead sees the MIRROR IMAGE ─────────────────────
        // Without this, every assertion above would pass vacuously against an endpoint that
        // returned nothing to anyone. This proves the seeded Branch-3 plan is genuinely
        // readable — just not by the Branch-2 caller.
        String otherBearer = "Bearer " + jwt.createAccessToken(
            otherLead.getId(), otherLead.getUsername(), "TEAM_LEAD", OTHER_BRANCH);
        MvcResult mirror = mvc.perform(get("/api/resource-plans/lookup")
                .param("branchId", String.valueOf(OWN_BRANCH))
                .header("Authorization", otherBearer))
            .andReturn();
        String mirrorBody = mirror.getResponse().getContentAsString();
        assertEquals(200, mirror.getResponse().getStatus(), "Body: " + mirrorBody);

        List<String> mirrorZones = zoneNamesOf(mirrorBody);
        assertTrue(mirrorZones.contains(otherZone),
            "Branch " + OTHER_BRANCH + "'s own Team Lead must see Branch " + OTHER_BRANCH
                + "'s plan — proving the assertions above are not vacuously true against an "
                + "endpoint that returns nothing. Body: " + mirrorBody);
        assertFalse(mirrorZones.contains(ownZone),
            "…and still not Branch " + OWN_BRANCH + "'s, despite ?branchId=" + OWN_BRANCH
                + ". Body: " + mirrorBody);

        // ── Expected Result: the branch is derived from identity, not from the request ─────
        // Structural proof, not a per-request observation: the handler has no branch parameter
        // to bind in the first place.
        List<String> handlerParams = new ArrayList<>();
        for (java.lang.reflect.Parameter p :
                lk.slt.fieldops.controller.ResourcePlanController.class
                    .getDeclaredMethod("lookup", String.class, Long.class)
                    .getParameters()) {
            org.springframework.web.bind.annotation.RequestParam rp =
                p.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
            if (rp != null) handlerParams.add(rp.value().isBlank() ? p.getName() : rp.value());
        }
        assertFalse(handlerParams.stream().anyMatch(n -> n.toLowerCase().contains("branch")),
            "ResourcePlanController.lookup must declare NO branch-bearing @RequestParam — a "
                + "client-supplied branch must be structurally impossible to accept, not merely "
                + "checked. Bound request params found: " + handlerParams);
    }
}
