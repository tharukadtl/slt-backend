package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.KpiTarget;
import lk.slt.fieldops.entity.User;
import org.springframework.data.jpa.repository
        .JpaRepository;
import org.springframework.data.jpa.repository
        .Query;
import org.springframework.data.repository.query
        .Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KpiTargetRepository
        extends JpaRepository<KpiTarget, Long> {

    // ─── Find branch-level monthly target ────────────────

    java.util.Optional<KpiTarget>
    findByBranchIdAndTargetYearAndTargetMonth(
            Long branchId, Integer targetYear, Integer targetMonth);

    // ─── Find by User ─────────────────────────────────────

    List<KpiTarget> findByUserAndIsActiveTrue(
            User user);

    List<KpiTarget>
    findByUserAndPeriodAndIsActiveTrue(
            User user, String period);

    // ─── Find by User ID ──────────────────────────────────

    @Query("SELECT kt FROM KpiTarget kt "
            + "WHERE kt.user.id = :userId "
            + "AND kt.isActive = true "
            + "ORDER BY kt.createdAt DESC")
    List<KpiTarget> findActiveByUserId(
            @Param("userId") Long userId);

    @Query("SELECT kt FROM KpiTarget kt "
            + "WHERE kt.user.id = :userId "
            + "AND kt.period = :period "
            + "AND kt.isActive = true "
            + "ORDER BY kt.createdAt DESC")
    List<KpiTarget> findActiveByUserIdAndPeriod(
            @Param("userId") Long userId,
            @Param("period") String period);

    // ─── Find by Branch ───────────────────────────────────

    @Query("SELECT kt FROM KpiTarget kt "
            + "WHERE kt.branch.id = :branchId "
            + "AND kt.isGroupTarget = true "
            + "AND kt.isActive = true")
    List<KpiTarget> findGroupTargetsByBranchId(
            @Param("branchId") Long branchId);

    // ─── Find by Assigned By ──────────────────────────────

    @Query("SELECT kt FROM KpiTarget kt "
            + "WHERE kt.assignedBy.id = :adminId "
            + "AND kt.isActive = true "
            + "ORDER BY kt.createdAt DESC")
    List<KpiTarget> findByAssignedById(
            @Param("adminId") Long adminId);

    // ─── Find All Active ──────────────────────────────────

    @Query("SELECT kt FROM KpiTarget kt "
            + "WHERE kt.isActive = true "
            + "ORDER BY kt.createdAt DESC")
    List<KpiTarget> findAllActive();

    // ─── Count by Status ──────────────────────────────────

    @Query("SELECT COUNT(kt) FROM KpiTarget kt "
            + "WHERE kt.user.id = :userId "
            + "AND kt.status = :status "
            + "AND kt.isActive = true")
    long countByUserIdAndStatus(
            @Param("userId") Long userId,
            @Param("status") String status);
}