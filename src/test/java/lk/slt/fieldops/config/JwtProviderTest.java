package lk.slt.fieldops.config;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUTH-010 — a JWT access token must stop validating once its 30-minute lifetime has elapsed,
 * and must still validate before that.
 *
 * <p><b>Harness.</b> Plain JUnit 5, no Spring context and no database — the DB-free unit-test
 * convention already used by {@code CheckInRequestValidationTest}. The class under test,
 * {@link JwtTokenProvider}, receives its two {@code @Value} fields through
 * {@link ReflectionTestUtils}, which is exactly what the container would inject at runtime.</p>
 *
 * <p><b>Deviation from the sheet's Assertion Code.</b> The row's draft mocks a {@code Clock}
 * ({@code when(clock.instant()).thenReturn(...)}), but {@link JwtTokenProvider} has no injectable
 * {@code Clock} — it calls {@code new Date()} directly. Adding one would be a production-code
 * change, which is out of scope here. The clock advance is therefore simulated from the other
 * side: tokens are minted with the SAME signing key and the SAME 30-minute lifetime, but with
 * their {@code iat} back-dated by 29 / 31 minutes. A token issued 31 minutes ago with a 30-minute
 * lifetime is byte-for-byte identical to what "advance the clock 31 minutes" would produce, so the
 * expiry boundary is genuinely exercised, not stubbed.</p>
 */
class JwtProviderTest {

    /** HS256 via {@code Keys.hmacShaKeyFor} requires >= 256 bits of key material. */
    private static final String TEST_SECRET =
        "auth010-unit-test-signing-secret-0123456789abcdef";

    private static final long THIRTY_MINUTES_MS = Duration.ofMinutes(30).toMillis();

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", TEST_SECRET);
        ReflectionTestUtils.setField(provider, "accessTokenExpiryMs", THIRTY_MINUTES_MS);
    }

    /**
     * Mints a token with the provider's own claim shape and signing key, but back-dated: issued
     * {@code minutesAgo} minutes ago with the standard 30-minute lifetime. Equivalent to holding a
     * token while the wall clock advances.
     */
    private static String tokenIssuedMinutesAgo(long minutesAgo) {
        Date issuedAt = new Date(System.currentTimeMillis() - Duration.ofMinutes(minutesAgo).toMillis());
        Date expiry   = new Date(issuedAt.getTime() + THIRTY_MINUTES_MS);
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("77")
                .claim("username", "tech77")
                .claim("role", "TECHNICIAN")
                .claim("opmcId", 1L)
                .issuedAt(issuedAt)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * Reads {@code app.jwt.access-token-expiry-ms} out of the real {@code application.yml} on the
     * test classpath and resolves the literal default inside its {@code ${ENV:default}}
     * placeholder — i.e. the lifetime any deployment gets unless it overrides the env var.
     */
    private static long configuredAccessTokenExpiryMsDefault() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertFalse(sources.isEmpty(), "application.yml was not found on the test classpath");

        Object raw = sources.get(0).getProperty("app.jwt.access-token-expiry-ms");
        assertNotNull(raw, "app.jwt.access-token-expiry-ms is not declared in application.yml");

        Matcher m = Pattern.compile("\\$\\{[^:}]+:(\\d+)}").matcher(String.valueOf(raw));
        assertTrue(m.matches(),
            "Expected a ${ENV:default} placeholder for app.jwt.access-token-expiry-ms, got: " + raw);
        return Long.parseLong(m.group(1));
    }

    @Test
    void validateToken_expiredAfter30min_returnsFalse() throws Exception {
        // ── Step 1: a token generated right now is valid ────────────────────────────────────
        String fresh = provider.createAccessToken(77L, "tech77", "TECHNICIAN", 1L);
        assertTrue(provider.validateToken(fresh),
            "A freshly issued access token must validate");
        assertEquals(77L, provider.getUserIdFromToken(fresh));

        // ── Step 4: only 29 minutes elapsed -> still inside the 30-minute window -> true ────
        String at29Minutes = tokenIssuedMinutesAgo(29);
        assertTrue(provider.validateToken(at29Minutes),
            "A token issued 29 minutes ago must STILL validate — the access-token lifetime is "
                + "30 minutes, so it has ~1 minute left");

        // ── Steps 2 & 3: clock advanced 31 minutes -> past expiry -> false ──────────────────
        String at31Minutes = tokenIssuedMinutesAgo(31);
        assertFalse(provider.validateToken(at31Minutes),
            "A token issued 31 minutes ago must NOT validate — it is 1 minute past its "
                + "30-minute expiry");

        // validateToken() swallows the cause, so assert the underlying reason is expiry
        // (not a signature/parse problem, which would make the test pass for the wrong reason).
        assertThrows(ExpiredJwtException.class,
            () -> provider.validateAndParseClaims(at31Minutes),
            "The 31-minute-old token must fail specifically with ExpiredJwtException");

        // ── Contract: the app really is configured for a 30-minute access token ─────────────
        long configuredMs = configuredAccessTokenExpiryMsDefault();
        assertEquals(THIRTY_MINUTES_MS, configuredMs,
            "AUTH-010 expects a 30-minute access-token lifetime; application.yml's default for "
                + "app.jwt.access-token-expiry-ms is " + configuredMs + " ms ("
                + Duration.ofMillis(configuredMs).toMinutes() + " minutes)");
    }
}
