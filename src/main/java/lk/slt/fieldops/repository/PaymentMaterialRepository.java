package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.PaymentMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentMaterialRepository extends JpaRepository<PaymentMaterial, Long> {

    List<PaymentMaterial> findByPaymentId(Long paymentId);
}
