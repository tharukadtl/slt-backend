package lk.slt.fieldops.fault.repository;

import lk.slt.fieldops.fault.entity.FaultNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * FaultNoteRepository — internal notes on a fault.
 */
@Repository
public interface FaultNoteRepository extends JpaRepository<FaultNote, Long> {

    /** Get all notes for a fault — newest first */
    List<FaultNote> findByFaultIdOrderByCreatedAtDesc(Long faultId);
}
