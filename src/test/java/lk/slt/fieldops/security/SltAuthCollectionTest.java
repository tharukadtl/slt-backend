package lk.slt.fieldops.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AUTH-009 — every protected endpoint must answer an unauthenticated request with HTTP 401.
 *
 * <p><b>Tool substitution.</b> The sheet maps this row to a Newman/Postman artifact,
 * {@code SLT_Auth_Collection.json → No_Token_Returns_401}. The collection itself is checked in at
 * {@code fieldops/src/test/postman/SLT_Auth_Collection.json} so it can be run by CI once
 * {@code newman} and a live server are available, but neither is available in this environment
 * (newman is not installed and nothing is listening on :8080), so it cannot produce a verdict on
 * its own. This class is the executable twin: the same two requests, the same assertions, run
 * through the REAL {@code SecurityConfig} filter chain with MockMvc — the module's existing
 * integration convention ({@code BillDisputeAmendmentIntegrationTest}). The 401 is produced by
 * the filter chain's {@code AuthenticationEntryPoint}, which is the exact code path a live
 * Newman run would hit.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SltAuthCollectionTest {

    @Autowired private MockMvc mvc;

    @Test
    void noTokenReturns401() throws Exception {
        // ── Step 1: GET a protected collection with no Authorization header ────────────────
        MvcResult getRes = mvc.perform(get("/api/faults")).andReturn();
        String getBody = getRes.getResponse().getContentAsString();

        assertEquals(401, getRes.getResponse().getStatus(),
            "GET /api/faults without an Authorization header must be 401 Unauthorized, got "
                + getRes.getResponse().getStatus() + ". Body: " + getBody);
        assertFalse(getBody.contains("\"faultNumber\""),
            "No fault data may be returned to an unauthenticated caller. Body: " + getBody);

        // ── Step 2: the same for a write endpoint ─────────────────────────────────────────
        MvcResult postRes = mvc.perform(post("/api/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"no token\"}"))
            .andReturn();
        String postBody = postRes.getResponse().getContentAsString();

        assertEquals(401, postRes.getResponse().getStatus(),
            "POST /api/faults without an Authorization header must be 401 Unauthorized, got "
                + postRes.getResponse().getStatus() + ". Body: " + postBody);

        // ── Step 3: both responses carry an error message, not an empty body ──────────────
        for (String body : new String[] {getBody, postBody}) {
            assertFalse(body.isBlank(),
                "A 401 response must carry an error body, not an empty payload");
            assertTrue(body.contains("Unauthorized") || body.toLowerCase().contains("token"),
                "The 401 body must explain the authentication failure. Body: " + body);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // API-010 (11_API_ENDPOINTS) — 401 across the WHOLE protected surface
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * API-010: no Authorization header must yield HTTP 401 on <b>every</b> protected route, and the
     * public routes must stay public.
     *
     * <p><b>Why this is not covered by {@link #noTokenReturns401} above.</b> AUTH-009 asserts two
     * requests, both against {@code /api/faults}. That is enough to prove the
     * {@code AuthenticationEntryPoint} is wired, but it <b>cannot distinguish "every protected route
     * is gated" from "the faults controller is gated"</b> — a different controller accidentally
     * added to {@code SecurityConfig}'s {@code permitAll()} group would leave AUTH-009 green while
     * leaking data. API-010's claim is the breadth one ("401 for all protected endpoints"), so this
     * test sweeps one representative route per protected controller, plus a negative control over
     * the routes that are supposed to be public. Related but distinct, so written rather than
     * repointed — the same reasoning the {@code 09_ANALYTICS} pass used when rejecting a
     * plausible-looking repoint.</p>
     *
     * <p><b>Distinct from SEC-001 too.</b> SEC-001 / AUTH-012 cover a <i>tampered</i> token, which
     * reaches the entry point only after {@code JwtTokenProvider.validateToken} rejects it. This row
     * is the no-header path, where the filter never populates a {@code SecurityContext} at all —
     * a different branch of the same filter, worth pinning separately.</p>
     */
    @Test
    void noToken401AcrossProtectedSurface() throws Exception {
        // One representative GET per protected controller. The three the row names explicitly
        // (/api/faults, /api/users, /api/payments) are first; the rest make the "all" claim real.
        String[] protectedRoutes = {
            "/api/faults", "/api/users", "/api/payments",
            "/api/jobs", "/api/vehicles", "/api/inventory/materials/search",
            "/api/branches", "/api/notifications", "/api/kpi/leaderboard",
            "/api/dashboard/summary", "/api/reports/daily-fault-summary",
            "/api/resource-plans/lookup", "/api/attendance/today",
            "/actuator/env", "/actuator/metrics"
        };

        List<String> wrongStatus = new ArrayList<>();
        List<String> leaked      = new ArrayList<>();

        for (String route : protectedRoutes) {
            // A unique synthetic source IP keeps this sweep out of the shared ip:127.0.0.1
            // rate-limit bucket, so a late-running suite cannot turn these into 429s.
            MvcResult res = mvc.perform(get(route)
                    .header("X-Forwarded-For", "198.51.100." + (1 + (int) (System.nanoTime() % 250))))
                .andReturn();

            int status = res.getResponse().getStatus();
            if (status != 401) {
                wrongStatus.add(route + " -> " + status);
            }
            // Whatever the status, no domain payload may come back to an anonymous caller.
            String body = res.getResponse().getContentAsString();
            for (String marker : new String[] {
                    "\"faultNumber\"", "\"username\"", "\"jobNumber\"", "\"passwordHash\"",
                    "\"propertySources\"", "\"billReference\""}) {
                if (body.contains(marker)) leaked.add(route + " leaked " + marker);
            }
        }

        assertTrue(leaked.isEmpty(),
            "SECURITY: an unauthenticated request returned domain data: " + leaked);
        assertTrue(wrongStatus.isEmpty(),
            "Every protected route must answer an unauthenticated request with HTTP 401. "
                + wrongStatus.size() + " of " + protectedRoutes.length + " did not: " + wrongStatus
                + ". (A 403 here would mean the AuthenticationEntryPoint is not applying to that "
                + "route; a 200 would mean the route is not protected at all.)");

        // ── Negative control: the deliberately public routes must NOT be 401 ───────────────
        // Without this, a filter chain that rejected literally everything would satisfy the sweep
        // above while having broken login and health checks.
        String[] publicRoutes = {"/actuator/health", "/v3/api-docs"};
        for (String route : publicRoutes) {
            MvcResult res = mvc.perform(get(route)
                    .header("X-Forwarded-For", "203.0.113." + (1 + (int) (System.nanoTime() % 250))))
                .andReturn();
            assertNotEquals(401, res.getResponse().getStatus(),
                route + " is in SecurityConfig's permitAll() group and must remain reachable "
                    + "without a token. Body: " + res.getResponse().getContentAsString());
        }
    }
}
