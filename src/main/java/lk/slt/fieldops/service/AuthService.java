package lk.slt.fieldops.auth.service;

import lk.slt.fieldops.auth.dto.AuthResponse;
import lk.slt.fieldops.auth.dto.OtpVerifyRequest;
import lk.slt.fieldops.auth.dto.PasswordLoginRequest;
import lk.slt.fieldops.auth.entity.OtpRecord;
import lk.slt.fieldops.auth.entity.RefreshToken;
import lk.slt.fieldops.auth.repository.OtpRecordRepository;
import lk.slt.fieldops.auth.repository.RefreshTokenRepository;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.user.entity.User;
import lk.slt.fieldops.user.repository.UserRepository;
import lk.slt.fieldops.user.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * AuthService — UPDATED with User module integration.
 *
 * Changes from original:
 *   1. Injected UserRepository and UserService
 *   2. verifyOtp() now looks up user by phone
 *   3. passwordLogin() now validates against User entity with BCrypt
 *   4. refreshToken() fetches fresh user data
 *   5. Calls userService.updateLastLogin() after successful login
 *   6. NO MORE placeholder users
 */
@Service
public class AuthService {

    private static final Logger log = Logger.getLogger(AuthService.class.getName());

    private final OtpRecordRepository    otpRepo;
    private final RefreshTokenRepository rtRepo;
    private final UserRepository         userRepo;
    private final UserService            userService;
    private final JwtTokenProvider       jwt;
    private final PasswordEncoder        encoder;

    public AuthService(OtpRecordRepository otpRepo,
                       RefreshTokenRepository rtRepo,
                       UserRepository userRepo,
                       UserService userService,
                       JwtTokenProvider jwt,
                       PasswordEncoder encoder) {
        this.otpRepo     = otpRepo;
        this.rtRepo      = rtRepo;
        this.userRepo    = userRepo;
        this.userService = userService;
        this.jwt         = jwt;
        this.encoder     = encoder;
    }

    @Value("${app.jwt.access-token-expiry-ms}")  private long    accessTokenExpiryMs;
    @Value("${app.jwt.refresh-token-expiry-ms}") private long    refreshTokenExpiryMs;
    @Value("${app.otp.expiry-minutes}")          private int     otpExpiryMinutes;
    @Value("${app.otp.max-attempts}")            private int     otpMaxAttempts;
    @Value("${app.sms.enabled}")                 private boolean smsEnabled;

    // ══════════════════════════════════════════════════════════════════════════
    // 1. SEND OTP (for CLIENT login via phone)
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public void sendOtp(String phoneNumber) {
        long recent = otpRepo.countRecentOtps(phoneNumber, LocalDateTime.now().minusHours(1));
        if (recent >= 3) {
            throw new RuntimeException("Too many OTP requests. Please wait 1 hour.");
        }

        String code = String.valueOf(100000 + new Random().nextInt(900000));

        OtpRecord r = OtpRecord.builder()
                .phoneNumber(phoneNumber)
                .otpCode(code)
                .purpose(OtpRecord.OtpPurpose.CLIENT_LOGIN)
                .expiresAt(LocalDateTime.now().plusMinutes(otpExpiryMinutes))
                .build();
        otpRepo.save(r);

        if (smsEnabled) {
            // TODO: Integrate SMS gateway (Twilio, Dialog, etc.)
            log.info("SMS sent to " + phoneNumber + " with OTP: " + code);
        } else {
            log.warning("========================================");
            log.warning("  DEV OTP for " + phoneNumber + " : " + code);
            log.warning("========================================");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. VERIFY OTP (CLIENT login) — NOW WITH USER LOOKUP
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public AuthResponse verifyOtp(OtpVerifyRequest request) {
        // Find latest valid OTP
        OtpRecord r = otpRepo.findLatestValidOtp(request.getPhoneNumber(), LocalDateTime.now())
                .orElseThrow(() -> new RuntimeException("No valid OTP found. Please request a new OTP."));

        // Check attempt count
        r.setAttemptCount(r.getAttemptCount() + 1);
        if (r.getAttemptCount() > otpMaxAttempts) {
            otpRepo.save(r);
            throw new RuntimeException("Too many failed attempts. Please request a new OTP.");
        }

        // Verify OTP code
        if (!r.getOtpCode().equals(request.getOtp())) {
            otpRepo.save(r);
            int left = otpMaxAttempts - r.getAttemptCount();
            throw new RuntimeException("Incorrect OTP. " + left + " attempt(s) remaining.");
        }

        // Mark OTP as used
        r.setIsUsed(true);
        r.setUsedAt(LocalDateTime.now());
        otpRepo.save(r);

        // ── NEW: Look up user by phone ───────────────────────────────────────
        User user = userRepo.findByPhone(request.getPhoneNumber())
                .orElseThrow(() -> new RuntimeException(
                    "No account found for this phone number. Please contact SLT to register."));

        // Guard: only CLIENTs can login via OTP
//        if (user.getRole() != User.Role.CLIENT) {
//            throw new RuntimeException("OTP login is only available for clients. Please use username/password.");
//        }

        // Guard: account must be active
        if (user.getIsActive() == null || !user.getIsActive()) {
            throw new RuntimeException("Your account is inactive. Please contact SLT support.");
        }

        // Update last login
        userService.updateLastLogin(user.getId());

        return buildAuthResponse(user, request.getDeviceInfo());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. PASSWORD LOGIN (ADMIN, TEAM_LEAD, TECHNICIAN) — NOW WITH USER LOOKUP
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public AuthResponse passwordLogin(PasswordLoginRequest request) {
        // Look up user by username
        User user = userRepo.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("Invalid username or password."));

        // Guard: account must be active
        if (user.getIsActive() == null || !user.getIsActive()) {
            throw new RuntimeException("Your account is inactive. Please contact your administrator.");
        }

        // Verify password with BCrypt
        if (!encoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Invalid username or password.");
        }

        // Update last login
        userService.updateLastLogin(user.getId());

        return buildAuthResponse(user, request.getDeviceInfo());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. REFRESH TOKEN — NOW FETCHES FRESH USER DATA
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public AuthResponse refreshToken(String tokenValue) {
        RefreshToken rt = rtRepo.findByToken(tokenValue)
                .orElseThrow(() -> new RuntimeException("Invalid refresh token."));

        if (!rt.isValid()) {
            throw new RuntimeException("Refresh token expired or revoked. Please login again.");
        }

        // ── NEW: Fetch fresh user data ───────────────────────────────────────
        User user = userRepo.findById(rt.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found."));

        // Generate new access token with current user data
        String newAccess = jwt.createAccessToken(
            user.getId(),
            user.getUsername(),
            user.getRole().name(),
            user.getBranchId()
        );

        AuthResponse response = new AuthResponse();
        response.setAccessToken(newAccess);
        response.setRefreshToken(tokenValue);
        response.setTokenType("Bearer");
        response.setExpiresIn(accessTokenExpiryMs);
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setRole(user.getRole().name());
        response.setFullName(user.getFullName());
        response.setBranchId(user.getBranchId());

        return response;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. LOGOUT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public void logout(Long userId) {
        rtRepo.revokeAllUserTokens(userId, LocalDateTime.now());
        log.info("User " + userId + " logged out — all refresh tokens revoked.");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPER — Build AuthResponse from User entity
    // ══════════════════════════════════════════════════════════════════════════

    private AuthResponse buildAuthResponse(User user, String deviceInfo) {
        // Create JWT access token
        String accessToken = jwt.createAccessToken(
            user.getId(),
            user.getUsername(),
            user.getRole().name(),
            user.getBranchId()
        );

        // Create refresh token
        String rtValue = UUID.randomUUID().toString();
        RefreshToken rt = RefreshToken.builder()
                .userId(user.getId())
                .token(rtValue)
                .expiresAt(LocalDateTime.now().plusSeconds(refreshTokenExpiryMs / 1000))
                .deviceInfo(deviceInfo)
                .build();
        rtRepo.save(rt);

        // Build response
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rtValue)
                .tokenType("Bearer")
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole().name())
                .fullName(user.getFullName())
                .branchId(user.getBranchId())
                .expiresIn(accessTokenExpiryMs)
                .build();
    }
}
