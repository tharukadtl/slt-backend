package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.DaySessionMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public interface DaySessionMemberRepository extends JpaRepository<DaySessionMember, Long> {

    List<DaySessionMember> findBySessionId(Long sessionId);

    /**
     * KEY CHECK (SRS §5.1): Is this technician in today's active session?
     * Called during Technician login to gate access.
     */
    @Query("SELECT dsm FROM DaySessionMember dsm " +
           "JOIN DaySession ds ON dsm.sessionId = ds.id " +
           "WHERE dsm.technicianId = :techId " +
           "AND ds.sessionDate = CURRENT_DATE " +
           "AND ds.status = 'ACTIVE' " +
           "AND dsm.isActive = TRUE")
    Optional<DaySessionMember> findActiveMemberForToday(Long techId);

    /**
     * Same as findActiveMemberForToday, but scoped to a specific team lead's
     * session — used to confirm a technician is actually on THIS team before
     * a job gets reassigned to them.
     */
    @Query("SELECT dsm FROM DaySessionMember dsm " +
           "JOIN DaySession ds ON dsm.sessionId = ds.id " +
           "WHERE dsm.technicianId = :techId " +
           "AND ds.teamLeadId = :teamLeadId " +
           "AND ds.sessionDate = CURRENT_DATE " +
           "AND ds.status = 'ACTIVE' " +
           "AND dsm.isActive = TRUE")
    Optional<DaySessionMember> findActiveMemberForTeamLeadToday(Long teamLeadId, Long techId);

    /** Deactivate all members at EOD */
    @Modifying
    @Transactional
    @Query("UPDATE DaySessionMember dsm SET dsm.isActive = FALSE WHERE dsm.sessionId = :sessionId")
    void deactivateAllMembers(Long sessionId);
}
