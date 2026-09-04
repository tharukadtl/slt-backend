package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Dp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DpRepository extends JpaRepository<Dp, Long> {
    Optional<Dp> findByCode(String code);
    boolean existsByCode(String code);
    boolean existsByCabIdAndCode(Long cabId, String code);
    List<Dp> findByCabId(Long cabId);
    List<Dp> findByIsActiveTrue();

    // QA_Compliance_Consolidated_Report.md Stage G Major — same N+1 shape as CircuitRepository's
    // fetch-joined queries below it (Dp -> Cab -> Exchange -> Opmc via LAZY proxies), on the same
    // 349,155-row `dps` table. Collapsed into one query with LEFT JOIN FETCH for consistency, even
    // though in practice this path's round-trip count is bounded by Cab's much smaller distinct
    // count (12,876) rather than by `dps`' own row count, since Hibernate's session cache serves
    // repeat proxy-inits for an already-loaded Cab id from memory — real, but nowhere near as
    // catastrophic as CircuitService's near-1:1 Dp fan-out was.
    @Query("SELECT d FROM Dp d "
        + "LEFT JOIN FETCH d.cab cb "
        + "LEFT JOIN FETCH cb.exchange e "
        + "LEFT JOIN FETCH e.opmc")
    List<Dp> findAllWithFullChain();

    @Query("SELECT d FROM Dp d "
        + "LEFT JOIN FETCH d.cab cb "
        + "LEFT JOIN FETCH cb.exchange e "
        + "LEFT JOIN FETCH e.opmc "
        + "WHERE d.isActive = true")
    List<Dp> findActiveWithFullChain();

    @Query("SELECT d FROM Dp d "
        + "LEFT JOIN FETCH d.cab cb "
        + "LEFT JOIN FETCH cb.exchange e "
        + "LEFT JOIN FETCH e.opmc "
        + "WHERE cb.id = :cabId")
    List<Dp> findByCabIdWithFullChain(@Param("cabId") Long cabId);
}
