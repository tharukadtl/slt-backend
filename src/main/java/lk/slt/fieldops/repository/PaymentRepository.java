package lk.slt.fieldops.payment.repository;

import lk.slt.fieldops.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByJobId(Long jobId);

    Optional<Payment> findByPaymentNumber(String paymentNumber);

    List<Payment> findByStatusOrderBySubmittedAtAsc(Payment.PaymentStatus status);

    List<Payment> findByBranchIdOrderBySubmittedAtDesc(Long branchId);

    List<Payment> findByTeamLeadIdOrderBySubmittedAtDesc(Long teamLeadId);

    List<Payment> findByCustomerIdOrderBySubmittedAtDesc(Long customerId);

    @Query("SELECT COUNT(p) FROM Payment p WHERE YEAR(p.createdAt) = :year")
    long countByYear(int year);

    @Query("SELECT COUNT(p) FROM Payment p WHERE YEAR(p.approvedAt) = :year AND MONTH(p.approvedAt) = :month AND p.status = 'BILLED'")
    long countBilledByYearMonth(int year, int month);
}
