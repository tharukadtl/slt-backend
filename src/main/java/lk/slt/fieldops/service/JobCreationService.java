package lk.slt.fieldops.service;

import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobCreationService {

    private final JobRepository jobRepository;

    /**
     * Creates a job record when a fault is assigned to a technician.
     * Runs in REQUIRES_NEW so any DB failure here does not roll back the
     * caller's fault-assignment transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createJobForFaultAssignment(Fault fault, User technician, String priority) {
        try {
            Job job = new Job();
            job.setJobNumber("ASSIGN-" + fault.getId() + "-" + System.currentTimeMillis());
            job.setFaultId(fault.getId());
            job.setFaultNumber(fault.getFaultNumber() != null
                    ? fault.getFaultNumber()
                    : "FAULT-" + fault.getId());
            job.setTechnicianId(technician.getId());
            job.setTechnicianName(technician.getFullName());
            job.setTeamLeadId(technician.getId());
            job.setCustomerId(fault.getCustomerId() != null ? fault.getCustomerId() : 0L);
            job.setStatus(Job.JobStatus.PENDING);
            if (priority != null) {
                try {
                    job.setPriority(Job.JobPriority.valueOf(priority.toUpperCase()));
                } catch (Exception e) {
                    // default MEDIUM stays
                }
            }
            jobRepository.save(job);
            log.debug("Job created for fault {} technician {}", fault.getId(), technician.getId());
        } catch (Exception e) {
            log.error("Job creation failed for fault {} — assignment will still succeed. Error: {}",
                    fault.getId(), e.getMessage());
        }
    }
}
