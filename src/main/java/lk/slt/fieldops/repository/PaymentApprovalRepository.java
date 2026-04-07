package lk.slt.fieldops.payment.repository;

import lk.slt.fieldops.payment.entity.PaymentApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PaymentApprovalRepository extends JpaRepository<PaymentApproval, Long> {

    List<PaymentApproval> findByPaymentIdOrderByCreatedAtDesc(Long paymentId);
}
