package lk.slt.fieldops.fault.repository;

import lk.slt.fieldops.fault.entity.FaultHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * FaultHistoryRepository — query the audit trail.
 */
@Repository
public interface FaultHistoryRepository extends JpaRepository<FaultHistory, Long> {

    /** Get full timeline for a fault — ordered oldest first */
    List<FaultHistory> findByFaultIdOrderByChangedAtAsc(Long faultId);

    /** Count how many times a fault was put on hold */
    long countByFaultIdAndNewStatus(Long faultId, lk.slt.fieldops.fault.entity.Fault.FaultStatus status);
}
