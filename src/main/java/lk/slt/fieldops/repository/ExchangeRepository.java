package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Exchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExchangeRepository extends JpaRepository<Exchange, Long> {
    Optional<Exchange> findByCode(String code);
    boolean existsByCode(String code);
    List<Exchange> findByOpmcId(Long opmcId);
    List<Exchange> findByIsActiveTrue();

    // QA_Compliance_Consolidated_Report.md Stage G Major — same N+1 shape as the rest of the
    // Exchange/Cab/Dp/Circuit family (Exchange -> Opmc via a LAZY proxy), added for consistency.
    // `exchanges` has only 377 rows, so this was never a practical problem on its own.
    @Query("SELECT e FROM Exchange e LEFT JOIN FETCH e.opmc")
    List<Exchange> findAllWithFullChain();

    @Query("SELECT e FROM Exchange e LEFT JOIN FETCH e.opmc WHERE e.isActive = true")
    List<Exchange> findActiveWithFullChain();

    @Query("SELECT e FROM Exchange e LEFT JOIN FETCH e.opmc o WHERE o.id = :opmcId")
    List<Exchange> findByOpmcIdWithFullChain(@Param("opmcId") Long opmcId);

    // H1b: candidate pool for nearest-Exchange matching -- only Exchanges with real coordinates
    // (see fieldops/scripts/geocode_master_data.py; 271/377 as of 2026-08-20, the rest pending).
    // Scoping via the query itself, not a null-check after fetching everything, is what makes it
    // structurally impossible for an un-geocoded Exchange to be silently treated as (0,0).
    List<Exchange> findByLatitudeIsNotNullAndLongitudeIsNotNull();
}
