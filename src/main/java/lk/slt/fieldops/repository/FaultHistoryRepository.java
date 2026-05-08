package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import org.springframework.data.jpa.repository
        .JpaRepository;
import org.springframework.data.jpa.repository
        .Query;
import org.springframework.data.repository.query
        .Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FaultHistoryRepository extends JpaRepository<FaultHistory, Long> {

        // ─── Find by Fault ────────────────────────────────────

        List<FaultHistory>
    findByFaultOrderByCreatedAtDesc(Fault fault);

@Query("SELECT fh FROM FaultHistory fh "
        + "WHERE fh.fault.id = :faultId "
        + "ORDER BY fh.createdAt DESC")
    List<FaultHistory> findByFaultId(
@Param("faultId") Long faultId);

// ─── Find by Event Type ───────────────────────────────

@Query("SELECT fh FROM FaultHistory fh "
        + "WHERE fh.fault.id = :faultId "
        + "AND fh.eventType = :eventType "
        + "ORDER BY fh.createdAt DESC")
    List<FaultHistory> findByFaultIdAndEventType(
@Param("faultId") Long faultId,
@Param("eventType") String eventType);

// ─── Find Recent ──────────────────────────────────────

@Query("SELECT fh FROM FaultHistory fh "
        + "ORDER BY fh.createdAt DESC")
    List<FaultHistory> findAllRecent();

        // ─── Find Ordered Ascending (for timeline) ────────────

@Query("SELECT fh FROM FaultHistory fh "
        + "WHERE fh.fault.id = :faultId "
        + "ORDER BY fh.createdAt ASC")
        List<FaultHistory> findByFaultIdOrderByChangedAtAsc(
@Param("faultId") Long faultId);

        // ─── Count by Fault ───────────────────────────────────

        long countByFault(Fault fault);
        }