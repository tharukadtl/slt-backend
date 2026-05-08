package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.CheckInOut;
import lk.slt.fieldops.entity.User;
import org.springframework.data.jpa.repository
        .JpaRepository;
import org.springframework.data.jpa.repository
        .Query;
import org.springframework.data.repository.query
        .Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CheckInOutRepository
        extends JpaRepository<CheckInOut, Long> {

    // ─── Find Today's Record ──────────────────────────────

    @Query("SELECT c FROM CheckInOut c "
            + "WHERE c.user.id = :userId "
            + "AND c.checkInTime >= :startOfDay "
            + "ORDER BY c.checkInTime DESC")
    Optional<CheckInOut> findTodayByUserId(
            @Param("userId") Long userId,
            @Param("startOfDay")
            LocalDateTime startOfDay);

    // ─── Find Active Check-In ─────────────────────────────
    // Active = checked in but not yet checked out

    @Query("SELECT c FROM CheckInOut c "
            + "WHERE c.user.id = :userId "
            + "AND c.checkOutTime IS NULL "
            + "ORDER BY c.checkInTime DESC")
    Optional<CheckInOut>
    findActiveCheckInByUserId(
            @Param("userId") Long userId);

    // ─── Find History by User ─────────────────────────────

    @Query("SELECT c FROM CheckInOut c "
            + "WHERE c.user.id = :userId "
            + "ORDER BY c.checkInTime DESC")
    List<CheckInOut> findByUserIdOrderByDesc(
            @Param("userId") Long userId);

    // ─── Find History by User and Date Range ─────────────

    @Query("SELECT c FROM CheckInOut c "
            + "WHERE c.user.id = :userId "
            + "AND c.checkInTime BETWEEN "
            + ":startDate AND :endDate "
            + "ORDER BY c.checkInTime DESC")
    List<CheckInOut> findByUserIdAndDateRange(
            @Param("userId") Long userId,
            @Param("startDate")
            LocalDateTime startDate,
            @Param("endDate")
            LocalDateTime endDate);

    // ─── Find Team Today ──────────────────────────────────

    @Query("SELECT c FROM CheckInOut c "
            + "JOIN c.user u "
            + "WHERE u.branchId = :branchId "
            + "AND c.checkInTime >= :startOfDay "
            + "ORDER BY c.checkInTime DESC")
    List<CheckInOut> findTeamTodayByBranchId(
            @Param("branchId") Long branchId,
            @Param("startOfDay")
            LocalDateTime startOfDay);

    // ─── Find All Today ───────────────────────────────────

    @Query("SELECT c FROM CheckInOut c "
            + "WHERE c.checkInTime >= :startOfDay "
            + "ORDER BY c.checkInTime DESC")
    List<CheckInOut> findAllToday(
            @Param("startOfDay")
            LocalDateTime startOfDay);

    // ─── Count Today ──────────────────────────────────────

    @Query("SELECT COUNT(c) "
            + "FROM CheckInOut c "
            + "WHERE c.checkInTime >= :startOfDay")
    long countTodayCheckIns(
            @Param("startOfDay")
            LocalDateTime startOfDay);

    // ─── Count Active ─────────────────────────────────────

    @Query("SELECT COUNT(c) "
            + "FROM CheckInOut c "
            + "WHERE c.checkOutTime IS NULL "
            + "AND c.checkInTime >= :startOfDay")
    long countActiveCheckIns(
            @Param("startOfDay")
            LocalDateTime startOfDay);

    // ─── Find by User ─────────────────────────────────────

    List<CheckInOut> findByUser(User user);

    // ─── Check if Already Checked In Today ───────────────

    @Query("SELECT COUNT(c) > 0 "
            + "FROM CheckInOut c "
            + "WHERE c.user.id = :userId "
            + "AND c.checkInTime >= :startOfDay")
    boolean existsTodayCheckIn(
            @Param("userId") Long userId,
            @Param("startOfDay")
            LocalDateTime startOfDay);

    // ─── Find by Multiple Users in a Date Range ───────────

    @Query("SELECT c FROM CheckInOut c "
            + "WHERE c.user.id IN :userIds "
            + "AND c.checkInTime BETWEEN "
            + ":startDate AND :endDate "
            + "ORDER BY c.checkInTime DESC")
    List<CheckInOut> findByUserIdsAndDateRange(
            @Param("userIds") List<Long> userIds,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}