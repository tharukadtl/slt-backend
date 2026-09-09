package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.JobNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobNoteRepository extends JpaRepository<JobNote, Long> {

    @Query("SELECT n FROM JobNote n WHERE n.jobId = :jobId ORDER BY n.createdAt DESC")
    List<JobNote> findByJobId(@Param("jobId") Long jobId);

    @Query("SELECT n FROM JobNote n WHERE n.jobId = :jobId AND n.isInternal = false "
            + "ORDER BY n.createdAt DESC")
    List<JobNote> findPublicByJobId(@Param("jobId") Long jobId);
}
