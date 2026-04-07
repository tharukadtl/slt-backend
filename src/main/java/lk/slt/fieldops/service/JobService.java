package lk.slt.fieldops.job.service;

import lk.slt.fieldops.job.dto.*;
import lk.slt.fieldops.job.entity.*;
import lk.slt.fieldops.job.repository.*;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * JobService — handles the entire BOD/EOD/Job workflow.
 *
 * Methods:
 *   performBod()         → Team Lead starts the day
 *   performEod()         → Team Lead ends the day (auto-returns all open jobs)
 *   createJob()          → Team Lead assigns fault to Technician
 *   updateJobStatus()    → Technician updates job (ACCEPTED/IN_PROGRESS/HOLD/COMPLETED)
 *   logMaterial()        → Technician logs material used
 *   getTodaysJobs()      → Team Lead/Technician gets today's jobs
 *   getJobById()         → Get single job
 *   getMaterialsForJob() → Get materials logged against a job
 *   getSessionMembers()  → Get technicians in today's session
 *   isTechnicianActive() → Gate check for Technician login
 */
@Service
public class JobService {

    private final DaySessionRepository       sessionRepo;
    private final DaySessionMemberRepository memberRepo;
    private final JobRepository              jobRepo;
    private final CheckInOutRepository       checkInOutRepo;
    private final MaterialUsageRepository    materialRepo;

    public JobService(DaySessionRepository sessionRepo,
                      DaySessionMemberRepository memberRepo,
                      JobRepository jobRepo,
                      CheckInOutRepository checkInOutRepo,
                      MaterialUsageRepository materialRepo) {
        this.sessionRepo    = sessionRepo;
        this.memberRepo     = memberRepo;
        this.jobRepo        = jobRepo;
        this.checkInOutRepo = checkInOutRepo;
        this.materialRepo   = materialRepo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. BOD — Team Lead begins the day
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Team Lead performs BOD:
     *   1. Validates no BOD already done today
     *   2. Creates DaySession record
     *   3. Creates DaySessionMember for each technician
     *   4. Records CheckInOut (CHECK_IN)
     *
     * After this, technicians in the list can log in.
     */
    @Transactional
    public DaySession performBod(BodRequest request, Long teamLeadId, String teamLeadName) {
        LocalDate today = LocalDate.now();

        // Guard: only one BOD per day per Team Lead
        if (sessionRepo.existsByTeamLeadIdAndSessionDate(teamLeadId, today)) {
            throw new RuntimeException(
                "BOD already completed for today. You cannot do BOD twice in one day.");
        }

        if (request.getTechnicianIds() == null || request.getTechnicianIds().isEmpty()) {
            throw new RuntimeException(
                "You must select at least one technician for today's session.");
        }

        // 1. Create the day session
        DaySession session = new DaySession();
        session.setTeamLeadId(teamLeadId);
        session.setStatus(DaySession.SessionStatus.ACTIVE);
        session.setBodTime(LocalDateTime.now());
        session.setBodLatitude(request.getLatitude());
        session.setBodLongitude(request.getLongitude());
        session.setBodVehicleId(request.getVehicleId());
        session.setBodOdometer(request.getOdometerStart());
        DaySession saved = sessionRepo.save(session);

        // 2. Add each technician as a session member
        for (Long techId : request.getTechnicianIds()) {
            DaySessionMember member = new DaySessionMember();
            member.setSessionId(saved.getId());
            member.setTechnicianId(techId);
            member.setIsActive(true);
            memberRepo.save(member);
        }

        // 3. Record check-in
        CheckInOut checkIn = new CheckInOut();
        checkIn.setSessionId(saved.getId());
        checkIn.setTeamLeadId(teamLeadId);
        checkIn.setTeamLeadName(teamLeadName);
        checkIn.setCheckType(CheckInOut.CheckType.CHECK_IN);
        checkIn.setCheckTime(LocalDateTime.now());
        checkIn.setLatitude(request.getLatitude());
        checkIn.setLongitude(request.getLongitude());
        checkIn.setLocationAddress(request.getLocationAddress());
        checkIn.setVehicleId(request.getVehicleId());
        checkIn.setOdometerReading(request.getOdometerStart());
        checkInOutRepo.save(checkIn);

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. EOD — Team Lead ends the day
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Team Lead performs EOD:
     *   1. Returns all PENDING/ACCEPTED/IN_PROGRESS/HOLD jobs back to TL
     *   2. Deactivates all session members (technicians can't log in after this)
     *   3. Closes the day session
     *   4. Records CheckInOut (CHECK_OUT)
     *
     * Returns a summary of how many jobs were auto-returned.
     */
    @Transactional
    public Map<String, Object> performEod(EodRequest request, Long teamLeadId, String teamLeadName) {
        DaySession session = sessionRepo
            .findByTeamLeadIdAndStatus(teamLeadId, DaySession.SessionStatus.ACTIVE)
            .orElseThrow(() -> new RuntimeException(
                "No active session found. You must do BOD before EOD."));

        // 1. Return all open jobs to Team Lead
        int returnedCount = jobRepo.returnAllJobsToTeamLead(session.getId());

        // 2. Deactivate all session members
        memberRepo.deactivateAllMembers(session.getId());

        // 3. Close the session
        session.setStatus(DaySession.SessionStatus.CLOSED);
        session.setEodTime(LocalDateTime.now());
        session.setEodOdometer(request.getOdometerEnd());
        session.setEodNotes(request.getNotes());
        sessionRepo.save(session);

        // 4. Record check-out
        CheckInOut checkOut = new CheckInOut();
        checkOut.setSessionId(session.getId());
        checkOut.setTeamLeadId(teamLeadId);
        checkOut.setTeamLeadName(teamLeadName);
        checkOut.setCheckType(CheckInOut.CheckType.CHECK_OUT);
        checkOut.setCheckTime(LocalDateTime.now());
        checkOut.setVehicleId(session.getBodVehicleId());
        checkOut.setOdometerReading(request.getOdometerEnd());
        checkOut.setNotes(request.getNotes());
        checkInOutRepo.save(checkOut);

        return Map.of(
            "message",       "EOD completed successfully.",
            "sessionId",     session.getId(),
            "jobsReturned",  returnedCount,
            "eodTime",       LocalDateTime.now().toString()
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. CREATE JOB — Team Lead assigns fault to Technician
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Team Lead creates a job from a fault and assigns it to a Technician.
     * The technician must be in today's active session.
     */
    @Transactional
    public Job createJob(CreateJobRequest request, Long teamLeadId, String teamLeadName) {
        // Verify TL has an active session
        DaySession session = sessionRepo
            .findByTeamLeadIdAndStatus(teamLeadId, DaySession.SessionStatus.ACTIVE)
            .orElseThrow(() -> new RuntimeException(
                "You must complete BOD before creating jobs."));

        // Verify technician is in today's session
        memberRepo.findActiveMemberForToday(request.getTechnicianId())
            .orElseThrow(() -> new RuntimeException(
                "Technician #" + request.getTechnicianId() +
                " is not in today's active session. " +
                "Only technicians selected at BOD can be assigned jobs."));

        Job job = new Job();
        job.setJobNumber(generateJobNumber());
        job.setFaultId(request.getFaultId());
        job.setSessionId(session.getId());
        job.setTeamLeadId(teamLeadId);
        job.setTeamLeadName(teamLeadName);
        job.setTechnicianId(request.getTechnicianId());
        job.setTechnicianName("Technician #" + request.getTechnicianId()); // TODO Phase 3: load from User
        job.setCustomerId(0L);     // TODO Phase 3: load from Fault
        job.setStatus(Job.JobStatus.PENDING);
        job.setPriority(parsePriority(request.getPriority()));
        job.setCreatedBy(teamLeadId);

        return jobRepo.save(job);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. UPDATE JOB STATUS — Technician updates their job
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Technician updates job status.
     * Status rules:
     *   PENDING     → ACCEPTED
     *   ACCEPTED    → IN_PROGRESS
     *   IN_PROGRESS → HOLD or COMPLETED
     *   HOLD        → IN_PROGRESS
     */
    @Transactional
    public Job updateJobStatus(Long jobId, UpdateJobRequest request, Long userId) {
        Job job = findJobOrThrow(jobId);

        Job.JobStatus newStatus;
        try {
            newStatus = Job.JobStatus.valueOf(request.getNewStatus());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid status: " + request.getNewStatus() +
                ". Valid: ACCEPTED, IN_PROGRESS, HOLD, COMPLETED, CANCELLED");
        }

        validateJobTransition(job.getStatus(), newStatus);

        if (newStatus == Job.JobStatus.HOLD &&
            (request.getReason() == null || request.getReason().isBlank())) {
            throw new RuntimeException("A reason is required when putting a job on HOLD.");
        }

        job.setStatus(newStatus);
        job.setUpdatedBy(userId);

        if (newStatus == Job.JobStatus.ACCEPTED && job.getAcceptedAt() == null) {
            job.setAcceptedAt(LocalDateTime.now());
        }
        if (newStatus == Job.JobStatus.IN_PROGRESS && job.getStartedAt() == null) {
            job.setStartedAt(LocalDateTime.now());
        }
        if (newStatus == Job.JobStatus.HOLD) {
            job.setHoldAt(LocalDateTime.now());
            job.setHoldReason(request.getReason());
        }
        if (newStatus == Job.JobStatus.COMPLETED) {
            job.setCompletedAt(LocalDateTime.now());
            job.setCauseOfFault(request.getCauseOfFault());
            job.setCompletionRemarks(request.getCompletionRemarks());
        }
        if (request.getWorkNotes() != null) {
            job.setWorkNotes(request.getWorkNotes());
        }

        return jobRepo.save(job);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. LOG MATERIAL — Technician logs material used in a job
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public MaterialUsage logMaterial(Long jobId, MaterialUsageRequest request, Long userId) {
        Job job = findJobOrThrow(jobId);

        if (job.getStatus() == Job.JobStatus.COMPLETED ||
            job.getStatus() == Job.JobStatus.CANCELLED) {
            throw new RuntimeException(
                "Cannot log materials for a " + job.getStatus() + " job.");
        }

        MaterialUsage usage = new MaterialUsage();
        usage.setJobId(jobId);
        usage.setJobNumber(job.getJobNumber());
        usage.setMaterialId(request.getMaterialId());
        usage.setMaterialName("Material #" + request.getMaterialId()); // TODO Phase 5: load from Material
        usage.setQuantityUsed(request.getQuantityUsed());
        usage.setChargeType(parseChargeType(request.getChargeType()));
        usage.setJustification(request.getJustification());
        usage.setRecordedBy(userId);

        return materialRepo.save(usage);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. READ METHODS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Job getJobById(Long id) {
        return findJobOrThrow(id);
    }

    @Transactional(readOnly = true)
    public List<Job> getTodaysJobsForTeamLead(Long teamLeadId) {
        return jobRepo.findByTeamLeadIdAndScheduledDate(teamLeadId, LocalDate.now());
    }

    @Transactional(readOnly = true)
    public List<Job> getTodaysJobsForTechnician(Long technicianId) {
        return jobRepo.findByTechnicianIdAndScheduledDate(technicianId, LocalDate.now());
    }

    @Transactional(readOnly = true)
    public DaySession getTodaysSession(Long teamLeadId) {
        return sessionRepo
            .findByTeamLeadIdAndSessionDate(teamLeadId, LocalDate.now())
            .orElseThrow(() -> new RuntimeException(
                "No session found for today. Please complete BOD first."));
    }

    @Transactional(readOnly = true)
    public List<DaySessionMember> getSessionMembers(Long sessionId) {
        return memberRepo.findBySessionId(sessionId);
    }

    @Transactional(readOnly = true)
    public List<MaterialUsage> getMaterialsForJob(Long jobId) {
        findJobOrThrow(jobId);
        return materialRepo.findByJobId(jobId);
    }

    /**
     * Gate check: is this technician in an active session today?
     * Called by the auth module during Technician login.
     */
    @Transactional(readOnly = true)
    public boolean isTechnicianActiveToday(Long technicianId) {
        return memberRepo.findActiveMemberForToday(technicianId).isPresent();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Job findJobOrThrow(Long id) {
        return jobRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id));
    }

    private void validateJobTransition(Job.JobStatus current, Job.JobStatus requested) {
        boolean valid = switch (current) {
            case PENDING     -> requested == Job.JobStatus.ACCEPTED  ||
                                requested == Job.JobStatus.CANCELLED;
            case ACCEPTED    -> requested == Job.JobStatus.IN_PROGRESS ||
                                requested == Job.JobStatus.CANCELLED;
            case IN_PROGRESS -> requested == Job.JobStatus.HOLD      ||
                                requested == Job.JobStatus.COMPLETED ||
                                requested == Job.JobStatus.CANCELLED;
            case HOLD        -> requested == Job.JobStatus.IN_PROGRESS ||
                                requested == Job.JobStatus.CANCELLED;
            case COMPLETED   -> false;
            case CANCELLED   -> false;
        };
        if (!valid) {
            throw new RuntimeException("Invalid job transition: " +
                current + " → " + requested);
        }
    }

    private String generateJobNumber() {
        int year  = LocalDateTime.now().getYear();
        long count = jobRepo.countJobsByYear(year) + 1;
        return String.format("JOB-%d-%05d", year, count);
    }

    private Job.JobPriority parsePriority(String p) {
        if (p == null || p.isBlank()) return Job.JobPriority.MEDIUM;
        try { return Job.JobPriority.valueOf(p); }
        catch (Exception e) { return Job.JobPriority.MEDIUM; }
    }

    private MaterialUsage.ChargeType parseChargeType(String c) {
        if (c == null || c.isBlank()) return MaterialUsage.ChargeType.FOC;
        try { return MaterialUsage.ChargeType.valueOf(c); }
        catch (Exception e) { return MaterialUsage.ChargeType.FOC; }
    }
}
