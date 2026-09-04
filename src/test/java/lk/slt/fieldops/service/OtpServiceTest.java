package lk.slt.fieldops.service;

import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.OtpVerifyRequest;
import lk.slt.fieldops.entity.OtpRecord;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.OtpRecordRepository;
import lk.slt.fieldops.repository.RefreshTokenRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * AUTH-005 — repeated wrong OTP codes must lock the account: the 3rd wrong attempt is expected to
 * be rejected as a LOCKOUT (not just another "wrong code"), and the lock must last 15 minutes.
 *
 * <p><b>Where the logic actually lives.</b> The sheet maps this row to {@code OtpServiceTest}, but
 * this module has no {@code OtpService} — OTP verification, attempt counting and account lockout
 * all live in {@link AuthService#verifyOtp}. The class name from the mapping is kept; the subject
 * under test is {@code AuthService}. Harness is {@code @ExtendWith(MockitoExtension.class)} with
 * {@code @Mock} repositories and {@code @InjectMocks}, matching
 * {@code PaymentServiceDisputeAmendTest} — the module's repo-mocked service-unit convention.</p>
 *
 * <p><b>Why the policy numbers are read from application.yml.</b> {@code AuthService}'s
 * {@code max-attempts} / {@code account-lock-minutes} arrive via {@code @Value}, so a unit test
 * must supply them itself. Hardcoding 5 and 30 here would make the test tautological — it would
 * prove only that the mechanism honours whatever numbers the test itself chose, and would stay
 * green no matter how the application is configured. Instead the REAL configured values are read
 * from {@code application.yml} on the classpath and injected, so this test verifies (a) that the
 * lockout mechanism works, and then (b) that the configured policy is the one AUTH-005
 * specifies.</p>
 *
 * <p><b>Exception types.</b> The row's draft expects dedicated {@code InvalidOtpException} /
 * {@code AccountLockedException} types. Neither exists in this codebase — both outcomes are a
 * plain {@code RuntimeException} distinguished only by its message. Introducing those types would
 * be a production-code change, so the assertions below discriminate the two outcomes by the
 * observable state change instead (was {@code accountLockedUntil} written?), which is the
 * behaviour the row is really about.</p>
 */
@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    /** AUTH-005 Expected Result: lockout on the 3rd wrong attempt, for 15 minutes. */
    private static final int SPEC_LOCK_ON_ATTEMPT = 3;
    private static final int SPEC_LOCK_MINUTES    = 15;

    private static final String PHONE       = "0717730773";
    private static final String CORRECT_OTP = "999999";
    private static final String WRONG_OTP   = "123456";
    private static final String HASHED_OTP  = "$2a$12$hashOfTheCorrectCode999999................";

    @Mock private OtpRecordRepository    otpRepo;
    @Mock private RefreshTokenRepository rtRepo;
    @Mock private UserRepository         userRepo;
    @Mock private UserService            userService;
    @Mock private JwtTokenProvider       jwt;
    @Mock private PasswordEncoder        encoder;

    @InjectMocks private AuthService authService;

    private User      user;
    private OtpRecord otpRecord;

    private int configuredMaxAttempts;
    private int configuredLockMinutes;

    /** Reads a plain int under the {@code app.} tree from the real application.yml. */
    private static int configuredInt(String key) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            assertFalse(sources.isEmpty(), "application.yml was not found on the test classpath");
            Object raw = sources.get(0).getProperty(key);
            assertNotNull(raw, key + " is not declared in application.yml");
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception e) {
            throw new AssertionError("Could not read " + key + " from application.yml", e);
        }
    }

    @BeforeEach
    void setUp() {
        configuredMaxAttempts = configuredInt("app.otp.max-attempts");
        configuredLockMinutes = configuredInt("app.otp.account-lock-minutes");

        // Inject the application's own OTP policy — exactly what Spring would bind at runtime.
        ReflectionTestUtils.setField(authService, "otpMaxAttempts",   configuredMaxAttempts);
        ReflectionTestUtils.setField(authService, "accountLockMinutes", configuredLockMinutes);

        user = new User();
        user.setId(4242L);
        user.setUsername("client_0717730773");
        user.setFullName("Test Client");
        user.setPhone(PHONE);
        user.setRole(User.Role.CLIENT);
        user.setIsActive(true);
        user.setFailedLoginAttempts(0);
        user.setAccountLockedUntil(null);

        // A live, unused, unexpired OTP whose (hashed) code is CORRECT_OTP.
        otpRecord = OtpRecord.builder()
                .phoneNumber(PHONE)
                .otpCode(HASHED_OTP)
                .purpose(OtpRecord.OtpPurpose.CLIENT_LOGIN)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        otpRecord.setId(1L);
        otpRecord.setAttemptCount(0);
        otpRecord.setIsUsed(false);
    }

    private OtpVerifyRequest wrongCodeRequest() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setPhoneNumber(PHONE);
        req.setOtp(WRONG_OTP);
        req.setDeviceInfo("junit");
        return req;
    }

    @Test
    void verifyOtp_fiveWrongAttempts_locksAccount() {
        when(userRepo.findByPhoneOrPhoneNumber(PHONE)).thenReturn(Optional.of(user));
        when(otpRepo.findLatestValidOtp(eq(PHONE), any(LocalDateTime.class)))
                .thenReturn(Optional.of(otpRecord));
        // The submitted code never matches the stored (hashed) one — every attempt is wrong.
        when(encoder.matches(anyString(), anyString())).thenReturn(false);

        List<String> messages     = new ArrayList<>();
        int          lockedOnAttempt = -1;
        LocalDateTime lockObserved   = null;
        LocalDateTime lockedUntil    = null;

        // ── Steps 1 & 2: submit the wrong code five times in a row ──────────────────────────
        for (int i = 1; i <= SPEC_LOCK_ON_ATTEMPT; i++) {
            final int attempt = i;
            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.verifyOtp(wrongCodeRequest()),
                "Wrong-OTP attempt #" + attempt + " must be rejected, not accepted");
            messages.add(ex.getMessage());

            if (lockedOnAttempt == -1 && user.getAccountLockedUntil() != null) {
                // First attempt that actually WROTE a lockout — the "AccountLockedException"
                // equivalent (see class javadoc: no dedicated exception type exists).
                lockedOnAttempt = i;
                lockObserved    = LocalDateTime.now();
                lockedUntil     = user.getAccountLockedUntil();
                assertTrue(ex.getMessage().toLowerCase().contains("too many failed attempts"),
                    "The locking attempt (#" + i + ") must say the account is locked out. "
                        + "Actual message: " + ex.getMessage());
            } else if (lockedOnAttempt == -1) {
                // Pre-lock attempts are the "InvalidOtpException" equivalent.
                assertTrue(ex.getMessage().contains("Incorrect OTP"),
                    "Attempt #" + i + " is before the lockout threshold, so it must be reported "
                        + "as a wrong code, not a lockout. Actual message: " + ex.getMessage());
            }
        }

        // ── Mechanism: a lockout really is written, with a real future expiry ───────────────
        assertNotEquals(-1, lockedOnAttempt,
            "Five consecutive wrong OTPs must lock the account (accountLockedUntil must be set). "
                + "Messages seen: " + messages);
        assertNotNull(lockedUntil);
        assertTrue(lockedUntil.isAfter(lockObserved),
            "accountLockedUntil must be in the future, was " + lockedUntil);
        assertTrue(user.getFailedLoginAttempts() > 0,
            "The failed-attempt counter must have been incremented on lockout");

        // ── Steps 1-3 / Expected Result: attempts 1-4 are wrong-code, the 5th is the lockout ─
        assertEquals(SPEC_LOCK_ON_ATTEMPT, lockedOnAttempt,
            "AUTH-005 expects attempts 1-" + (SPEC_LOCK_ON_ATTEMPT - 1)
                + " to be rejected as a wrong code and attempt #" + SPEC_LOCK_ON_ATTEMPT
                + " to lock the account; app.otp.max-attempts is configured to "
                + configuredMaxAttempts + ", so the lock actually fires on attempt #"
                + lockedOnAttempt + ". Messages seen: " + messages);

        // ── Step 4 / Expected Result: the lock lasts 15 minutes ────────────────────────────
        // lockedUntil was computed inside lockAccount() a moment before lockObserved was
        // captured here, so the raw duration is SPEC_LOCK_MINUTES minus a sub-second gap that
        // can truncate to zero (same-millisecond clock reads are common on this platform) —
        // hence the tolerant range instead of a single toMinutes()+1 value, which overshoots
        // by a minute whenever that gap rounds away to nothing.
        long lockMinutes = Duration.between(lockObserved, lockedUntil).toMinutes();
        assertTrue(lockMinutes == SPEC_LOCK_MINUTES || lockMinutes == SPEC_LOCK_MINUTES - 1,
            "AUTH-005 expects the account to stay locked for " + SPEC_LOCK_MINUTES
                + " minutes; app.otp.account-lock-minutes is configured to "
                + configuredLockMinutes + ", so the lock actually lasts ~" + lockMinutes
                + "-" + (lockMinutes + 1) + " minutes");
    }
}
