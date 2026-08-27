package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.CauseOfFault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * CauseOfFaultRepository — leaf of the Cause/Material hierarchy (869 rows), FK'd to CauseCategory.
 * Read-only reference data; first Java-level access (Stage 2). Fault.causeId FKs here.
 */
@Repository
public interface CauseOfFaultRepository extends JpaRepository<CauseOfFault, Long> {
    Optional<CauseOfFault> findByCauseCode(String causeCode);
    List<CauseOfFault> findByCauseCategoryIdOrderBySortKeyAsc(Long causeCategoryId);
}
