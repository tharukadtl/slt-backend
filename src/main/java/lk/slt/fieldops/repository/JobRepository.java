package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    Optional<Job> findByJobNumber(String jobNumber);

    List<Job> findByTeamLeadIdAndScheduledDate(Long teamLeadId, LocalDate date);

    List<Job> findByTechnicianIdAndScheduledDate(Long techId, LocalDate date);

    List<Job> findBySessionId(Long sessionId);

    /** Jobs still open in a session — for EOD auto-return */
    @Query("SELECT j FROM Job j WHERE j.sessionId = :sessionId " +
           "AND j.status IN ('PENDING','ACCEPTED','IN_PROGRESS','HOLD')")
    List<Job> findIncompleteJobsInSession(Long sessionId);

    /** EOD: return all open jobs back to Team Lead */
    @Modifying
    @Transactional
    @Query("UPDATE Job j SET j.status = 'PENDING', " +
           "j.technicianId = NULL, j.technicianName = NULL " +
           "WHERE j.sessionId = :sessionId " +
           "AND j.status IN ('PENDING','ACCEPTED','IN_PROGRESS','HOLD')")
    int returnAllJobsToTeamLead(Long sessionId);

    /** Technician checkout: return this technician's open jobs to team lead pool */
    @Modifying
    @Transactional
    @Query("UPDATE Job j SET j.status = 'PENDING', " +
           "j.technicianId = NULL, j.technicianName = NULL " +
           "WHERE j.technicianId = :technicianId " +
           "AND j.scheduledDate = :date " +
           "AND j.status IN ('PENDING','ACCEPTED','IN_PROGRESS','HOLD')")
    int returnJobsOnTechnicianCheckout(Long technicianId, LocalDate date);

    /** Count for job number generation */
    @Query("SELECT COUNT(j) FROM Job j WHERE YEAR(j.createdAt) = :year")
    long countJobsByYear(int year);
}
