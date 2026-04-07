package lk.slt.fieldops.kpi.repository;

import lk.slt.fieldops.kpi.entity.KpiTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface KpiTargetRepository extends JpaRepository<KpiTarget, Long> {

    Optional<KpiTarget> findByBranchIdAndTargetYearAndTargetMonth(
            Long branchId, Integer year, Integer month);
}
