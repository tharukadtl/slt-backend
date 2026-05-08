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

    // ─── Find by Branch + Status (ordered by priority then reported) ──

    @Query("SELECT f FROM Fault f "
            + "WHERE f.branchId = :branchId "
            + "AND f.status = :status "
            + "ORDER BY f.priority ASC, f.reportedAt ASC")
    List<Fault> findByBranchIdAndStatusOrderByPriorityAscReportedAtAsc(
            @Param("branchId") Long branchId,
            @Param("status") Fault.FaultStatus status);

    // ─── Find by Branch ───────────────────────────────────

    @Query("SELECT f FROM Fault f "
            + "WHERE f.branchId = :branchId "
            + "ORDER BY f.reportedAt DESC")
    List<Fault> findByBranchIdOrderByReportedAtDesc(
            @Param("branchId") Long branchId);

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
}
