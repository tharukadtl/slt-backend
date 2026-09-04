package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Circuit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CircuitRepository extends JpaRepository<Circuit, Long> {
    Optional<Circuit> findByCode(String code);
    boolean existsByCode(String code);
    List<Circuit> findByDpId(Long dpId);
    List<Circuit> findByIsActiveTrue();

    // QA_Compliance_Consolidated_Report.md Stage G Major — CircuitService.mapToDTO walks
    // Circuit -> Dp -> Cab -> Exchange -> Opmc through LAZY proxies with no batching, which at
    // this table's real volume (349,180 rows, matching docs/master-data/CIRCUIT.csv exactly — not
    // test data) turned GET /api/circuits into an unbounded N+1 that never completed (confirmed via
    // jstack: still RUNNABLE after 23+ minutes). These three queries collapse the whole chain into
    // one SQL statement with LEFT JOIN FETCH — LEFT, not inner, because dp/circuitCategory (and
    // transitively cab/exchange/opmc) are all nullable per the entity's own comments, and existing
    // null-tolerant behavior in mapToDTO/opmcIdOf for those rows must not change.
    @Query("SELECT c FROM Circuit c "
        + "LEFT JOIN FETCH c.dp d "
        + "LEFT JOIN FETCH d.cab cb "
        + "LEFT JOIN FETCH cb.exchange e "
        + "LEFT JOIN FETCH e.opmc "
        + "LEFT JOIN FETCH c.circuitCategory")
    List<Circuit> findAllWithFullChain();

    @Query("SELECT c FROM Circuit c "
        + "LEFT JOIN FETCH c.dp d "
        + "LEFT JOIN FETCH d.cab cb "
        + "LEFT JOIN FETCH cb.exchange e "
        + "LEFT JOIN FETCH e.opmc "
        + "LEFT JOIN FETCH c.circuitCategory "
        + "WHERE c.isActive = true")
    List<Circuit> findActiveWithFullChain();

    @Query("SELECT c FROM Circuit c "
        + "LEFT JOIN FETCH c.dp d "
        + "LEFT JOIN FETCH d.cab cb "
        + "LEFT JOIN FETCH cb.exchange e "
        + "LEFT JOIN FETCH e.opmc "
        + "LEFT JOIN FETCH c.circuitCategory "
        + "WHERE d.id = :dpId")
    List<Circuit> findByDpIdWithFullChain(@Param("dpId") Long dpId);
}
