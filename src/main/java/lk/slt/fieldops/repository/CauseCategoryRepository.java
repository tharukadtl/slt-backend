package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.CauseCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * CauseCategoryRepository — middle tier of the Cause/Material hierarchy (85 rows), FK'd to
 * TypeOfFault. Read-only reference data; first Java-level access (Stage 2).
 */
@Repository
public interface CauseCategoryRepository extends JpaRepository<CauseCategory, Long> {
    Optional<CauseCategory> findByCauseCategoryCode(String causeCategoryCode);
    List<CauseCategory> findByTypeOfFaultIdOrderBySortKeyAsc(Long typeOfFaultId);
    List<CauseCategory> findAllByOrderBySortKeyAsc();
}
