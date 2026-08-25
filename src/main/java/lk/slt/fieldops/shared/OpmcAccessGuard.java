package lk.slt.fieldops.shared;

import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Shared OPMC access-control guard (Stage F #2/#3, QA_Compliance_Consolidated_Report.md) — an Admin
 * may only see/act on data within their own OPMC; Super Admin is unscoped. Extracted so
 * {@code ResourceAllocationController}, {@code KpiController}, and {@code ReportController} reuse one
 * implementation instead of hand-rolling a fourth/fifth/sixth private copy of the check that already
 * exists independently in {@code WorkGroupController} (RES-023) and {@code UserService} (Critical #30).
 * Those two are left as-is — same logic, different call sites, not refactored here to avoid touching
 * already-verified code outside this fix's scope.
 */
@Component
public class OpmcAccessGuard {

    private final UserRepository userRepo;

    public OpmcAccessGuard(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    private User findCaller(Long callerId) {
        return userRepo.findById(callerId)
            .orElseThrow(() -> new ResourceNotFoundException("User", callerId));
    }

    /**
     * For single-target endpoints (an ID naming one OPMC, Work Group, etc.): throws unless the caller
     * is SUPER_ADMIN or {@code targetOpmcId} matches the caller's own OPMC.
     */
    public void assertSameOpmc(Long targetOpmcId, Long callerId) {
        User caller = findCaller(callerId);
        if (caller.getRole() == User.Role.SUPER_ADMIN) return;

        Long callerOpmcId = caller.getOpmcId();
        if (callerOpmcId == null || targetOpmcId == null || !callerOpmcId.equals(targetOpmcId)) {
            throw new AccessDeniedException("This resource does not belong to your OPMC.");
        }
    }

    /**
     * For list/aggregate endpoints: returns {@code null} for SUPER_ADMIN (unscoped — no filter should
     * be applied), otherwise the caller's own {@code opmcId} to filter the result set by. Fails closed
     * — mirrors {@link #assertSameOpmc}'s "no OPMC on file denies" rule rather than treating it as
     * unscoped, so a non-SUPER_ADMIN caller with no {@code opmcId} (a data anomaly; every other role
     * is expected to always have one) can never fall through to seeing everyone's data.
     */
    public Long resolveOpmcFilter(Long callerId) {
        User caller = findCaller(callerId);
        if (caller.getRole() == User.Role.SUPER_ADMIN) return null;

        Long callerOpmcId = caller.getOpmcId();
        if (callerOpmcId == null) {
            throw new AccessDeniedException("Your account has no OPMC on file.");
        }
        return callerOpmcId;
    }
}
