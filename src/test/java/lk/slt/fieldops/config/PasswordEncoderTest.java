package lk.slt.fieldops.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUTH-018 — the application's password encoder must be BCrypt at cost factor 12.
 *
 * <p><b>Harness.</b> Plain JUnit 5, no Spring context and no database (the DB-free unit-test
 * convention of {@code CheckInRequestValidationTest}). The encoder under test is obtained from
 * the REAL {@link SecurityConfig#passwordEncoder()} factory method rather than by constructing a
 * {@code new BCryptPasswordEncoder(12)} here — asserting on a locally-constructed encoder would
 * be tautological and would keep passing even if the application's bean were downgraded. Both
 * constructor arguments are irrelevant to {@code passwordEncoder()} (it touches neither), so
 * nulls are safe and keep this a genuine unit test.</p>
 */
class PasswordEncoderTest {

    /** AUTH-018 Test Data. */
    private static final String PASSWORD = "Tech@Pass1";

    @Test
    void bcrypt_cost12_encodesCorrectly() {
        PasswordEncoder encoder = new SecurityConfig(null, null).passwordEncoder();

        // ── Step 1: the hash advertises BCrypt version 2a at cost 12 ───────────────────────
        String encoded = encoder.encode(PASSWORD);
        assertNotNull(encoded, "encode() must not return null");
        assertTrue(encoded.startsWith("$2a$12$"),
            "AUTH-018: the application's PasswordEncoder bean must be BCrypt at cost 12, so the "
                + "hash must start with '$2a$12$'. Actual prefix: "
                + encoded.substring(0, Math.min(7, encoded.length())));

        // ── Step 2: canonical BCrypt output length ─────────────────────────────────────────
        assertEquals(60, encoded.length(),
            "A BCrypt hash is always 60 characters. Actual: " + encoded);

        // ── Step 3: the correct password verifies ─────────────────────────────────────────
        assertTrue(encoder.matches(PASSWORD, encoded),
            "matches() must accept the original password");

        // ── Step 4: a wrong password does not ─────────────────────────────────────────────
        assertFalse(encoder.matches("Wrong", encoded),
            "matches() must reject an incorrect password");

        // Per-hash salting: the same input must never produce the same hash twice, otherwise the
        // cost factor would be irrelevant (identical passwords would be trivially correlatable).
        assertNotEquals(encoded, encoder.encode(PASSWORD),
            "Two encodings of the same password must differ — BCrypt salts every hash");
    }
}
