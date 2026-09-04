package lk.slt.fieldops.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.dto.RefreshTokenRequest;
import lk.slt.fieldops.entity.RefreshToken;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.RefreshTokenRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-004 — Refresh tokens are single-use: replaying an already-consumed refresh token must be
 * detected and rejected, and the token must be marked revoked in the DB.
 *
 * <p>Harness matches {@code BillDisputeAmendmentIntegrationTest} — real filter chain, real MySQL,
 * {@code @Transactional} rollback. {@code /api/auth/**} is {@code permitAll}, so no bearer token is
 * needed on these calls. MockMvc replaces the sheet's REST Assured (not a dependency here).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RefreshTokenSecurityTest {

    @Autowired private MockMvc                mvc;
    @Autowired private UserRepository         userRepo;
    @Autowired private RefreshTokenRepository rtRepo;
    @Autowired private ObjectMapper           json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private User newClient() {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername("rtuser" + n);
        u.setPasswordHash("x");
        u.setFirstName("Refresh");
        u.setLastName("User");
        u.setFullName("Refresh User " + n);
        u.setRole(User.Role.CLIENT);
        u.setOpmcId(1L);
        return userRepo.save(u);
    }

    private String refreshBody(String token) throws Exception {
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(token);
        return json.writeValueAsString(req);
    }

    private MvcResult callRefresh(String token) throws Exception {
        return mvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(token)))
            .andReturn();
    }

    @Test
    void reuseRejected() throws Exception {
        User user = newClient();

        String tokenValue = UUID.randomUUID().toString();
        rtRepo.save(RefreshToken.builder()
            .userId(user.getId())
            .token(tokenValue)
            .expiresAt(LocalDateTime.now().plusDays(7))
            .deviceInfo("junit-sec-004")
            .build());
        rtRepo.flush();
        em.clear();

        // ── Step 1: first use succeeds and issues a new access token ────────────────────────
        MvcResult first = callRefresh(tokenValue);
        String firstBody = first.getResponse().getContentAsString();
        assertEquals(200, first.getResponse().getStatus(),
            "First use of a valid refresh token must succeed. Body: " + firstBody);

        JsonNode issued = json.readTree(firstBody);
        assertTrue(issued.hasNonNull("accessToken") && !issued.get("accessToken").asText().isBlank(),
            "A new access token must be issued on first use. Body: " + firstBody);
        assertNotEquals(tokenValue, issued.path("refreshToken").asText(),
            "The refresh token must be ROTATED, not handed back unchanged");

        rtRepo.flush();
        em.clear();

        // ── Step 2: replay the SAME refresh token ───────────────────────────────────────────
        MvcResult replay = callRefresh(tokenValue);
        int    replayStatus = replay.getResponse().getStatus();
        String replayBody   = replay.getResponse().getContentAsString();

        // Primary security property: the replay must NOT mint another access token.
        assertNotEquals(200, replayStatus,
            "Reusing a consumed refresh token must be rejected, not silently accepted. Body: "
                + replayBody);
        assertFalse(replayBody.contains("\"accessToken\""),
            "No access token may be issued for a replayed refresh token. Body: " + replayBody);

        // DB state: the consumed token is revoked.
        RefreshToken consumed = rtRepo.findByToken(tokenValue).orElseThrow(
            () -> new AssertionError("The used refresh token row disappeared instead of being revoked"));
        assertNotNull(consumed.getRevokedAt(),
            "The used refresh token must be marked revoked (revoked=true) in the DB");
        assertFalse(consumed.isValid(), "A revoked refresh token must not report itself as valid");

        // ── SEC-004 spec contract: HTTP 401 ────────────────────────────────────────────────
        assertEquals(401, replayStatus,
            "SEC-004 expects HTTP 401 on refresh-token reuse, got " + replayStatus
                + ". (Reuse IS detected and rejected — AuthService throws \"Refresh token expired "
                + "or revoked\" — but GlobalExceptionHandler maps a plain RuntimeException to 400, "
                + "so the client sees a generic Bad Request rather than an auth failure.) Body: "
                + replayBody);
    }
}
