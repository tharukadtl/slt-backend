package lk.slt.fieldops.security;

import lk.slt.fieldops.config.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * API-016 (11_API_ENDPOINTS) — {@code /actuator/**} is gated to ADMIN/SUPER_ADMIN, and only
 * {@code /actuator/health} stays public.
 *
 * <p><b>Why this is new coverage of an already-fixed area, not a duplicate.</b> The underlying
 * defect ("Actuator {@code env}/{@code metrics} reachable by any authenticated user, not just
 * admins") is recorded as RESOLVED 2026-07-24 in this project's
 * {@code docs/QA_Compliance_Consolidated_Report.md} Resolution Log — and that same log entry states
 * explicitly: <i>"no dedicated new test was added for the 403-vs-200 behavior specifically —
 * flagged as a coverage gap for a possible follow-up."</i> The fix was verified by matcher-ordering
 * code review plus a full regression pass, never by an assertion. A repository-wide search confirms
 * the only two test files that mention {@code /actuator} at all are {@code AuthSecurityTest} and
 * {@code RateLimitSecurityTest}, both of which use {@code /actuator/health} merely as a cheap
 * permitAll route to burn rate-limit slots and assert nothing about authorization. This class is
 * that follow-up.</p>
 *
 * <p><b>The matcher ordering is the whole point.</b> {@code SecurityConfig} registers
 * {@code /actuator/health} inside the {@code permitAll()} group and {@code /actuator/**} under
 * {@code hasAnyRole('ADMIN','SUPER_ADMIN')} <i>afterwards</i>. Spring Security's
 * {@code authorizeHttpRequests} matches in registration order, so the public health check survives
 * the admin gate only because it is registered first. A reordering would silently either lock out
 * health checks or open {@code env} to everyone; all three assertions below are needed to catch
 * either direction.</p>
 *
 * <p><b>Tool substitution.</b> The row's {@code Tool} column says REST Assured, which is not a
 * dependency of this module. MockMvc through the REAL {@code SecurityConfig} filter chain is the
 * module's convention ({@code BillDisputeAmendmentIntegrationTest}) and exercises the identical
 * authorization path — the {@code 13_SECURITY_HARDENING} precedent. Column K is left unchanged.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ActuatorSecurityTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private Environment      env;

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "act" + userId, role, 1L);
    }

    /**
     * A unique synthetic source IP per request. {@code SecurityConfig.resolveClientIp} prefers
     * {@code X-Forwarded-For}, so this keeps the unauthenticated calls below out of the shared
     * {@code ip:127.0.0.1} rate-limit bucket that the rest of the suite consumes — otherwise a
     * late-running actuator test could be answered 429 instead of the status under test.
     */
    private static String freshIp() {
        return "198.51.100." + (1 + (int) (System.nanoTime() % 250));
    }

    @Test
    void envGatedToAdminOnly() throws Exception {
        // ── Pre-condition, asserted rather than assumed: env really IS exposed here ─────────
        // If management.endpoints.web.exposure.include stopped listing "env", every assertion
        // below would pass vacuously against a 404 and this test would be worthless.
        String exposed = env.getProperty("management.endpoints.web.exposure.include", "");
        assertTrue(exposed.contains("env"),
            "This row is only meaningful while /actuator/env is actually exposed. "
                + "management.endpoints.web.exposure.include = '" + exposed + "'");

        // ── Step 1: GET /actuator/health with no auth stays public ─────────────────────────
        MvcResult health = mvc.perform(get("/actuator/health")
                .header("X-Forwarded-For", freshIp()))
            .andReturn();
        String healthBody = health.getResponse().getContentAsString();
        assertEquals(200, health.getResponse().getStatus(),
            "/actuator/health must remain publicly reachable with no Authorization header — "
                + "load balancers and container health probes depend on it. Body: " + healthBody);
        assertTrue(healthBody.contains("\"status\""),
            "The public health endpoint must return a real health document. Body: " + healthBody);

        // ── Step 2: GET /actuator/env with a CLIENT JWT is Forbidden ───────────────────────
        MvcResult clientEnv = mvc.perform(get("/actuator/env")
                .header("Authorization", bearer(950016L, "CLIENT")))
            .andReturn();
        String clientEnvBody = clientEnv.getResponse().getContentAsString();
        assertEquals(403, clientEnv.getResponse().getStatus(),
            "A CLIENT token must be Forbidden from /actuator/env — 'authenticated' is not enough. "
                + "Body: " + clientEnvBody);

        // …and nothing leaks in the denial body. /actuator/env dumps the whole resolved
        // Environment, which includes the datasource URL and the property NAMES of every secret.
        for (String forbidden : new String[] {
                "propertySources", "systemEnvironment", "spring.datasource", "app.jwt.secret"}) {
            assertFalse(clientEnvBody.contains(forbidden),
                "The 403 must not leak any part of the environment dump ('" + forbidden
                    + "' appeared). Body: " + clientEnvBody);
        }

        // ── Step 2b: a TECHNICIAN — another ordinary authenticated role — is refused too ───
        // The row names CLIENT/TECHNICIAN as interchangeable examples of "any authenticated
        // user", which is exactly the bug that was fixed; both are asserted.
        MvcResult techEnv = mvc.perform(get("/actuator/env")
                .header("Authorization", bearer(950116L, "TECHNICIAN")))
            .andReturn();
        assertEquals(403, techEnv.getResponse().getStatus(),
            "A TECHNICIAN token must be Forbidden from /actuator/env. Body: "
                + techEnv.getResponse().getContentAsString());

        // ── Step 3: GET /actuator/env with an ADMIN JWT succeeds ───────────────────────────
        // The positive control. Without it, a blanket deny-all on /actuator/** would satisfy
        // steps 2/2b while having broken admin observability entirely.
        MvcResult adminEnv = mvc.perform(get("/actuator/env")
                .header("Authorization", bearer(950216L, "ADMIN")))
            .andReturn();
        String adminEnvBody = adminEnv.getResponse().getContentAsString();
        assertEquals(200, adminEnv.getResponse().getStatus(),
            "An ADMIN token must still reach /actuator/env — the endpoint must be gated, not "
                + "disabled. Body: " + adminEnvBody);
        assertTrue(adminEnvBody.contains("propertySources"),
            "The ADMIN response must be the real environment dump, not an empty 200. Body: "
                + adminEnvBody.substring(0, Math.min(400, adminEnvBody.length())));

        // ── …and the same gate applies to the other exposed non-health endpoint ────────────
        // The Resolution Log names env AND metrics; the matcher is /actuator/**, so metrics is
        // asserted too rather than assumed to follow.
        MvcResult clientMetrics = mvc.perform(get("/actuator/metrics")
                .header("Authorization", bearer(950316L, "CLIENT")))
            .andReturn();
        assertEquals(403, clientMetrics.getResponse().getStatus(),
            "A CLIENT token must be Forbidden from /actuator/metrics as well as /actuator/env. "
                + "Body: " + clientMetrics.getResponse().getContentAsString());

        MvcResult adminMetrics = mvc.perform(get("/actuator/metrics")
                .header("Authorization", bearer(950416L, "ADMIN")))
            .andReturn();
        assertEquals(200, adminMetrics.getResponse().getStatus(),
            "An ADMIN token must still reach /actuator/metrics. Body: "
                + adminMetrics.getResponse().getContentAsString());

        // ── An unauthenticated caller gets 401 (not 403) on the gated endpoints ────────────
        // Confirms the AuthenticationEntryPoint applies here too, i.e. actuator is inside the
        // same filter chain rather than a separate management chain with its own defaults.
        MvcResult anonEnv = mvc.perform(get("/actuator/env")
                .header("X-Forwarded-For", freshIp()))
            .andReturn();
        assertEquals(401, anonEnv.getResponse().getStatus(),
            "An unauthenticated request to /actuator/env must be 401 Unauthorized. Body: "
                + anonEnv.getResponse().getContentAsString());
    }
}
