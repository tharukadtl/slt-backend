package lk.slt.fieldops.security;

import lk.slt.fieldops.config.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AUTH-012 and AUTH-017 — JWT tampering and request rate limiting, through the real filter chain.
 *
 * <p><b>Harness.</b> {@code @SpringBootTest @AutoConfigureMockMvc @Transactional} with tokens
 * minted by the app's own {@link JwtTokenProvider}, matching
 * {@code BillDisputeAmendmentIntegrationTest}. MockMvc stands in for the sheet's REST Assured,
 * which is not a dependency of this module; both the JWT filter and the rate-limit filter are
 * servlet filters, so they run identically here.</p>
 *
 * <p>{@code JwtSecurityTest} already covers the payload/role-claim tampering case for SEC-001;
 * AUTH-012 additionally covers signature-segment tampering, and both variants are asserted here
 * so this class stands alone as the AUTH-012 artifact named by the sheet.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthSecurityTest {

    /** A protected route: GET /api/faults is @PreAuthorize ADMIN/SUPER_ADMIN. */
    private static final String PROTECTED_ROUTE = "/api/faults";

    /** AUTH-017: app.rate-limit.max-requests-per-minute defaults to 100. */
    private static final int RATE_LIMIT = 100;

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;

    private MvcResult getWithToken(String token) throws Exception {
        return mvc.perform(get(PROTECTED_ROUTE).header("Authorization", "Bearer " + token))
            .andReturn();
    }

    /** Rewrites the {@code role} claim inside an already-signed JWT, keeping the ORIGINAL signature. */
    private static String tamperPayloadRole(String token, String from, String to) {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "Expected a three-segment JWS, got: " + token);

        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tampered    = payloadJson.replace("\"role\":\"" + from + "\"", "\"role\":\"" + to + "\"");
        assertNotEquals(payloadJson, tampered,
            "Payload did not contain the expected role claim; nothing was tampered with: " + payloadJson);

        return parts[0] + "."
            + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tampered.getBytes(StandardCharsets.UTF_8))
            + "." + parts[2];
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTH-012 — a JWT whose signature or payload was altered must be rejected
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void tamperedJwt_returns401() throws Exception {
        String valid = jwt.createAccessToken(940012L, "victim", "CLIENT", 1L);
        assertTrue(jwt.validateToken(valid), "The baseline token must itself be valid");

        // ── Steps 1 & 2: append a byte to the signature segment ────────────────────────────
        String badSignature = valid + "x";
        assertFalse(jwt.validateToken(badSignature),
            "A token with a mutated signature must fail provider-level validation");

        MvcResult sigRes = getWithToken(badSignature);
        String sigBody = sigRes.getResponse().getContentAsString();
        assertFalse(sigBody.contains("\"faultNumber\""),
            "No fault data may be returned to a signature-tampered token. Body: " + sigBody);

        // ── Step 3 + Expected Result: 401 with an authentication error ────────────────────
        assertEquals(401, sigRes.getResponse().getStatus(),
            "AUTH-012 expects HTTP 401 for a signature-tampered token, got "
                + sigRes.getResponse().getStatus() + ". Body: " + sigBody);
        assertTrue(sigBody.toLowerCase().contains("token"),
            "The 401 body should name the invalid/missing token as the reason. Body: " + sigBody);

        // ── Step 4: escalate the role claim to SUPER_ADMIN, keep the original signature ────
        String escalated = tamperPayloadRole(valid, "CLIENT", "SUPER_ADMIN");
        assertFalse(jwt.validateToken(escalated),
            "A payload-tampered token must fail provider-level validation");

        MvcResult escRes = getWithToken(escalated);
        String escBody = escRes.getResponse().getContentAsString();
        assertFalse(escBody.contains("\"faultNumber\""),
            "Role escalation must not grant data access. Body: " + escBody);
        assertEquals(401, escRes.getResponse().getStatus(),
            "AUTH-012 expects HTTP 401 for a payload-tampered token, got "
                + escRes.getResponse().getStatus() + ". Body: " + escBody);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTH-017 — the 101st request inside one minute is throttled
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void rateLimiting_101stRequest_returns429() throws Exception {
        // A unique user id gives this test its own fixed window inside RateLimitService
        // ("user:<id>"), isolated from the shared "ip:127.0.0.1" window other tests consume.
        String bearer = "Bearer " + jwt.createAccessToken(940017L, "burstuser", "ADMIN", 1L);

        List<Integer> statuses = new ArrayList<>();
        MvcResult throttled = null;

        // /actuator/health is permitAll and cheap, and the rate-limit filter runs BEFORE
        // authorization, so each request still consumes a slot (same approach as
        // RateLimitSecurityTest).
        for (int i = 1; i <= RATE_LIMIT + 1; i++) {
            MvcResult res = mvc.perform(get("/actuator/health").header("Authorization", bearer))
                .andReturn();
            statuses.add(res.getResponse().getStatus());
            if (res.getResponse().getStatus() == 429 && throttled == null) {
                throttled = res;
            }
        }

        // ── Step 1: the first 100 requests are NOT throttled ──────────────────────────────
        List<Integer> firstHundred = statuses.subList(0, RATE_LIMIT);
        assertFalse(firstHundred.contains(429),
            "None of the first " + RATE_LIMIT + " requests inside the window may be throttled. "
                + "First 429 appeared at request #" + (firstHundred.indexOf(429) + 1));

        // ── Step 2: the 101st is HTTP 429 ─────────────────────────────────────────────────
        assertEquals(429, statuses.get(RATE_LIMIT).intValue(),
            "Request #" + (RATE_LIMIT + 1) + " must be rejected with HTTP 429. Statuses were: "
                + statuses);
        assertNotNull(throttled);
        assertTrue(throttled.getResponse().getContentAsString().toLowerCase().contains("rate limit"),
            "The 429 body should explain that the rate limit was exceeded. Body: "
                + throttled.getResponse().getContentAsString());

        // ── Step 3: a Retry-After header tells the client when to come back ───────────────
        // (Step 4 — "after 61s the request succeeds again" — is intentionally not automated:
        // RateLimitService's window is wall-clock based with no injectable clock, so honouring
        // it would mean a 61-second sleep in the suite. The window-rollover logic itself is
        // covered by RateLimitService's fixed-window reset in SEC-006.)
        String retryAfter = throttled.getResponse().getHeader("Retry-After");
        assertNotNull(retryAfter,
            "AUTH-017 expects a Retry-After header on the 429 response so the client knows when "
                + "the window resets. SecurityConfig's rate-limit filter writes only the status "
                + "and a JSON body, and sets no Retry-After header. Headers present: "
                + throttled.getResponse().getHeaderNames());
    }
}
