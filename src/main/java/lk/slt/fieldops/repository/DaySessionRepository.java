package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.DaySession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DaySessionRepository extends JpaRepository<DaySession, Long> {

    Optional<DaySession> findByTeamLeadIdAndSessionDate(Long teamLeadId, LocalDate date);

    /** Returns the most recent ACTIVE session for a team lead — avoids NonUniqueResult when stale sessions exist */
    @Query("SELECT ds FROM DaySession ds WHERE ds.teamLeadId = :teamLeadId AND ds.status = :status ORDER BY ds.id DESC")
    List<DaySession> findAllByTeamLeadIdAndStatus(Long teamLeadId, DaySession.SessionStatus status);

    default Optional<DaySession> findByTeamLeadIdAndStatus(Long teamLeadId, DaySession.SessionStatus status) {
        List<DaySession> results = findAllByTeamLeadIdAndStatus(teamLeadId, status);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    boolean existsByTeamLeadIdAndSessionDate(Long teamLeadId, LocalDate date);

    /** Close all stale ACTIVE sessions from previous days before starting a new BOD */
    @Modifying
    @Transactional
    @Query("UPDATE DaySession ds SET ds.status = 'CLOSED' WHERE ds.teamLeadId = :teamLeadId AND ds.status = 'ACTIVE' AND ds.sessionDate < :today")
    int closeStaleActiveSessions(Long teamLeadId, LocalDate today);
}
