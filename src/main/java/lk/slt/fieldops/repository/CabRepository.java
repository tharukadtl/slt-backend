package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Cab;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CabRepository extends JpaRepository<Cab, Long> {
    Optional<Cab> findByCode(String code);
    boolean existsByCode(String code);
    boolean existsByExchangeIdAndCode(Long exchangeId, String code);
    List<Cab> findByExchangeId(Long exchangeId);
    List<Cab> findByIsActiveTrue();

    // QA_Compliance_Consolidated_Report.md Stage G Major — same N+1 shape as
    // CircuitRepository/DpRepository's fetch-joined queries (Cab -> Exchange -> Opmc via LAZY
    // proxies), added for consistency across the whole Exchange/Cab/Dp/Circuit family even though
    // `cabs` (12,876 rows) and `exchanges` (377 rows) are far too small for this to be a practical
    // problem today the way CircuitService's was.
    @Query("SELECT c FROM Cab c "
        + "LEFT JOIN FETCH c.exchange e "
        + "LEFT JOIN FETCH e.opmc")
    List<Cab> findAllWithFullChain();

    @Query("SELECT c FROM Cab c "
        + "LEFT JOIN FETCH c.exchange e "
        + "LEFT JOIN FETCH e.opmc "
        + "WHERE c.isActive = true")
    List<Cab> findActiveWithFullChain();

    @Query("SELECT c FROM Cab c "
        + "LEFT JOIN FETCH c.exchange e "
        + "LEFT JOIN FETCH e.opmc "
        + "WHERE e.id = :exchangeId")
    List<Cab> findByExchangeIdWithFullChain(@Param("exchangeId") Long exchangeId);
}
