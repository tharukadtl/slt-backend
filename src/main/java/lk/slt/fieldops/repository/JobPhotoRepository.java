package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.JobPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobPhotoRepository extends JpaRepository<JobPhoto, Long> {

    /** The upload-time record for a URL not yet claimed by any job. */
    Optional<JobPhoto> findFirstByUrlAndJobIdIsNull(String url);

    List<JobPhoto> findByJobIdOrderByUploadedAtAsc(Long jobId);
}
