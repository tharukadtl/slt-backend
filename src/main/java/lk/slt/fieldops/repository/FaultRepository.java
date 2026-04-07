package lk.slt.fieldops.fault.repository;

import lk.slt.fieldops.fault.entity.Fault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FaultRepository extends JpaRepository<Fault, Long> {

    Optional<Fault> findByFaultNumber(String faultNumber);

    /** All faults for a branch — Admin dashboard */
    List<Fault> findByBranchIdOrderByReportedAtDesc(Long branchId);

    /** Filter by status — e.g. get all REPORTED faults */
    List<Fault> findByBranchIdAndStatusOrderByPriorityAscReportedAtAsc(
            Long branchId, Fault.FaultStatus status);

    /** All faults for a customer — Client mobile app */
    List<Fault> findByCustomerIdOrderByReportedAtDesc(Long customerId);

    /** All faults assigned to a Team Lead */
    List<Fault> findByAssignedTeamLeadIdAndStatusIn(
            Long teamLeadId, List<Fault.FaultStatus> statuses);

    /** Overdue faults — alert dashboard */
    List<Fault> findByIsOverdueTrueAndStatusNotIn(List<Fault.FaultStatus> excludeStatuses);

    /** All open faults across all branches — Super Admin */
    @Query("SELECT f FROM Fault f WHERE f.status NOT IN ('COMPLETED','CANCELLED') ORDER BY f.priority ASC, f.reportedAt ASC")
    List<Fault> findAllOpenFaults();

    /** Count open faults per branch — dashboard widget */
    @Query("SELECT COUNT(f) FROM Fault f WHERE f.branchId = :branchId AND f.status NOT IN ('COMPLETED','CANCELLED')")
    long countOpenFaultsByBranch(Long branchId);

    /** Generate next fault number — e.g. FLT-2026-00042 */
    @Query("SELECT COUNT(f) FROM Fault f WHERE YEAR(f.reportedAt) = :year")
    long countFaultsByYear(int year);
}
