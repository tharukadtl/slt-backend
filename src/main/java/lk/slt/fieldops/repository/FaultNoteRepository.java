package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.FaultNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FaultNoteRepository
        extends JpaRepository<FaultNote, Long> {

    // ─── Find by Fault ────────────────────────────────────

    @Query("SELECT fn FROM FaultNote fn "
            + "WHERE fn.faultId = :faultId "
            + "ORDER BY fn.createdAt DESC")
    List<FaultNote> findByFaultId(
            @Param("faultId") Long faultId);

    @Query("SELECT fn FROM FaultNote fn "
            + "WHERE fn.faultId = :faultId "
            + "ORDER BY fn.createdAt DESC")
    List<FaultNote> findByFaultIdOrderByCreatedAtDesc(
            @Param("faultId") Long faultId);

    // ─── Find Public Notes ────────────────────────────────

    @Query("SELECT fn FROM FaultNote fn "
            + "WHERE fn.faultId = :faultId "
            + "AND fn.isInternal = false "
            + "ORDER BY fn.createdAt DESC")
    List<FaultNote> findPublicByFaultId(
            @Param("faultId") Long faultId);

    // ─── Find Internal Notes ──────────────────────────────

    @Query("SELECT fn FROM FaultNote fn "
            + "WHERE fn.faultId = :faultId "
            + "AND fn.isInternal = true "
            + "ORDER BY fn.createdAt DESC")
    List<FaultNote> findInternalByFaultId(
            @Param("faultId") Long faultId);

    // ─── Count by Fault ───────────────────────────────────

    @Query("SELECT COUNT(fn) FROM FaultNote fn "
            + "WHERE fn.faultId = :faultId")
    long countByFaultId(
            @Param("faultId") Long faultId);
}
