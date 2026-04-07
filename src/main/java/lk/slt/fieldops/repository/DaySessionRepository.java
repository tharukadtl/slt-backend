package lk.slt.fieldops.job.repository;

import lk.slt.fieldops.job.entity.DaySession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DaySessionRepository extends JpaRepository<DaySession, Long> {

    Optional<DaySession> findByTeamLeadIdAndSessionDate(Long teamLeadId, LocalDate date);

    Optional<DaySession> findByTeamLeadIdAndStatus(Long teamLeadId, DaySession.SessionStatus status);

    boolean existsByTeamLeadIdAndSessionDate(Long teamLeadId, LocalDate date);
}
