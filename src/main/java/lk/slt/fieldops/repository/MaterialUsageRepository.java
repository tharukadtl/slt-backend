package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.MaterialUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MaterialUsageRepository extends JpaRepository<MaterialUsage, Long> {

    List<MaterialUsage> findByJobId(Long jobId);

    List<MaterialUsage> findByJobIdAndChargeType(Long jobId, MaterialUsage.ChargeType chargeType);
}
