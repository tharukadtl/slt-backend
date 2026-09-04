package lk.slt.fieldops.security;

import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.config.RateLimitService;
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
 * SEC-006 — Rate limiting: a burst past the configured cap must return HTTP 429, and the 429
 * message must state the REAL configured limit rather than a stale hardcoded number.
 *
 * <p>The SEC-006 pre-condition ("rate limit overridden to a known small value for test
 * determinism") is met with {@code @SpringBootTest(properties = ...)} overriding
 * {@code app.rate-limit.max-requests-per-minute} to 5 — this is the property
 * {@link RateLimitService} binds, so the limiter really does cap at 5 for this context. Because the
 * property differs from the default, this class gets its own application context and cannot be
 * poisoned by (or poison) other tests' request counts.</p>
 *
 * <p>Requests carry a bearer token for a unique user id so the limiter keys on
 * {@code user:<id>} — a fresh window, isolated from any {@code ip:127.0.0.1} traffic. Harness
 * otherwise matches {@code BillDisputeAmendmentIntegrationTest}. MockMvc replaces the sheet's REST
 * Assured (not a dependency of this module); the limiter is a servlet filter, so it runs
 * identically under MockMvc.</p>
 *
 * <p><b>API-014 (11_API_ENDPOINTS) also lives in this class</b>, per its own Automation Mapping
 * ({@code RateLimitSecurityTest::preAuthEndpointsThrottled}). It is a genuinely different case from
 * SEC-006 above, not a duplicate: SEC-006 deliberately carries a Bearer token so the limiter keys on
 * {@code user:<id>}, and asserts the 429 <i>message</i>. API-014 deliberately carries NO token, so
 * the limiter must key on {@code ip:<address>} instead, and asserts that the pre-auth surface
 * (login/OTP — the endpoints a brute-force or credential-stuffing attack actually targets) is capped
 * at all. Neither test can substitute for the other: a limiter that only counted authenticated
 * traffic would pass SEC-006 and fail API-014. API-014 makes no claim about the message text, so it
 * does not re-assert (or re-log) SEC-006's already-open hardcoded-message finding.</p>
 */
@SpringBootTest(properties = "app.rate-limit.max-requests-per-minute=" + RateLimitSecurityTest.LIMIT)
@AutoConfigureMockMvc
@Transactional
class RateLimitSecurityTest {

    /** SEC-006 Test Data: "Override limit=5/min, send 10 requests". */
    static final int LIMIT  = 5;
    static final int BURST  = 10;

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;

    @Test
    void messageReflectsRealLimit() throws Exception {
        // Unique user id -> its own fixed window inside the limiter.
        String bearer = "Bearer " + jwt.createAccessToken(960601L, "ratelimituser", "ADMIN", 1L);

        List<Integer> statuses = new ArrayList<>();
        String firstThrottledBody = null;

        for (int i = 0; i < BURST; i++) {
            // /actuator/health is permitAll and cheap; the rate-limit filter runs BEFORE
            // authorization, so every request here still consumes a slot.
            MvcResult res = mvc.perform(get("/actuator/health")
                    .header("Authorization", bearer))
                .andReturn();
            statuses.add(res.getResponse().getStatus());
            if (res.getResponse().getStatus() == 429 && firstThrottledBody == null) {
                firstThrottledBody = res.getResponse().getContentAsString();
            }
        }

        // ── Step 2: HTTP 429 fires once the burst passes the configured cap ─────────────────
        assertTrue(statuses.contains(429),
            "Sending " + BURST + " requests against a limit of " + LIMIT
                + " must produce at least one HTTP 429. Statuses were: " + statuses);
        assertEquals(LIMIT, statuses.indexOf(429),
            "The first " + LIMIT + " requests should pass and request #" + (LIMIT + 1)
                + " should be the first 429. Statuses were: " + statuses);
        assertNotNull(firstThrottledBody, "A 429 response body was expected");

        // ── Step 3: the message must state the REAL configured limit, not a hardcoded value ──
        assertTrue(firstThrottledBody.contains(String.valueOf(LIMIT)),
            "SEC-006: the 429 message must reflect the REAL configured limit (" + LIMIT
                + " requests/minute). Actual body: " + firstThrottledBody);
        assertFalse(firstThrottledBody.contains("100"),
            "SEC-006: the 429 message still reports the stale hardcoded '100' instead of the "
                + "configured limit of " + LIMIT + ". Actual body: " + firstThrottledBody);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // API-014 — the UNAUTHENTICATED pre-auth surface is throttled too, keyed on IP
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * API-014: 10 rapid {@code POST /api/auth/login} calls from one source IP with no Bearer token.
     * Requests 1..LIMIT must reach the real login logic (HTTP 400 on bad credentials — the
     * {@code GlobalExceptionHandler.handleRuntime} mapping); requests LIMIT+1..BURST must be 429.
     *
     * <p><b>Why the IP is supplied via {@code X-Forwarded-For}.</b> {@code SecurityConfig}'s
     * {@code resolveClientIp} prefers {@code X-Forwarded-For} over {@code getRemoteAddr()}, so a
     * unique synthetic IP per run gives this test its own fixed window inside
     * {@link lk.slt.fieldops.config.RateLimitService} — otherwise it would share the
     * {@code ip:127.0.0.1} bucket with every other unauthenticated request in the suite and its
     * "first N succeed" assertion would be order-dependent. This is the unauthenticated twin of the
     * unique-user-id isolation SEC-006 uses above.</p>
     */
    @Test
    void preAuthEndpointsThrottled() throws Exception {
        // A documentation-range (RFC 5737) address that no other test can collide with.
        String sourceIp = "198.51.100." + (1 + (int) (System.nanoTime() % 250));

        List<Integer> statuses = new ArrayList<>();
        String firstAllowedBody   = null;
        String firstThrottledBody = null;

        for (int i = 0; i < BURST; i++) {
            MvcResult res = mvc.perform(post("/api/auth/login")
                    .header("X-Forwarded-For", sourceIp)
                    .contentType(MediaType.APPLICATION_JSON)
                    // Well-formed body with deliberately wrong credentials, so a non-throttled
                    // request genuinely reaches AuthService.login rather than being short-circuited
                    // by @Valid — which would make "reached real login logic" unfalsifiable.
                    .content("{\"username\":\"no-such-user-" + sourceIp
                        + "\",\"password\":\"WrongPassword1!\",\"deviceInfo\":\"junit\"}"))
                .andReturn();

            int status = res.getResponse().getStatus();
            statuses.add(status);
            if (status != 429 && firstAllowedBody == null) {
                firstAllowedBody = res.getResponse().getContentAsString();
            }
            if (status == 429 && firstThrottledBody == null) {
                firstThrottledBody = res.getResponse().getContentAsString();
            }
        }

        // ── Step 2: requests 1..LIMIT reach the real login logic (400 on bad creds) ─────────
        List<Integer> allowed = statuses.subList(0, LIMIT);
        assertFalse(allowed.contains(429),
            "None of the first " + LIMIT + " unauthenticated login attempts may be throttled. "
                + "Statuses were: " + statuses);
        for (int i = 0; i < LIMIT; i++) {
            assertEquals(400, statuses.get(i).intValue(),
                "Unauthenticated login attempt #" + (i + 1) + " with bad credentials must reach the "
                    + "real login logic and be rejected with HTTP 400, not throttled and not 401/403. "
                    + "Statuses were: " + statuses);
        }

        assertNotNull(firstAllowedBody, "A non-throttled login response was expected");
        assertFalse(firstAllowedBody.contains("\"error\":\"Validation Failed\""),
            "The probe body must be well-formed enough to reach AuthService.login — a "
                + "@Valid rejection would mean this test never exercised the login path at all. "
                + "Body: " + firstAllowedBody);
        assertFalse(firstAllowedBody.contains("\"accessToken\""),
            "A bad-credential login must not issue a token. Body: " + firstAllowedBody);

        // ── Step 3: requests LIMIT+1..BURST are HTTP 429 ───────────────────────────────────
        assertEquals(LIMIT, statuses.indexOf(429),
            "Request #" + (LIMIT + 1) + " from the same unauthenticated source IP must be the first "
                + "throttled one. If NO 429 appears at all, the rate limiter is keying only on "
                + "authenticated users and the pre-auth surface (login/OTP) is unprotected against "
                + "brute force. Statuses were: " + statuses);
        for (int i = LIMIT; i < BURST; i++) {
            assertEquals(429, statuses.get(i).intValue(),
                "Every request after the cap must stay throttled — request #" + (i + 1)
                    + " was not. Statuses were: " + statuses);
        }
        assertNotNull(firstThrottledBody, "A 429 response body was expected");
        assertTrue(firstThrottledBody.toLowerCase().contains("rate limit"),
            "The 429 body should explain that the rate limit was exceeded. Body: "
                + firstThrottledBody);

        // ── Expected Result: keying is per-IP, so a DIFFERENT source IP is unaffected ───────
        // Without this, a limiter that had simply thrown a global switch would pass everything
        // above while actually being a denial-of-service on all login traffic.
        MvcResult otherIp = mvc.perform(post("/api/auth/login")
                .header("X-Forwarded-For", "203.0.113." + (1 + (int) (System.nanoTime() % 250)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"other-ip-user\",\"password\":\"WrongPassword1!\"}"))
            .andReturn();
        assertNotEquals(429, otherIp.getResponse().getStatus(),
            "Throttling one source IP must not throttle a different one — the limiter must key on "
                + "the client IP for unauthenticated traffic, not apply a global cap. Body: "
                + otherIp.getResponse().getContentAsString());
    }
}
