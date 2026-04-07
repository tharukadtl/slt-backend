package lk.slt.fieldops.kpi.repository;

import lk.slt.fieldops.kpi.entity.KpiScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface KpiScoreRepository extends JpaRepository<KpiScore, Long> {

    Optional<KpiScore> findByTechnicianIdAndScoreDate(Long techId, LocalDate date);

    List<KpiScore> findByTechnicianIdOrderByScoreDateDesc(Long techId);

    List<KpiScore> findByBranchIdAndScoreDate(Long branchId, LocalDate date);

    @Query("SELECT k FROM KpiScore k WHERE k.technicianId = :techId " +
           "AND k.scoreDate BETWEEN :from AND :to ORDER BY k.scoreDate DESC")
    List<KpiScore> findByTechnicianAndDateRange(Long techId, LocalDate from, LocalDate to);

    @Query("SELECT k FROM KpiScore k WHERE k.scoreDate = :date " +
           "AND k.scoredFor = 'TECHNICIAN' ORDER BY k.overallScore DESC")
    List<KpiScore> findLeaderboardForDate(LocalDate date);
}
