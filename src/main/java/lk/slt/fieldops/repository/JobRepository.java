package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    Optional<Job> findByJobNumber(String jobNumber);

    List<Job> findByTeamLeadIdAndScheduledDate(Long teamLeadId, LocalDate date);

    List<Job> findByTechnicianIdAndScheduledDate(Long techId, LocalDate date);

    // QA_Compliance_Consolidated_Report.md — KpiCalculationService.getPersonalKpi previously
    // loaded the ENTIRE jobs table via findAll() and filtered by technicianId/date range in
    // memory. These push both filters to the database instead. The "In" variant lets
    // getTeamKpi fetch every member's jobs in one query rather than one query per member.
    List<Job> findByTechnicianIdAndCreatedAtBetween(
            Long technicianId, LocalDateTime start, LocalDateTime end);

    List<Job> findByTechnicianIdInAndCreatedAtBetween(
            List<Long> technicianIds, LocalDateTime start, LocalDateTime end);

    List<Job> findBySessionId(Long sessionId);

    /** Jobs still open in a session — for EOD auto-return */
    @Query("SELECT j FROM Job j WHERE j.sessionId = :sessionId " +
           "AND j.status IN ('PENDING','ACCEPTED','IN_PROGRESS','HOLD')")
    List<Job> findIncompleteJobsInSession(Long sessionId);

    /** Count for job number generation */
    @Query("SELECT COUNT(j) FROM Job j WHERE YEAR(j.createdAt) = :year")
    long countJobsByYear(int year);

    /** Most recent job for a fault (used to find the technician currently working it) */
    Optional<Job> findFirstByFaultIdOrderByCreatedAtDesc(Long faultId);

    /** Is this technician currently assigned to an active (non-terminal) job for this client? */
    @Query("SELECT CASE WHEN COUNT(j) > 0 THEN TRUE ELSE FALSE END FROM Job j " +
           "WHERE j.customerId = :customerId AND j.technicianId = :technicianId " +
           "AND j.status NOT IN ('COMPLETED','CANCELLED')")
    boolean existsActiveJobForCustomerAndTechnician(Long customerId, Long technicianId);

    // ─── Uploaded-photo ownership lookup (SecurityConfig /uploads/** authorization) ──────
    // Technician after-service photos (Job.completionPhotoUrls) — Job already carries its
    // own customerId (set from the linked Fault at creation), so no join back to Fault
    // is needed to answer "does this Job's completion set carry this exact photo path,
    // and does it belong to this caller".
    @Query("SELECT j FROM Job j WHERE j.completionPhotoUrls LIKE CONCAT('%', :path, '%')")
    List<Job> findByCompletionPhotoUrlsContaining(@Param("path") String path);
}
