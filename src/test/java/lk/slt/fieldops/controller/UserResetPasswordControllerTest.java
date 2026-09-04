package lk.slt.fieldops.controller;

import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA_Compliance_Consolidated_Report.md Stage G Minor — "{@code POST /api/users/{id}/reset-password}
 * returns plaintext password in the response body" ({@code UserController.java:94-98}).
 *
 * <p><b>Investigated before fixing.</b> Grepped {@code frontend-admin} end to end:
 * {@code resetPassword()} ({@code api/users.js:49}) has no caller anywhere in the UI — nothing
 * displays this value today, so there is no existing "relay the password to the user" flow this
 * fix needs to replace. The real, SRS-5.5.4-aligned admin capability ("force re-OTP on next
 * login") is the separate {@code resetAccess} endpoint, which never generates a password at all —
 * confirmed by reading both methods side by side in {@code UserService.java}. This is therefore
 * the "simple removal" case: the endpoint still generates and persists a real new password
 * server-side, unchanged; only the plaintext value is no longer echoed back in the response.</p>
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain, matching this project's established
 * convention: real JWT filter, real {@code @PreAuthorize}, real MySQL, {@code @Transactional}
 * rollback.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserResetPasswordControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository userRepo;
    @Autowired private PasswordEncoder encoder;

    private static final Long REAL_OPMC_ID = 1L;
    private static final String OLD_PASSWORD = "OldRealPass123";
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private User newTargetUser() {
        long n = uniq();
        User u = new User();
        u.setUsername("resetpwtest" + n);
        u.setPasswordHash(encoder.encode(OLD_PASSWORD));
        u.setFirstName("Reset");
        u.setLastName("Target" + n);
        u.setFullName("Reset Target " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(User.Role.TECHNICIAN);
        u.setOpmcId(REAL_OPMC_ID);
        return userRepo.save(u);
    }

    private String adminBearer() {
        long n = uniq();
        User admin = new User();
        admin.setUsername("resetpwadmin" + n);
        admin.setPasswordHash("x");
        admin.setFirstName("Reset");
        admin.setLastName("Admin");
        admin.setFullName("Reset Admin " + n);
        admin.setPhone("07" + (20000000L + (n % 70000000L)));
        admin.setRole(User.Role.SUPER_ADMIN);
        admin.setOpmcId(REAL_OPMC_ID);
        User saved = userRepo.save(admin);
        return "Bearer " + jwt.createAccessToken(saved.getId(), saved.getUsername(), "SUPER_ADMIN", REAL_OPMC_ID);
    }

    @Test
    void resetPassword_responseNeverContainsThePlaintextPassword() throws Exception {
        User target = newTargetUser();
        String oldHash = target.getPasswordHash();

        MvcResult result = mvc.perform(post("/api/users/" + target.getId() + "/reset-password")
                .header("Authorization", adminBearer()))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();

        assertFalse(body.contains("newPassword"),
            "Response must not carry a newPassword field at all: " + body);
        assertTrue(body.contains("Password reset successfully"),
            "The confirmation message must still be present: " + body);

        // The reset must still genuinely happen server-side — this fix removes the leak,
        // not the actual password rotation. Fresh, independent reload, not the request's own
        // in-memory entity, to prove the write really persisted.
        User reloaded = userRepo.findById(target.getId()).orElseThrow();
        assertNotEquals(oldHash, reloaded.getPasswordHash(),
            "The password hash must have genuinely changed — this fix must not silently skip the reset.");
        assertFalse(encoder.matches(OLD_PASSWORD, reloaded.getPasswordHash()),
            "The old password must no longer work against the new hash — a real rotation happened, "
                + "not a no-op that merely stopped returning the (unchanged) old password.");
    }

    @Test
    void resetPassword_nonAdminCaller_isForbidden() throws Exception {
        User target = newTargetUser();
        long n = uniq();
        User plainTech = new User();
        plainTech.setUsername("resetpwtech" + n);
        plainTech.setPasswordHash("x");
        plainTech.setFirstName("Reset");
        plainTech.setLastName("Tech");
        plainTech.setFullName("Reset Tech " + n);
        plainTech.setPhone("07" + (30000000L + (n % 60000000L)));
        plainTech.setRole(User.Role.TECHNICIAN);
        plainTech.setOpmcId(REAL_OPMC_ID);
        User saved = userRepo.save(plainTech);
        String techBearer = "Bearer " + jwt.createAccessToken(saved.getId(), saved.getUsername(), "TECHNICIAN", REAL_OPMC_ID);

        mvc.perform(post("/api/users/" + target.getId() + "/reset-password")
                .header("Authorization", techBearer))
            .andExpect(status().is(403));
    }
}
