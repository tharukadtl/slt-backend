package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Fault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FaultRepository
        extends JpaRepository<Fault, Long> {

    // ─── Find by Status ───────────────────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.status = :status "
            + "ORDER BY f.createdAt DESC")
    List<Fault> findByStatus(
            @Param("status") Fault.FaultStatus status);

    // ─── Find by optional Status + Category (admin list filter) ──────
    // Both parameters are optional: a NULL parameter disables that predicate,
    // so passing neither returns every fault, one narrows on that one, and
    // both are AND-ed. Filtering happens in the database, not in memory.

    @Query("SELECT f FROM Fault f "
            + "WHERE (:status IS NULL OR f.status = :status) "
            + "AND (:category IS NULL OR f.category = :category) "
            + "ORDER BY f.reportedAt DESC")
    List<Fault> findByOptionalStatusAndCategory(
            @Param("status")   Fault.FaultStatus   status,
            @Param("category") Fault.FaultCategory category);

    // RES-023 — OPMC Admin's fault list must be genuinely scoped server-side
    // (opmcId derived from the caller, never client-supplied), not merely
    // filtered in the UI while the API stays open cross-OPMC.
    @Query("SELECT f FROM Fault f "
            + "WHERE f.opmcId = :opmcId "
            + "AND (:status IS NULL OR f.status = :status) "
            + "AND (:category IS NULL OR f.category = :category) "
            + "ORDER BY f.reportedAt DESC")
    List<Fault> findByOpmcIdAndOptionalStatusAndCategory(
            @Param("opmcId")   Long                 opmcId,
            @Param("status")   Fault.FaultStatus   status,
            @Param("category") Fault.FaultCategory category);

    // ─── Find by OPMC + Status (ordered by priority then reported) ──

    @Query("SELECT f FROM Fault f "
            + "WHERE f.opmcId = :opmcId "
            + "AND f.status = :status "
            + "ORDER BY f.priority ASC, f.reportedAt ASC")
    List<Fault> findByOpmcIdAndStatusOrderByPriorityAscReportedAtAsc(
            @Param("opmcId") Long opmcId,
            @Param("status") Fault.FaultStatus status);

    // ─── Find by OPMC ───────────────────────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.opmcId = :opmcId "
            + "ORDER BY f.reportedAt DESC")
    List<Fault> findByOpmcIdOrderByReportedAtDesc(
            @Param("opmcId") Long opmcId);

    // ─── Find by Work Group (a Team Lead's incoming queue, SRS 5.5.1) ────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.workGroupId = :workGroupId "
            + "AND f.status NOT IN ('COMPLETED', 'CANCELLED') "
            + "ORDER BY f.priority ASC, f.reportedAt ASC")
    List<Fault> findOpenByWorkGroupId(@Param("workGroupId") Long workGroupId);

    // ─── Find by Customer ─────────────────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.customerId = :customerId "
            + "ORDER BY f.reportedAt DESC")
    List<Fault> findByCustomerIdOrderByReportedAtDesc(
            @Param("customerId") Long customerId);

    // ─── Find by Assigned Team Lead ───────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.assignedTeamLeadId = :teamLeadId "
            + "ORDER BY f.createdAt DESC")
    List<Fault> findByAssignedTechnicianId(
            @Param("teamLeadId") Long teamLeadId);

    // ─── Find Unassigned ──────────────────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.assignedTeamLeadId IS NULL "
            + "AND f.status NOT IN "
            + "('COMPLETED', 'CANCELLED') "
            + "ORDER BY f.createdAt DESC")
    List<Fault> findUnassigned();

    // ─── Find All Open ────────────────────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.status NOT IN "
            + "('COMPLETED', 'CANCELLED') "
            + "ORDER BY f.createdAt ASC")
    List<Fault> findAllOpen();

    @Query("SELECT f FROM Fault f "
            + "WHERE f.status NOT IN "
            + "('COMPLETED', 'CANCELLED') "
            + "ORDER BY f.createdAt ASC")
    List<Fault> findAllOpenFaults();

    // ─── Find by Date Range ───────────────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.createdAt BETWEEN "
            + ":startDate AND :endDate "
            + "ORDER BY f.createdAt DESC")
    List<Fault> findByDateRange(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate")   LocalDateTime endDate);

    // ─── Count by Status ──────────────────────────────────

    @Query("SELECT COUNT(f) FROM Fault f "
            + "WHERE f.status = :status")
    long countByStatus(
            @Param("status") Fault.FaultStatus status);

    // ─── Find by Priority ─────────────────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.priority = :priority "
            + "AND f.status NOT IN "
            + "('COMPLETED', 'CANCELLED') "
            + "ORDER BY f.createdAt ASC")
    List<Fault> findByPriority(
            @Param("priority") Fault.FaultPriority priority);

    // ─── Count by Year ────────────────────────────────────

    @Query("SELECT COUNT(f) FROM Fault f "
            + "WHERE YEAR(f.createdAt) = :year")
    long countFaultsByYear(@Param("year") int year);

    // ─── Find High Priority Open ──────────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.priority = 'HIGH' "
            + "AND f.status NOT IN "
            + "('COMPLETED', 'CANCELLED') "
            + "ORDER BY f.createdAt ASC")
    List<Fault> findHighPriorityOpen();

    // ─── Uploaded-photo ownership lookup (SecurityConfig /uploads/** authorization) ──────
    // Client-submitted evidence photos (Fault.photoUrls is a comma-joined string of
    // "/uploads/photos/xxx.jpg" paths) — used to answer "does this Fault carry this exact
    // photo path" without pulling every Fault into memory to check with String.contains().
    @Query("SELECT f FROM Fault f WHERE f.photoUrls LIKE CONCAT('%', :path, '%')")
    List<Fault> findByPhotoUrlsContaining(@Param("path") String path);
}
