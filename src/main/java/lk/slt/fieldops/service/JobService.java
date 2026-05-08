package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.*;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.repository.*;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class JobService {

    private final DaySessionRepository       sessionRepo;
    private final DaySessionMemberRepository memberRepo;
    private final JobRepository              jobRepo;
    private final CheckInOutRepository       checkInOutRepo;
    private final MaterialUsageRepository    materialUsageRepo;
    private final UserRepository             userRepo;
    private final MaterialRepository         materialRepo;
    private final FaultRepository            faultRepo;
    private final NotificationService        notificationService;

    public JobService(DaySessionRepository sessionRepo,
                      DaySessionMemberRepository memberRepo,
                      JobRepository jobRepo,
                      CheckInOutRepository checkInOutRepo,
                      MaterialUsageRepository materialUsageRepo,
                      UserRepository userRepo,
                      MaterialRepository materialRepo,
                      FaultRepository faultRepo,
                      NotificationService notificationService) {
        this.sessionRepo         = sessionRepo;
        this.memberRepo          = memberRepo;
        this.jobRepo             = jobRepo;
        this.checkInOutRepo      = checkInOutRepo;
        this.materialUsageRepo   = materialUsageRepo;
        this.userRepo            = userRepo;
        this.materialRepo        = materialRepo;
        this.faultRepo           = faultRepo;
        this.notificationService = notificationService;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. BOD — Team Lead begins the day
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public DaySession performBod(BodRequest request, Long teamLeadId, String teamLeadName) {
        LocalDate today = LocalDate.now();

        // Auto-close any stale ACTIVE sessions from previous days (handles test/dev leftover data)
        sessionRepo.closeStaleActiveSessions(teamLeadId, today);

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

        // 3. Record team lead check-in using the proper CheckInOut entity fields
        User teamLead = userRepo.findById(teamLeadId)
            .orElseThrow(() -> new ResourceNotFoundException("Team lead not found: " + teamLeadId));

        CheckInOut checkIn = new CheckInOut();
        checkIn.setUser(teamLead);
        checkIn.setSessionId(saved.getId());
        checkIn.setTeamLeadId(teamLeadId);
        checkIn.setCheckType("BOD");
        checkIn.setCheckInTime(LocalDateTime.now());
        checkIn.setCheckInLatitude(request.getLatitude());
        checkIn.setCheckInLongitude(request.getLongitude());
        checkIn.setCheckInAddress(request.getLocationAddress());
        checkIn.setStatus("CHECKED_IN");
        checkInOutRepo.save(checkIn);

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. EOD — Team Lead ends the day
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> performEod(EodRequest request, Long teamLeadId, String teamLeadName) {
        DaySession session = sessionRepo
            .findByTeamLeadIdAndStatus(teamLeadId, DaySession.SessionStatus.ACTIVE)
            .orElseThrow(() -> new RuntimeException(
                "No active session found. You must do BOD before EOD."));

        // Gate: all technicians in the session must have checked out
        List<DaySessionMember> members = memberRepo.findBySessionId(session.getId());
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        List<String> notCheckedOut = new java.util.ArrayList<>();
        for (DaySessionMember member : members) {
            if (!Boolean.TRUE.equals(member.getIsActive())) continue;
            boolean checkedOut = checkInOutRepo
                .findTodayByUserId(member.getTechnicianId(), startOfDay)
                .map(c -> c.getCheckOutTime() != null)
                .orElse(false);
            if (!checkedOut) {
                String name = userRepo.findById(member.getTechnicianId())
                    .map(User::getFullName)
                    .orElse("Technician #" + member.getTechnicianId());
                notCheckedOut.add(name);
            }
        }
        if (!notCheckedOut.isEmpty()) {
            throw new RuntimeException(
                "Cannot complete EOD. The following technicians have not checked out: " +
                String.join(", ", notCheckedOut));
        }

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

        // 4. Update the team lead's check-in record to a check-out
        checkInOutRepo.findActiveCheckInByUserId(teamLeadId).ifPresent(checkIn -> {
            checkIn.setCheckOutTime(LocalDateTime.now());
            checkIn.setStatus("CHECKED_OUT");
            checkIn.setNotes(request.getNotes());
            checkInOutRepo.save(checkIn);
        });

        return Map.of(
            "message",      "EOD completed successfully.",
            "sessionId",    session.getId(),
            "jobsReturned", returnedCount,
            "eodTime",      LocalDateTime.now().toString()
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. CREATE JOB — Team Lead assigns fault to Technician
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Job createJob(CreateJobRequest request, Long teamLeadId, String teamLeadName) {
        DaySession session = sessionRepo
            .findByTeamLeadIdAndStatus(teamLeadId, DaySession.SessionStatus.ACTIVE)
            .orElseThrow(() -> new RuntimeException(
                "You must complete BOD before creating jobs."));

        memberRepo.findActiveMemberForToday(request.getTechnicianId())
            .orElseThrow(() -> new RuntimeException(
                "Technician #" + request.getTechnicianId() +
                " is not in today's active session. " +
                "Only technicians selected at BOD can be assigned jobs."));

        // Load real technician name
        String technicianName = userRepo.findById(request.getTechnicianId())
            .map(u -> u.getFullName())
            .orElse("Technician #" + request.getTechnicianId());

        // Load fault to get customer and fault number
        Fault fault = faultRepo.findById(request.getFaultId())
            .orElseThrow(() -> new RuntimeException(
                "Fault not found: " + request.getFaultId()));

        if (fault.getAssignedTeamLeadId() == null
                || !fault.getAssignedTeamLeadId().equals(teamLeadId)) {
            throw new RuntimeException(
                "Fault #" + request.getFaultId() +
                " is not assigned to you. Only the assigned Team Lead can create a job for this fault.");
        }

        Job job = new Job();
        job.setJobNumber(generateJobNumber());
        job.setFaultId(fault.getId());
        job.setFaultNumber(fault.getFaultNumber() != null
            ? fault.getFaultNumber()
            : "FAULT-" + fault.getId());
        job.setSessionId(session.getId());
        job.setTeamLeadId(teamLeadId);
        job.setTeamLeadName(teamLeadName);
        job.setTechnicianId(request.getTechnicianId());
        job.setTechnicianName(technicianName);
        job.setCustomerId(fault.getCustomerId() != null ? fault.getCustomerId() : 0L);
        job.setDescription(fault.getDescription());
        job.setStatus(Job.JobStatus.PENDING);
        job.setPriority(request.getPriority() != null
            ? parsePriority(request.getPriority())
            : (fault.getPriority() != null
                ? Job.JobPriority.valueOf(fault.getPriority().name())
                : Job.JobPriority.MEDIUM));
        job.setCreatedBy(teamLeadId);
        Job savedJob = jobRepo.save(job);

        // Job created for technician → move fault to IN_PROGRESS
        // (ASSIGNED means "assigned to team lead by admin"; IN_PROGRESS means "job dispatched to technician")
        fault.setStatus(Fault.FaultStatus.IN_PROGRESS);
        faultRepo.save(fault);

        // Notify technician of new assignment
        userRepo.findById(request.getTechnicianId()).ifPresent(tech ->
            notificationService.notifyJobAssigned(
                tech.getId(), tech.getFcmToken(), savedJob.getJobNumber(), savedJob.getId()));

        return savedJob;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. REASSIGN JOB — Team Lead reassigns to a different Technician
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Job reassignJob(Long jobId, Long newTechnicianId, Long teamLeadId) {
        Job job = findJobOrThrow(jobId);

        if (job.getStatus() == Job.JobStatus.COMPLETED ||
            job.getStatus() == Job.JobStatus.CANCELLED) {
            throw new RuntimeException(
                "Cannot reassign a " + job.getStatus() + " job.");
        }

        memberRepo.findActiveMemberForToday(newTechnicianId)
            .orElseThrow(() -> new RuntimeException(
                "Technician #" + newTechnicianId +
                " is not in today's active session."));

        String newTechnicianName = userRepo.findById(newTechnicianId)
            .map(u -> u.getFullName())
            .orElse("Technician #" + newTechnicianId);

        job.setTechnicianId(newTechnicianId);
        job.setTechnicianName(newTechnicianName);
        job.setStatus(Job.JobStatus.PENDING);
        job.setUpdatedBy(teamLeadId);

        return jobRepo.save(job);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. UPDATE JOB STATUS — Technician updates their job
    // ══════════════════════════════════════════════════════════════════════════

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

        if ((newStatus == Job.JobStatus.HOLD || newStatus == Job.JobStatus.REJECTED) &&
            (request.getReason() == null || request.getReason().isBlank())) {
            throw new RuntimeException("A reason is required when setting status to " + newStatus + ".");
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
        if (newStatus == Job.JobStatus.REJECTED) {
            job.setRejectionReason(request.getReason());
            // Determine who rejected — look up role from user
            String rejectorRole = userRepo.findById(userId)
                .map(u -> u.getRole() != null ? u.getRole().name() : "UNKNOWN")
                .orElse("UNKNOWN");
            job.setRejectedByRole(rejectorRole);
            // Return to team lead pool: clear technician assignment
            if ("TECHNICIAN".equals(rejectorRole)) {
                job.setTechnicianId(null);
                job.setTechnicianName(null);
                // Notify team lead that technician rejected the job
                final Long tlId = job.getTeamLeadId();
                final String jobNum = job.getJobNumber();
                final Long jobIdRef = job.getId();
                final String reason = request.getReason();
                userRepo.findById(tlId).ifPresent(tl ->
                    notificationService.notifyJobRejectedToTeamLead(
                        tl.getId(), tl.getFcmToken(), jobNum, jobIdRef, reason));
            }
        }
        if (newStatus == Job.JobStatus.IN_PROGRESS) {
            // Mirror IN_PROGRESS on the linked fault so client sees live status
            if (job.getFaultId() != null) {
                faultRepo.findById(job.getFaultId()).ifPresent(fault -> {
                    if (fault.getStatus() != Fault.FaultStatus.COMPLETED) {
                        fault.setStatus(Fault.FaultStatus.IN_PROGRESS);
                        if (fault.getStartedAt() == null) fault.setStartedAt(LocalDateTime.now());
                        faultRepo.save(fault);
                    }
                });
            }
        }
        if (newStatus == Job.JobStatus.COMPLETED) {
            job.setCompletedAt(LocalDateTime.now());
            job.setCauseOfFault(request.getCauseOfFault());
            job.setCompletionRemarks(request.getCompletionRemarks());

            // Sync completion back to the linked fault
            if (job.getFaultId() != null) {
                faultRepo.findById(job.getFaultId()).ifPresent(fault -> {
                    fault.setStatus(Fault.FaultStatus.COMPLETED);
                    fault.setCompletedAt(LocalDateTime.now());
                    fault.setCauseOfFault(request.getCauseOfFault());
                    fault.setCompletionRemarks(request.getCompletionRemarks());
                    faultRepo.save(fault);

                    // Notify the client that their fault has been resolved
                    userRepo.findById(fault.getCustomerId()).ifPresent(customer ->
                        notificationService.notifyFaultCompletedToClient(
                            customer.getId(), customer.getFcmToken(),
                            fault.getFaultNumber(), fault.getId()));
                });
            }

            // Notify the team lead that the job is done
            final Long tlId = job.getTeamLeadId();
            final String jobNum = job.getJobNumber();
            final Long jobIdRef = job.getId();
            userRepo.findById(tlId).ifPresent(tl ->
                notificationService.notifyJobCompleted(
                    tl.getId(), tl.getFcmToken(), jobNum, jobIdRef));
        }
        // Team lead reassigns a rejected job back to PENDING
        if (newStatus == Job.JobStatus.PENDING) {
            job.setRejectionReason(null);
            job.setRejectedByRole(null);
        }
        if (request.getWorkNotes() != null) {
            job.setWorkNotes(request.getWorkNotes());
        }

        return jobRepo.save(job);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. LOG MATERIAL — Technician logs material used in a job
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public MaterialUsage logMaterial(Long jobId, MaterialUsageRequest request, Long userId) {
        Job job = findJobOrThrow(jobId);

        if (job.getStatus() == Job.JobStatus.COMPLETED ||
            job.getStatus() == Job.JobStatus.CANCELLED) {
            throw new RuntimeException(
                "Cannot log materials for a " + job.getStatus() + " job.");
        }

        // Load real material name
        String materialName = materialRepo.findById(request.getMaterialId())
            .map(m -> m.getName())
            .orElse("Material #" + request.getMaterialId());

        MaterialUsage usage = new MaterialUsage();
        usage.setJobId(jobId);
        usage.setJobNumber(job.getJobNumber());
        usage.setMaterialId(request.getMaterialId());
        usage.setMaterialName(materialName);
        usage.setQuantityUsed(request.getQuantityUsed());
        usage.setChargeType(parseChargeType(request.getChargeType()));
        usage.setJustification(request.getJustification());
        usage.setRecordedBy(userId);

        return materialUsageRepo.save(usage);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. READ METHODS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Job> getAllJobs() {
        return jobRepo.findAll(
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

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
        return materialUsageRepo.findByJobId(jobId);
    }

    @Transactional(readOnly = true)
    public boolean isTechnicianActiveToday(Long technicianId) {
        return memberRepo.findActiveMemberForToday(technicianId).isPresent();
    }

    @Transactional(readOnly = true)
    public List<User> getTeamMembersForTeamLead(Long teamLeadId) {
        return sessionRepo
            .findByTeamLeadIdAndSessionDate(teamLeadId, LocalDate.now())
            .map(session -> {
                List<Long> memberIds = memberRepo.findBySessionId(session.getId())
                    .stream()
                    .map(DaySessionMember::getTechnicianId)
                    .collect(Collectors.toList());
                return userRepo.findAllById(memberIds);
            })
            .orElse(java.util.Collections.emptyList());
    }

    @Transactional
    public Job submitSignature(Long jobId, String signature, Long userId) {
        Job job = findJobOrThrow(jobId);
        job.setCompletionSignature(signature);
        job.setUpdatedBy(userId);
        return jobRepo.save(job);
    }

    @Transactional(readOnly = true)
    public List<CheckInOut> getCheckInOutForSession(Long sessionId) {
        DaySession session = sessionRepo.findById(sessionId)
            .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));

        List<Long> memberIds = memberRepo.findBySessionId(sessionId).stream()
            .map(DaySessionMember::getTechnicianId)
            .collect(Collectors.toList());

        // Include the team lead
        memberIds.add(session.getTeamLeadId());

        LocalDate sessionDate = session.getSessionDate();
        LocalDateTime startOfDay = sessionDate.atStartOfDay();
        LocalDateTime endOfDay   = sessionDate.atTime(23, 59, 59);

        return checkInOutRepo.findByUserIdsAndDateRange(memberIds, startOfDay, endOfDay);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TECHNICIAN CHECKOUT STATUS — for EOD screen
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<lk.slt.fieldops.dto.TechnicianCheckoutStatusDTO> getTechnicianCheckoutStatus(Long sessionId) {
        List<DaySessionMember> members = memberRepo.findBySessionId(sessionId);
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

        return members.stream()
            .filter(m -> Boolean.TRUE.equals(m.getIsActive()))
            .map(m -> {
                String name = userRepo.findById(m.getTechnicianId())
                    .map(User::getFullName)
                    .orElse("Technician #" + m.getTechnicianId());

                return checkInOutRepo
                    .findTodayByUserId(m.getTechnicianId(), startOfDay)
                    .map(c -> new lk.slt.fieldops.dto.TechnicianCheckoutStatusDTO(
                        m.getTechnicianId(), name,
                        true,
                        c.getCheckOutTime() != null,
                        c.getCheckInTime()  != null ? c.getCheckInTime().toString()  : null,
                        c.getCheckOutTime() != null ? c.getCheckOutTime().toString() : null))
                    .orElse(new lk.slt.fieldops.dto.TechnicianCheckoutStatusDTO(
                        m.getTechnicianId(), name, false, false, null, null));
            })
            .collect(Collectors.toList());
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
            case PENDING     -> requested == Job.JobStatus.ACCEPTED   ||
                                requested == Job.JobStatus.REJECTED   ||
                                requested == Job.JobStatus.CANCELLED;
            case ACCEPTED    -> requested == Job.JobStatus.IN_PROGRESS ||
                                requested == Job.JobStatus.REJECTED   ||
                                requested == Job.JobStatus.CANCELLED;
            case IN_PROGRESS -> requested == Job.JobStatus.HOLD       ||
                                requested == Job.JobStatus.COMPLETED  ||
                                requested == Job.JobStatus.REJECTED   ||
                                requested == Job.JobStatus.CANCELLED;
            case HOLD        -> requested == Job.JobStatus.IN_PROGRESS ||
                                requested == Job.JobStatus.REJECTED   ||
                                requested == Job.JobStatus.CANCELLED;
            // Team lead can reassign rejected jobs (back to PENDING)
            case REJECTED    -> requested == Job.JobStatus.PENDING    ||
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
