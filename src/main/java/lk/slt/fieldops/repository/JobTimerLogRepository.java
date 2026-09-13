package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.JobTimerLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobTimerLogRepository extends JpaRepository<JobTimerLog, Long> {

    List<JobTimerLog> findByJobId(Long jobId);

    Optional<JobTimerLog> findByJobIdAndStoppedAtIsNull(Long jobId);
}
