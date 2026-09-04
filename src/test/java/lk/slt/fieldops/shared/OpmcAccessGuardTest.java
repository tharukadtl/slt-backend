package lk.slt.fieldops.shared;

import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link OpmcAccessGuard} (Stage F #2/#3) — the shared guard extracted so
 * {@code ResourceAllocationController}, {@code KpiController}, and {@code ReportController} don't
 * each hand-roll their own copy of {@code WorkGroupController}/{@code UserService}'s existing
 * "Admin sees only their own OPMC, Super Admin unscoped" pattern.
 */
@ExtendWith(MockitoExtension.class)
class OpmcAccessGuardTest {

    @Mock private UserRepository userRepo;

    private OpmcAccessGuard guard;

    private User user(Long id, User.Role role, Long opmcId) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setOpmcId(opmcId);
        return u;
    }

    private void newGuard() {
        guard = new OpmcAccessGuard(userRepo);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // assertSameOpmc — single-target endpoints
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void assertSameOpmc_adminSameOpmc_passes() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, 5L)));
        newGuard();
        assertDoesNotThrow(() -> guard.assertSameOpmc(5L, 1L));
    }

    @Test
    void assertSameOpmc_adminDifferentOpmc_throwsAccessDenied() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, 5L)));
        newGuard();
        assertThrows(AccessDeniedException.class, () -> guard.assertSameOpmc(6L, 1L),
            "An Admin must not be able to act on a target in a different OPMC");
    }

    @Test
    void assertSameOpmc_superAdminAnyOpmc_passes() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.SUPER_ADMIN, 5L)));
        newGuard();
        assertDoesNotThrow(() -> guard.assertSameOpmc(999L, 1L),
            "Super Admin must remain fully unscoped, regardless of the caller's own opmcId");
        assertDoesNotThrow(() -> guard.assertSameOpmc(null, 1L),
            "Super Admin must be unscoped even against a null target opmcId");
    }

    @Test
    void assertSameOpmc_targetOpmcNull_throwsForNonSuperAdmin() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, 5L)));
        newGuard();
        assertThrows(AccessDeniedException.class, () -> guard.assertSameOpmc(null, 1L),
            "A null target opmcId must not silently pass for a non-Super-Admin caller");
    }

    @Test
    void assertSameOpmc_callerHasNoOpmc_throws() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, null)));
        newGuard();
        assertThrows(AccessDeniedException.class, () -> guard.assertSameOpmc(5L, 1L),
            "A caller with no opmcId on file must be denied, not silently matched");
    }

    @Test
    void assertSameOpmc_unknownCaller_throwsResourceNotFound() {
        when(userRepo.findById(1L)).thenReturn(Optional.empty());
        newGuard();
        assertThrows(ResourceNotFoundException.class, () -> guard.assertSameOpmc(5L, 1L));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // resolveOpmcFilter — list/aggregate endpoints
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void resolveOpmcFilter_superAdmin_returnsNullUnscoped() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.SUPER_ADMIN, 5L)));
        newGuard();
        assertNull(guard.resolveOpmcFilter(1L),
            "null must mean unscoped for Super Admin, regardless of their own opmcId");
    }

    @Test
    void resolveOpmcFilter_admin_returnsOwnOpmcId() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, 5L)));
        newGuard();
        assertEquals(5L, guard.resolveOpmcFilter(1L));
    }

    @Test
    void resolveOpmcFilter_teamLeadAndTechnician_returnOwnOpmcId() {
        when(userRepo.findById(2L)).thenReturn(Optional.of(user(2L, User.Role.TEAM_LEAD, 7L)));
        when(userRepo.findById(3L)).thenReturn(Optional.of(user(3L, User.Role.TECHNICIAN, 7L)));
        newGuard();
        assertEquals(7L, guard.resolveOpmcFilter(2L));
        assertEquals(7L, guard.resolveOpmcFilter(3L));
    }

    @Test
    void resolveOpmcFilter_nonSuperAdminWithNoOpmc_failsClosed() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, null)));
        newGuard();
        assertThrows(AccessDeniedException.class, () -> guard.resolveOpmcFilter(1L),
            "A non-Super-Admin caller with no opmcId must fail closed (deny), never fall through to "
                + "returning null, which downstream callers treat as \"unscoped\" — that would let a "
                + "misconfigured Admin account see every OPMC's data");
    }
}
