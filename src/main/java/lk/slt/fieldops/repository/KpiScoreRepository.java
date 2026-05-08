package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.KpiScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface KpiScoreRepository
        extends JpaRepository<KpiScore, Long> {

    // ─── Find by Technician ───────────────────────────────

    Optional<KpiScore> findByTechnicianIdAndScoreDate(
            Long technicianId, LocalDate scoreDate);

    List<KpiScore> findByTechnicianIdOrderByScoreDateDesc(
            Long technicianId);

    @Query("SELECT ks FROM KpiScore ks "
            + "WHERE ks.technicianId = :techId "
            + "AND ks.period = :period "
            + "ORDER BY ks.scoreDate DESC")
    List<KpiScore> findByTechnicianIdAndPeriod(
            @Param("techId") Long technicianId,
            @Param("period") String period);

    // ─── Date range query ─────────────────────────────────

    @Query("SELECT ks FROM KpiScore ks "
            + "WHERE ks.technicianId = :techId "
            + "AND ks.scoreDate >= :from "
            + "AND ks.scoreDate <= :to "
            + "ORDER BY ks.scoreDate DESC")
    List<KpiScore> findByTechnicianAndDateRange(
            @Param("techId") Long technicianId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // ─── Leaderboard ──────────────────────────────────────

    @Query("SELECT ks FROM KpiScore ks "
            + "WHERE ks.scoreDate = :date "
            + "ORDER BY ks.overallScore DESC")
    List<KpiScore> findLeaderboardForDate(
            @Param("date") LocalDate date);

    @Query("SELECT ks FROM KpiScore ks "
            + "WHERE ks.period = :period "
            + "AND ks.scoreDate = :date "
            + "ORDER BY ks.overallScore DESC")
    List<KpiScore> findTopPerformersByPeriod(
            @Param("period") String period,
            @Param("date") LocalDate date);

    // ─── Find by Branch ───────────────────────────────────

    @Query("SELECT ks FROM KpiScore ks "
            + "WHERE ks.branchId = :branchId "
            + "AND ks.scoreDate = :date "
            + "ORDER BY ks.overallScore DESC")
    List<KpiScore> findByBranchIdAndScoreDate(
            @Param("branchId") Long branchId,
            @Param("date") LocalDate date);

    @Query("SELECT ks FROM KpiScore ks "
            + "WHERE ks.branchId = :branchId "
            + "AND ks.period = :period "
            + "ORDER BY ks.overallScore DESC")
    List<KpiScore> findByBranchIdAndPeriod(
            @Param("branchId") Long branchId,
            @Param("period") String period);

    // ─── Average Score ────────────────────────────────────

    @Query("SELECT AVG(ks.overallScore) "
            + "FROM KpiScore ks "
            + "WHERE ks.branchId = :branchId "
            + "AND ks.period = :period")
    Double avgScoreByBranchAndPeriod(
            @Param("branchId") Long branchId,
            @Param("period") String period);
}
