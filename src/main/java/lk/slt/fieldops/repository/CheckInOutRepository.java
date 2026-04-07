package lk.slt.fieldops.job.repository;

import lk.slt.fieldops.job.entity.CheckInOut;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CheckInOutRepository extends JpaRepository<CheckInOut, Long> {
    List<CheckInOut> findBySessionIdOrderByCheckTimeAsc(Long sessionId);
    List<CheckInOut> findByTeamLeadIdOrderByCheckTimeDesc(Long teamLeadId);
}
