package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByJobId(Long jobId);

    Optional<Payment> findByPaymentNumber(String paymentNumber);

    List<Payment> findByStatusOrderBySubmittedAtAsc(Payment.PaymentStatus status);

    List<Payment> findByOpmcIdOrderBySubmittedAtDesc(Long opmcId);

    List<Payment> findByTeamLeadIdOrderBySubmittedAtDesc(Long teamLeadId);

    // QA_Compliance_Consolidated_Report.md — KpiCalculationService.getPersonalKpi previously
    // loaded the ENTIRE payments table via findAll() and filtered by teamLeadId/status/date
    // range in memory. Pushes the teamLeadId + date-range filter to the database; the status
    // check ("APPROVED") is deliberately kept as an in-memory step by the caller, not folded
    // into this query — Payment.PaymentStatus has no APPROVED constant at all (a separate,
    // already-tracked, pre-existing bug: this report's own ANA-001-adjacent Major already notes
    // KpiCalculationService's revenue always computes to zero for exactly this reason), so a
    // query-level `status = APPROVED` would fail to compile/parse as a JPQL enum literal. This
    // fix's job is the N+1/full-scan shape, not that separate bug — preserved exactly, not
    // fixed here. The "In" variant lets getTeamKpi fetch every member's candidate payments in
    // one query rather than one query per member.
    List<Payment> findByTeamLeadIdAndCreatedAtBetween(
            Long teamLeadId, LocalDateTime start, LocalDateTime end);

    List<Payment> findByTeamLeadIdInAndCreatedAtBetween(
            List<Long> teamLeadIds, LocalDateTime start, LocalDateTime end);

    List<Payment> findByCustomerIdOrderBySubmittedAtDesc(Long customerId);

    @Query("SELECT COUNT(p) FROM Payment p WHERE YEAR(p.createdAt) = :year")
    long countByYear(int year);

    @Query("SELECT COUNT(p) FROM Payment p WHERE YEAR(p.approvedAt) = :year AND MONTH(p.approvedAt) = :month AND p.status = 'FINAL'")
    long countBilledByYearMonth(int year, int month);
}
