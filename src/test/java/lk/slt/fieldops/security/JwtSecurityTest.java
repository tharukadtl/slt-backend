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
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SEC-001 — JWT tampering: a role claim modified after signing must be rejected.
 *
 * <p>Follows the established fieldops integration harness (see
 * {@code BillDisputeAmendmentIntegrationTest}): {@code @SpringBootTest @AutoConfigureMockMvc
 * @Transactional}, tokens minted with the app's own {@link JwtTokenProvider}, every request sent
 * through the REAL {@code SecurityConfig} filter chain. The spreadsheet's Tool column says
 * "REST Assured", but REST Assured is NOT a dependency of this module's {@code pom.xml} — MockMvc
 * against the real filter chain is the module's existing convention and exercises exactly the same
 * code path (signature verification happens inside the filter, not in the HTTP layer).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JwtSecurityTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;

    /** A protected admin route — GET /api/users is @PreAuthorize SUPER_ADMIN/ADMIN/TEAM_LEAD. */
    private static final String PROTECTED_ADMIN_ROUTE = "/api/users";

    /**
     * Rewrites the {@code role} claim in an already-signed JWT's payload segment and re-assembles
     * the token with the ORIGINAL signature — i.e. exactly the "decode, edit, re-encode without a
     * valid signature" attack the test case describes.
     */
    private static String tamperRoleClaim(String token, String newRole) {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "Expected a three-segment JWS, got: " + token);

        String payloadJson = new String(
            Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tamperedJson = payloadJson.replace("\"role\":\"CLIENT\"", "\"role\":\"" + newRole + "\"");
        assertNotEquals(payloadJson, tamperedJson,
            "Payload did not contain the expected CLIENT role claim; nothing was tampered with. "
                + "Payload was: " + payloadJson);

        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + tamperedPayload + "." + parts[2];
    }

    @Test
    void tamperedRoleClaimRejected() throws Exception {
        // ── Arrange: a genuinely-valid CLIENT token, then escalate its role claim to SUPER_ADMIN ──
        String validClientToken = jwt.createAccessToken(4242L, "client4242", "CLIENT", 1L);
        String tamperedJwt      = tamperRoleClaim(validClientToken, "SUPER_ADMIN");

        assertNotEquals(validClientToken, tamperedJwt, "Tampering produced an identical token");
        assertFalse(jwt.validateToken(tamperedJwt),
            "Signature validation must reject the tampered token at the provider level");

        // ── Act ──
        MvcResult res = mvc.perform(get(PROTECTED_ADMIN_ROUTE)
                .header("Authorization", "Bearer " + tamperedJwt))
            .andReturn();

        int    status = res.getResponse().getStatus();
        String body   = res.getResponse().getContentAsString();

        // ── Assert (primary security property): role escalation is impossible ──
        assertTrue(status >= 400,
            "Tampered SUPER_ADMIN token must NOT be granted access to " + PROTECTED_ADMIN_ROUTE
                + " — got HTTP " + status + ". Body: " + body);
        assertFalse(body.contains("\"username\""),
            "No user list may be returned to a tampered token. Body: " + body);

        // ── Assert (spec contract): SEC-001 Expected Result is HTTP 401 'Invalid token' ──
        assertEquals(401, status,
            "SEC-001 expects HTTP 401 for a tampered token, got " + status
                + ". (The request IS rejected, so no privilege escalation occurs, but the app "
                + "returns the Spring Security default entry-point status instead of 401.) Body: "
                + body);
    }
}
