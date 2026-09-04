package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.*;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.repository.*;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.shared.exception.DuplicateSessionException;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final FaultHistoryRepository     faultHistoryRepo;
    private final MaterialRequestRepository  materialRequestRepo;
    private final NotificationService        notificationService;

    public JobService(DaySessionRepository sessionRepo,
                      DaySessionMemberRepository memberRepo,
                      JobRepository jobRepo,
                      CheckInOutRepository checkInOutRepo,
                      MaterialUsageRepository materialUsageRepo,
                      UserRepository userRepo,
                      MaterialRepository materialRepo,
                      FaultRepository faultRepo,
                      FaultHistoryRepository faultHistoryRepo,
                      MaterialRequestRepository materialRequestRepo,
                      NotificationService notificationService) {
        this.sessionRepo         = sessionRepo;
        this.memberRepo          = memberRepo;
        this.jobRepo             = jobRepo;
        this.checkInOutRepo      = checkInOutRepo;
        this.materialUsageRepo   = materialUsageRepo;
        this.userRepo            = userRepo;
        this.materialRepo        = materialRepo;
        this.faultRepo           = faultRepo;
        this.faultHistoryRepo    = faultHistoryRepo;
        this.materialRequestRepo = materialRequestRepo;
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
            throw new DuplicateSessionException("You have already checked in today.");
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

        DaySession saved;
        try {
            saved = sessionRepo.save(session);
        } catch (DataIntegrityViolationException e) {
            // Race: another request created today's session between the exists() check above and this save
            throw new DuplicateSessionException("You have already checked in today.");
        }

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

        // 1. Route this Team Lead's still-open jobs per FR-20 / SRS 5.4.2.1 (EOD Routing
        //    Options) instead of always silently reopening them under the same Team
        //    Lead. Defaults to CARRY_OVER when the caller doesn't send routingOption,
        //    which reproduces the exact pre-fix behavior for any client not yet updated.
        EodRequest.RoutingOption routingOption = request.getRoutingOption() != null
            ? request.getRoutingOption() : EodRequest.RoutingOption.CARRY_OVER;

        List<Job> openJobs = jobRepo.findIncompleteJobsInSession(session.getId());
        int returnedCount = routeOpenJobsAtEod(openJobs, routingOption,
            request.getReassignToTeamLeadId(), teamLeadId, teamLeadName, request.getNotes());

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
            "message",       "EOD completed successfully.",
            "sessionId",     session.getId(),
            "jobsReturned",  returnedCount,
            "routingOption", routingOption.name(),
            "eodTime",       LocalDateTime.now().toString()
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2b. FR-20 (SRS 5.4.2.1) — EOD Routing Options for a Team Lead's open jobs
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Routes a Team Lead's still-open jobs at EOD to one of three distinct,
     * auditable destinations — each writes its own FaultHistory event (surfaced
     * by REP-10 Audit Trail) instead of all three being folded into the same
     * silent "return to pool" behavior.
     */
    private int routeOpenJobsAtEod(List<Job> openJobs, EodRequest.RoutingOption option,
                                    Long reassignToTeamLeadId, Long teamLeadId, String teamLeadName,
                                    String notes) {
        if (openJobs.isEmpty()) {
            return 0;
        }

        User actor = userRepo.findById(teamLeadId).orElse(null);
        // SRS 5.4.2.1 — "reassign... with a reason for the transfer": reuses the
        // EOD screen's existing notes field rather than adding a new one, since
        // that's already the Team Lead's one free-text explanation for the day.
        String reasonSuffix = (notes != null && !notes.isBlank())
            ? " Team Lead's note: " + notes.trim() : "";

        switch (option) {
            case FORWARD_TO_ADMIN:
                for (Job job : openJobs) {
                    job.setStatus(Job.JobStatus.CANCELLED);
                    job.setRejectionReason(
                        "Forwarded to Admin for reassignment at EOD by " + teamLeadName + ".");
                    job.setRejectedByRole("TEAM_LEAD");
                    jobRepo.save(job);

                    faultRepo.findById(job.getFaultId()).ifPresent(fault -> {
                        // Don't resurrect a fault that's already terminal — only pull back
                        // ones this Team Lead's incomplete job actually still represents.
                        if (fault.getStatus() != Fault.FaultStatus.COMPLETED
                                && fault.getStatus() != Fault.FaultStatus.CANCELLED) {
                            String previousTeamLead = fault.getAssignedTeamLeadName();
                            fault.setStatus(Fault.FaultStatus.REPORTED);
                            fault.setAssignedTeamLeadId(null);
                            fault.setAssignedTeamLeadName(null);
                            fault.setAssignedAt(null);
                            faultRepo.save(fault);

                            logJobRoutingHistory(fault, actor, "EOD_FORWARDED_TO_ADMIN",
                                "Forwarded to Admin at EOD",
                                "Job " + job.getJobNumber() + " was still open at EOD and was " +
                                "forwarded to Admin for reassignment by " + teamLeadName + "." + reasonSuffix,
                                previousTeamLead, "Admin queue (unassigned)");
                        }
                    });
                }
                break;

            case REASSIGN_TEAM_LEAD:
                if (reassignToTeamLeadId == null) {
                    throw new RuntimeException(
                        "reassignToTeamLeadId is required when routingOption is REASSIGN_TEAM_LEAD.");
                }
                if (reassignToTeamLeadId.equals(teamLeadId)) {
                    throw new RuntimeException("Cannot reassign open jobs to yourself.");
                }
                User newTeamLead = userRepo.findById(reassignToTeamLeadId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                        "Team lead not found: " + reassignToTeamLeadId));
                if (newTeamLead.getRole() != User.Role.TEAM_LEAD
                        || !Boolean.TRUE.equals(newTeamLead.getIsActive())) {
                    throw new RuntimeException(
                        "reassignToTeamLeadId must belong to an active Team Lead.");
                }

                LocalDate reassignDate = LocalDate.now().plusDays(1);
                for (Job job : openJobs) {
                    String previousTeamLeadName = job.getTeamLeadName();
                    job.setTeamLeadId(reassignToTeamLeadId);
                    job.setTeamLeadName(newTeamLead.getFullName());
                    job.setStatus(Job.JobStatus.PENDING);
                    job.setTechnicianId(null);
                    job.setTechnicianName(null);
                    job.setScheduledDate(reassignDate);
                    job.setSessionId(null);
                    jobRepo.save(job);

                    Fault fault = faultRepo.findById(job.getFaultId()).orElse(null);
                    if (fault != null) {
                        fault.setAssignedTeamLeadId(reassignToTeamLeadId);
                        fault.setAssignedTeamLeadName(newTeamLead.getFullName());
                        faultRepo.save(fault);
                    }

                    logJobRoutingHistory(fault, actor, "EOD_REASSIGNED_TEAM_LEAD",
                        "Reassigned to another Team Lead at EOD",
                        "Job " + job.getJobNumber() + " was still open at EOD and was reassigned " +
                        "from " + previousTeamLeadName + " to " + newTeamLead.getFullName() + "." + reasonSuffix,
                        previousTeamLeadName, newTeamLead.getFullName());

                    notificationService.notifyUser(reassignToTeamLeadId, newTeamLead.getFcmToken(),
                        Notification.NotificationType.GENERAL, "Job Reassigned to You",
                        "Job " + job.getJobNumber() + " was reassigned to you at EOD by " +
                        teamLeadName + ".", job.getId(), "JOB");
                }
                break;

            case CARRY_OVER:
            default:
                LocalDate carryOverDate = LocalDate.now().plusDays(1);
                for (Job job : openJobs) {
                    job.setStatus(Job.JobStatus.PENDING);
                    job.setTechnicianId(null);
                    job.setTechnicianName(null);
                    job.setScheduledDate(carryOverDate);
                    jobRepo.save(job);

                    faultRepo.findById(job.getFaultId()).ifPresent(fault ->
                        logJobRoutingHistory(fault, actor, "EOD_CARRY_OVER",
                            "Carried over to next day",
                            "Job " + job.getJobNumber() + " was still open at EOD and was carried " +
                            "over to " + carryOverDate + " under " + teamLeadName + "." + reasonSuffix,
                            LocalDate.now().toString(), carryOverDate.toString()));
                }
                break;
        }

        return openJobs.size();
    }

    private void logJobRoutingHistory(Fault fault, User actor, String eventType, String title,
                                       String description, String previousValue, String newValue) {
        if (fault == null) {
            return;
        }
        FaultHistory history = FaultHistory.builder()
            .fault(fault)
            .faultNumber(fault.getFaultNumber())
            .actor(actor)
            .eventType(eventType)
            .title(title)
            .description(description)
            .previousValue(previousValue)
            .newValue(newValue)
            .isSystem(false)
            .ipAddress(lk.slt.fieldops.shared.RequestContext.getClientIp())
            .build();
        faultHistoryRepo.save(history);
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

        User teamLead = userRepo.findById(teamLeadId)
            .orElseThrow(() -> new ResourceNotFoundException("Team lead not found: " + teamLeadId));

        User technician = userRepo.findById(request.getTechnicianId())
            .orElseThrow(() -> new RuntimeException(
                "Technician not found: " + request.getTechnicianId()));
        String technicianName = technician.getFullName();

        // Load fault to get customer and fault number
        Fault fault = faultRepo.findById(request.getFaultId())
            .orElseThrow(() -> new RuntimeException(
                "Fault not found: " + request.getFaultId()));

        // SRS 5.5.1 (Stage D): a fault is dispatchable once it's in this Team
        // Lead's Work Group's queue — no separate self-assign step required first.
        if (fault.getWorkGroupId() == null
                || teamLead.getWorkgroup() == null
                || !fault.getWorkGroupId().equals(teamLead.getWorkgroup().getId())) {
            throw new RuntimeException(
                "Fault #" + request.getFaultId() +
                " is not in your Work Group's queue. Only a Work Group's own Team Lead can dispatch it.");
        }

        // JOB-030 — a Team Lead may only dispatch to a Technician in their OWN
        // Work Group, never across Work Groups, even one they're personally
        // acquainted with or that's active in the same BOD session by mistake.
        if (technician.getWorkgroup() == null || teamLead.getWorkgroup() == null
                || !technician.getWorkgroup().getId().equals(teamLead.getWorkgroup().getId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Technician #" + request.getTechnicianId() + " does not belong to your Work Group.");
        }

        // Dispatching to a Technician is itself how a Team Lead claims a
        // Work-Group-queued fault when they didn't explicitly self-assign first.
        if (fault.getAssignedTeamLeadId() == null) {
            fault.setAssignedTeamLeadId(teamLeadId);
            fault.setAssignedTeamLeadName(teamLeadName);
            faultRepo.save(fault);
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
    public Job reassignJob(Long jobId, Long newTechnicianId, Long callerId) {
        Job job = findJobOrThrow(jobId);

        if (job.getStatus() == Job.JobStatus.COMPLETED ||
            job.getStatus() == Job.JobStatus.CANCELLED) {
            throw new RuntimeException(
                "Cannot reassign a " + job.getStatus() + " job.");
        }

        // Guard: a Team Lead may only reassign jobs that belong to their own team.
        // (ADMIN/SUPER_ADMIN bypass this — they can reassign across teams.)
        String callerRole = userRepo.findById(callerId)
            .map(u -> u.getRole() != null ? u.getRole().name() : "UNKNOWN")
            .orElse("UNKNOWN");
        if ("TEAM_LEAD".equals(callerRole) && !callerId.equals(job.getTeamLeadId())) {
            throw new RuntimeException(
                "You can only reassign jobs that belong to your own team.");
        }

        // Guard: the new technician must be active in THIS job's team session today,
        // not merely active in any team's session.
        memberRepo.findActiveMemberForTeamLeadToday(job.getTeamLeadId(), newTechnicianId)
            .orElseThrow(() -> new RuntimeException(
                "Technician #" + newTechnicianId +
                " is not in this team's active session today."));

        String newTechnicianName = userRepo.findById(newTechnicianId)
            .map(u -> u.getFullName())
            .orElse("Technician #" + newTechnicianId);

        job.setTechnicianId(newTechnicianId);
        job.setTechnicianName(newTechnicianName);
        job.setStatus(Job.JobStatus.PENDING);
        job.setUpdatedBy(callerId);

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
                ". Valid: ACCEPTED, TRAVELLING, IN_PROGRESS, HOLD, COMPLETED, REJECTED, CANCELLED");
        }

        validateJobTransition(job.getStatus(), newStatus);

        if ((newStatus == Job.JobStatus.HOLD || newStatus == Job.JobStatus.REJECTED) &&
            (request.getReason() == null || request.getReason().isBlank())) {
            throw new RuntimeException("A reason is required when setting status to " + newStatus + ".");
        }
        if (newStatus == Job.JobStatus.COMPLETED &&
            (request.getCompletionPhotoUrls() == null || request.getCompletionPhotoUrls().isBlank())) {
            throw new RuntimeException("At least one after-service photo is required to complete a job.");
        }

        job.setStatus(newStatus);
        job.setUpdatedBy(userId);

        if (newStatus == Job.JobStatus.ACCEPTED) {
            if (job.getAcceptedAt() == null) {
                job.setAcceptedAt(LocalDateTime.now());
            }
            // A prior shift's EOD handover explanation no longer applies once
            // someone actually picks the job back up.
            job.setEodHandoverReason(null);
            job.setEodHandoverAt(null);
        }
        if (newStatus == Job.JobStatus.TRAVELLING && job.getTravelStartedAt() == null) {
            job.setTravelStartedAt(LocalDateTime.now());
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
            applyRejectionCategory(job, request);
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
            // #23 — a rejected job is no longer being actively worked, so pull the
            // linked fault off IN_PROGRESS back into the "open" bucket (ASSIGNED),
            // keeping the Team Lead assignment intact since the same Team Lead still
            // owns it (it lands in their "Needs Attention" queue). Same terminal-fault
            // guard routeOpenJobsAtEod's FORWARD_TO_ADMIN uses — don't resurrect a
            // COMPLETED/CANCELLED fault, and (unlike FORWARD_TO_ADMIN) don't clear the
            // assignedTeamLead* fields.
            if (job.getFaultId() != null) {
                faultRepo.findById(job.getFaultId()).ifPresent(fault -> {
                    if (fault.getStatus() != Fault.FaultStatus.COMPLETED
                            && fault.getStatus() != Fault.FaultStatus.CANCELLED) {
                        fault.setStatus(Fault.FaultStatus.ASSIGNED);
                        faultRepo.save(fault);
                    }
                });
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
            job.setCompletionPhotoUrls(request.getCompletionPhotoUrls());

            // SRS 5.3.1.3 (FR-9) — client unavailable or declined to sign. Does not block
            // completion at this level (confirmed: no signature is required to reach
            // COMPLETED) — routed through this same request instead of the separate
            // /signature endpoint, which is simply never called on this path. Flags the
            // job for the Team Lead to review before submitting payment; no separate
            // audit record, the flag + its own timestamp is sufficient.
            if (request.getSignatureDeclineReason() != null
                    && !request.getSignatureDeclineReason().isBlank()) {
                job.setSignatureDeclineReason(request.getSignatureDeclineReason());
                job.setNeedsTeamLeadReview(true);
                job.setSignatureFlaggedAt(LocalDateTime.now());
            }

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
            job.setRejectionCategory(null);
            job.setObservedIssueType(null);
            job.setLinkedMaterialRequestId(null);
            job.setLinkedMaterialRequestNumber(null);
        }
        if (request.getWorkNotes() != null) {
            job.setWorkNotes(request.getWorkNotes());
        }

        return jobRepo.save(job);
    }

    /**
     * SRS 5.3.1.2 — validates and populates the follow-up fields for whichever
     * rejection category the Technician chose. rejectionCategory itself is
     * optional (any caller that omits it keeps today's plain-reason behavior).
     */
    private void applyRejectionCategory(Job job, UpdateJobRequest request) {
        Job.RejectionCategory category = request.getRejectionCategory();
        job.setRejectionCategory(category);
        job.setObservedIssueType(null);
        job.setLinkedMaterialRequestId(null);
        job.setLinkedMaterialRequestNumber(null);

        if (category == Job.RejectionCategory.ISSUE_MISMATCH) {
            if (request.getObservedIssueType() == null) {
                throw new RuntimeException(
                    "observedIssueType is required when rejectionCategory is ISSUE_MISMATCH.");
            }
            job.setObservedIssueType(request.getObservedIssueType());
        }

        if (category == Job.RejectionCategory.MATERIAL_DELAY) {
            if (request.getLinkedMaterialRequestId() == null) {
                // No outstanding request exists to link (e.g. none was ever
                // submitted for this job) — allowed to proceed on the reason
                // text alone rather than blocking the rejection entirely.
                return;
            }
            MaterialRequest mr = materialRequestRepo.findById(request.getLinkedMaterialRequestId())
                .orElseThrow(() -> new RuntimeException(
                    "Material request not found: " + request.getLinkedMaterialRequestId()));
            // Tied to THIS job specifically — not an ownership check, since a
            // Team Lead may have submitted the request on the Technician's
            // behalf (teamlead/MaterialRequestScreen.tsx does exactly that).
            if (!String.valueOf(job.getId()).equals(mr.getTaskId())) {
                throw new RuntimeException(
                    "Material request " + request.getLinkedMaterialRequestId() +
                    " is not linked to job " + job.getId() + ".");
            }
            if (mr.getStatus() != MaterialRequest.RequestStatus.PENDING &&
                mr.getStatus() != MaterialRequest.RequestStatus.APPROVED) {
                throw new RuntimeException(
                    "Material request " + mr.getRequestNumber() +
                    " is " + mr.getStatus() + ", not still outstanding.");
            }
            job.setLinkedMaterialRequestId(mr.getId());
            job.setLinkedMaterialRequestNumber(mr.getRequestNumber());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5b. MARK ARRIVED — Technician has reached the job site while TRAVELLING
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Job markArrived(Long jobId, Long userId) {
        Job job = findJobOrThrow(jobId);
        if (job.getStatus() != Job.JobStatus.TRAVELLING) {
            throw new RuntimeException("Can only mark arrival while TRAVELLING to the job.");
        }
        if (job.getArrivedAt() == null) {
            job.setArrivedAt(LocalDateTime.now());
        }
        job.setUpdatedBy(userId);
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
            case ACCEPTED    -> requested == Job.JobStatus.TRAVELLING ||
                                requested == Job.JobStatus.REJECTED   ||
                                requested == Job.JobStatus.CANCELLED;
            case TRAVELLING  -> requested == Job.JobStatus.IN_PROGRESS ||
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
