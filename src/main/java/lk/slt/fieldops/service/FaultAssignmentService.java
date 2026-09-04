package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultNoteRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import lk.slt.fieldops.websocket
        .WebSocketEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation
        .Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FaultAssignmentService {

    private final FaultRepository
            faultRepository;
    private final UserRepository
            userRepository;
    private final FaultHistoryRepository
            faultHistoryRepository;
    private final FaultNoteRepository
            faultNoteRepository;
    private final WebSocketEventPublisher
            webSocketEventPublisher;
    private final WorkGroupRepository
            workGroupRepository;
    private final OpmcAccessGuard
            opmcGuard;
    private final NotificationService
            notificationService;
    private final JobRepository
            jobRepository;

    private static final DateTimeFormatter
            FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm");

    // ─── Assign Fault ─────────────────────────────────────

    @Transactional
    public FaultAssignmentDTO.AssignmentResponse
    assignFault(
            Long faultId,
            FaultAssignmentDTO.AssignRequest req,
            Long adminId) {

        log.info(
                "Assigning fault {} to "
                        + "work group {}",
                faultId,
                req.getWorkGroupId());

        Fault fault = faultRepository
                .findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: "
                                        + faultId));

        // Caller-OPMC scoping (Critical, reclassified from Major — QA_Compliance_Consolidated_Report.md):
        // the calling Admin's own OPMC must match the fault's OPMC; SUPER_ADMIN is unscoped. Distinct
        // from the WorkGroup-vs-fault structural check below, which only verifies the fault and its
        // target Work Group are internally consistent and says nothing about who may call this at all.
        opmcGuard.assertSameOpmc(fault.getOpmcId(), adminId);

        WorkGroup workGroup = workGroupRepository
                .findById(req.getWorkGroupId())
                .orElseThrow(() ->
                        new RuntimeException(
                                "Work Group not found: "
                                        + req.getWorkGroupId()));

        if (!Boolean.TRUE.equals(workGroup.getIsActive())) {
            throw new RuntimeException(
                    "Work Group " + workGroup.getId() + " is not active.");
        }
        // A fault can only ever go to a Work Group in its own OPMC — this holds
        // regardless of caller role (Admin or Super Admin), since assigning
        // cross-OPMC never makes sense even when the caller is unscoped.
        if (workGroup.getOpmc() == null
                || !workGroup.getOpmc().getId().equals(fault.getOpmcId())) {
            throw new RuntimeException(
                    "Work Group " + workGroup.getId()
                            + " does not belong to this fault's OPMC.");
        }

        User admin = userRepository
                .findById(adminId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Admin not found: "
                                        + adminId));

        // Check if fault is already completed
        if (fault.getStatus() != null
                && ("COMPLETED".equals(
                fault.getStatus().name())
                || "CANCELLED".equals(
                fault.getStatus().name()))) {
            throw new RuntimeException(
                    "Cannot assign a "
                            + fault.getStatus().name()
                            .toLowerCase()
                            + " fault");
        }

        String previousWorkGroup =
                fault.getWorkGroupName() != null
                        ? fault.getWorkGroupName()
                        : "Unassigned";

        // Update fault — SRS 5.5.1: assigned to the Work Group, not a person.
        // The Team Lead of that Work Group decides further routing (self-assign
        // or dispatch to a Technician), so assignedTeamLeadId stays null here.
        fault.setWorkGroupId(workGroup.getId());
        fault.setWorkGroupName(workGroup.getName());
        fault.setAssignedTeamLeadId(null);
        fault.setAssignedTeamLeadName(null);
        fault.setAssignedAt(LocalDateTime.now());
        if (req.getPriority() != null) {
            try {
                fault.setPriority(
                        Fault.FaultPriority.valueOf(
                                req.getPriority()
                                        .toUpperCase()));
            } catch (Exception e) {
                log.warn(
                        "Invalid priority: {}",
                        req.getPriority());
            }
        }

        // Update status to ASSIGNED
        try {
            fault.setStatus(
                    Fault.FaultStatus.valueOf("ASSIGNED"));
        } catch (Exception e) {
            log.warn(
                    "ASSIGNED status not "
                            + "found in enum");
        }

        faultRepository.save(fault);

        // Save history event
        saveHistoryEvent(
                fault, admin,
                "FAULT_ASSIGNED_TO_WORK_GROUP",
                "🔧",
                "Fault Assigned to Work Group",
                "Fault assigned to Work Group: "
                        + workGroup.getName(),
                previousWorkGroup,
                workGroup.getName(),
                false);

        // Add note if provided
        if (req.getNotes() != null
                && !req.getNotes().isEmpty()) {
            addNote(fault, admin,
                    req.getNotes(),
                    "ASSIGNMENT", true);
        }

        // WebSocket notifications
        User teamLead = workGroup.getTeamLead();
        if (req.isNotifyTeamLead() && teamLead != null) {
            webSocketEventPublisher.sendToUser(
                    teamLead.getId().toString(),
                    "New Fault in Your Work Group",
                    "Fault #" + faultId
                            + " has been assigned to "
                            + workGroup.getName(),
                    "FAULT_ASSIGNED");
        }

        if (req.isNotifyCustomer()
                && fault.getCustomerId() != null) {
            webSocketEventPublisher.sendToUser(
                    fault.getCustomerId().toString(),
                    "Fault Assigned",
                    "Your fault #" + faultId
                            + " has been assigned to a field team",
                    "TECHNICIAN_ASSIGNED");

            // QA_Compliance_Consolidated_Report.md — Stage G FCM Major: "a customer being
            // told a technician was assigned to their fault" was WebSocket-only. Distinct
            // from NOTIF-001's Team-Lead-side gap on this same event (immediately above),
            // which stays separately open — out of scope here.
            userRepository.findById(fault.getCustomerId()).ifPresent(customer ->
                    notificationService.notifyFaultAssignedToCustomer(
                            customer.getId(), customer.getFcmToken(),
                            fault.getFaultNumber(), fault.getId()));
        }

        log.info(
                "Fault {} assigned to work group {} by {}",
                faultId,
                workGroup.getName(),
                admin.getFullName());

        return FaultAssignmentDTO
                .AssignmentResponse.builder()
                .faultId(faultId)
                .faultStatus(
                        fault.getStatus() != null
                                ? fault.getStatus().name()
                                : "ASSIGNED")
                .workGroupId(workGroup.getId())
                .workGroupName(workGroup.getName())
                .teamLeadId(teamLead != null ? teamLead.getId() : null)
                .teamLeadName(teamLead != null ? teamLead.getFullName() : null)
                .priority(
                        fault.getPriority() != null
                                ? fault.getPriority()
                                .name()
                                : null)
                .scheduledDate(req.getScheduledDate())
                .estimatedDurationHours(
                        req.getEstimatedDurationHours())
                .assignedBy(admin.getFullName())
                .assignedAt(LocalDateTime.now())
                .message("Fault successfully "
                        + "assigned to "
                        + workGroup.getName())
                .notificationSent(
                        req.isNotifyTeamLead())
                .build();
    }

    // ─── Reassign Fault ───────────────────────────────────

    @Transactional
    public FaultAssignmentDTO.AssignmentResponse
    reassignFault(
            Long faultId,
            FaultAssignmentDTO.ReassignRequest req,
            Long adminId) {

        log.info(
                "Reassigning fault {} to "
                        + "work group {}",
                faultId,
                req.getNewWorkGroupId());

        Fault fault = faultRepository
                .findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: "
                                        + faultId));

        // Caller-OPMC scoping (Critical, reclassified from Major) — same reasoning as assignFault.
        opmcGuard.assertSameOpmc(fault.getOpmcId(), adminId);

        WorkGroup newWorkGroup = workGroupRepository
                .findById(req.getNewWorkGroupId())
                .orElseThrow(() ->
                        new RuntimeException(
                                "Work Group not found: "
                                        + req.getNewWorkGroupId()));

        if (!Boolean.TRUE.equals(newWorkGroup.getIsActive())) {
            throw new RuntimeException(
                    "Work Group " + newWorkGroup.getId() + " is not active.");
        }
        if (newWorkGroup.getOpmc() == null
                || !newWorkGroup.getOpmc().getId().equals(fault.getOpmcId())) {
            throw new RuntimeException(
                    "Work Group " + newWorkGroup.getId()
                            + " does not belong to this fault's OPMC.");
        }

        User admin = userRepository
                .findById(adminId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Admin not found: "
                                        + adminId));

        // Check if fault is already completed — same guard as assignFault
        // (its sibling method), previously missing here entirely.
        if (fault.getStatus() != null
                && ("COMPLETED".equals(
                fault.getStatus().name())
                || "CANCELLED".equals(
                fault.getStatus().name()))) {
            throw new RuntimeException(
                    "Cannot reassign a "
                            + fault.getStatus().name()
                            .toLowerCase()
                            + " fault");
        }

        Long prevTeamLeadId = fault.getAssignedTeamLeadId();
        String prevWorkGroupName = fault.getWorkGroupName() != null
                ? fault.getWorkGroupName()
                : "Unassigned";

        // Notify previous Team Lead, if the fault had already been picked up
        if (req.isNotifyPreviousTeamLead()
                && prevTeamLeadId != null) {
            webSocketEventPublisher.sendToUser(
                    prevTeamLeadId.toString(),
                    "Fault Reassigned",
                    "Fault #" + faultId
                            + " has been reassigned to another Work Group. "
                            + "Reason: " + req.getReason(),
                    "FAULT_REASSIGNED");
        }

        // Update fault — reassigning always resets to "in the new Work Group's
        // queue", clearing any prior self-assignment/dispatch under the old one.
        fault.setWorkGroupId(newWorkGroup.getId());
        fault.setWorkGroupName(newWorkGroup.getName());
        fault.setAssignedTeamLeadId(null);
        fault.setAssignedTeamLeadName(null);
        faultRepository.save(fault);

        // Save history event
        saveHistoryEvent(
                fault, admin,
                "FAULT_REASSIGNED",
                "🔄",
                "Fault Reassigned",
                "Reassigned from "
                        + prevWorkGroupName
                        + " to "
                        + newWorkGroup.getName()
                        + ". Reason: "
                        + req.getReason(),
                prevWorkGroupName,
                newWorkGroup.getName(),
                false);

        // Add note
        String noteContent =
                "Reassigned to "
                        + newWorkGroup.getName()
                        + ". Reason: "
                        + req.getReason()
                        + (req.getNotes() != null
                        && !req.getNotes().isEmpty()
                        ? " | " + req.getNotes()
                        : "");
        addNote(fault, admin,
                noteContent, "REASSIGNMENT", true);

        // Notify new Work Group's Team Lead
        User newTeamLead = newWorkGroup.getTeamLead();
        if (req.isNotifyTeamLead() && newTeamLead != null) {
            webSocketEventPublisher.sendToUser(
                    newTeamLead.getId().toString(),
                    "Fault Assigned to Your Work Group",
                    "Fault #" + faultId
                            + " has been reassigned to " + newWorkGroup.getName(),
                    "FAULT_ASSIGNED");
        }

        return FaultAssignmentDTO
                .AssignmentResponse.builder()
                .faultId(faultId)
                .faultStatus(
                        fault.getStatus() != null
                                ? fault.getStatus().name()
                                : "ASSIGNED")
                .workGroupId(newWorkGroup.getId())
                .workGroupName(newWorkGroup.getName())
                .teamLeadId(newTeamLead != null ? newTeamLead.getId() : null)
                .teamLeadName(newTeamLead != null ? newTeamLead.getFullName() : null)
                .assignedBy(admin.getFullName())
                .assignedAt(LocalDateTime.now())
                .message("Fault reassigned from "
                        + prevWorkGroupName
                        + " to "
                        + newWorkGroup.getName())
                .notificationSent(
                        req.isNotifyTeamLead())
                .build();
    }

    // ─── Escalate Fault ───────────────────────────────────

    @Transactional
    public FaultAssignmentDTO.AssignmentResponse
    escalateFault(
            Long faultId,
            FaultAssignmentDTO.EscalateRequest req,
            Long adminId) {

        log.info(
                "Escalating fault {}", faultId);

        Fault fault = faultRepository
                .findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: "
                                        + faultId));

        User admin = userRepository
                .findById(adminId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Admin not found: "
                                        + adminId));

        String previousPriority =
                fault.getPriority() != null
                        ? fault.getPriority().name()
                        : "MEDIUM";

        // Escalate priority to HIGH
        fault.setPriority(Fault.FaultPriority.HIGH);
        fault.setIsEscalated(true);
        fault.setEscalationReason(req.getReason());
        faultRepository.save(fault);

        // Save history event
        saveHistoryEvent(
                fault, admin,
                "FAULT_ESCALATED",
                "⚠️",
                "Fault Escalated",
                "Priority escalated to HIGH. "
                        + "Reason: " + req.getReason()
                        + (req.getEscalateTo() != null
                        ? " | Escalated to: "
                        + req.getEscalateTo()
                        : ""),
                previousPriority,
                "HIGH",
                false);

        // Add escalation note
        addNote(fault, admin,
                "ESCALATED: " + req.getReason()
                        + (req.getNotes() != null
                        ? " | " + req.getNotes()
                        : ""),
                "ESCALATION", true);

        // Notify admins
        if (req.isNotifyAdmin()) {
            webSocketEventPublisher.sendToRole(
                    "admin",
                    "Fault Escalated",
                    "Fault #" + faultId
                            + " has been escalated. "
                            + "Reason: " + req.getReason(),
                    "FAULT_ESCALATED");
        }

        // Notify customer
        if (fault.getCustomerId() != null) {
            webSocketEventPublisher.sendToUser(
                    fault.getCustomerId().toString(),
                    "Your Issue Has Been Escalated",
                    "Your issue #" + faultId
                            + " has been given "
                            + "high priority",
                    "FAULT_UPDATE");
        }

        return FaultAssignmentDTO
                .AssignmentResponse.builder()
                .faultId(faultId)
                .faultStatus(
                        fault.getStatus() != null
                                ? fault.getStatus().name()
                                : "OPEN")
                .priority("HIGH")
                .assignedBy(admin.getFullName())
                .assignedAt(LocalDateTime.now())
                .message("Fault #" + faultId
                        + " escalated to HIGH priority")
                .notificationSent(req.isNotifyAdmin())
                .build();
    }

    // ─── Bulk Assign ──────────────────────────────────────

    @Transactional
    public FaultAssignmentDTO.BulkAssignResponse
    bulkAssign(
            FaultAssignmentDTO.BulkAssignRequest req,
            Long adminId) {

        log.info(
                "Bulk assigning {} faults to "
                        + "work group {}",
                req.getFaultIds().size(),
                req.getWorkGroupId());

        WorkGroup workGroup = workGroupRepository
                .findById(req.getWorkGroupId())
                .orElseThrow(() ->
                        new RuntimeException(
                                "Work Group not found: "
                                        + req.getWorkGroupId()));

        if (!Boolean.TRUE.equals(workGroup.getIsActive())) {
            throw new RuntimeException(
                    "Work Group " + workGroup.getId() + " is not active.");
        }

        User admin = userRepository
                .findById(adminId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Admin not found: "
                                        + adminId));

        // Validate once up front rather than silently swallowing per-fault —
        // the same priority string applies to every fault in this batch.
        if (req.getPriority() != null && !req.getPriority().isBlank()) {
            try {
                Fault.FaultPriority.valueOf(req.getPriority().toUpperCase());
            } catch (Exception e) {
                throw new RuntimeException("Invalid priority: " + req.getPriority()
                        + ". Valid: HIGH, MEDIUM, LOW");
            }
        }

        List<Long> successIds = new ArrayList<>();
        List<Long> failedIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Long faultId : req.getFaultIds()) {
            try {
                Fault fault = faultRepository
                        .findById(faultId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Fault not found: "
                                                + faultId));

                // Caller-OPMC scoping (Critical, reclassified from Major) — checked per-fault
                // since a bulk request's faults are not guaranteed to share one OPMC; a rejection
                // here fails just this fault, matching how the WorkGroup-consistency check below
                // already fails individual faults without aborting the whole batch.
                try {
                    opmcGuard.assertSameOpmc(fault.getOpmcId(), adminId);
                } catch (AccessDeniedException ade) {
                    failedIds.add(faultId);
                    errors.add("Fault #" + faultId
                            + " does not belong to your OPMC");
                    continue;
                }

                if (fault.getStatus() != null
                        && ("COMPLETED".equals(
                        fault.getStatus().name())
                        || "CANCELLED".equals(
                        fault.getStatus().name()))) {
                    failedIds.add(faultId);
                    errors.add("Fault #" + faultId
                            + " is "
                            + fault.getStatus().name());
                    continue;
                }

                if (workGroup.getOpmc() == null
                        || !workGroup.getOpmc().getId().equals(fault.getOpmcId())) {
                    failedIds.add(faultId);
                    errors.add("Fault #" + faultId
                            + " does not belong to Work Group " + workGroup.getId()
                            + "'s OPMC");
                    continue;
                }

                fault.setWorkGroupId(workGroup.getId());
                fault.setWorkGroupName(workGroup.getName());
                fault.setAssignedTeamLeadId(null);
                fault.setAssignedTeamLeadName(null);

                if (req.getPriority() != null) {
                    try {
                        fault.setPriority(
                                Fault.FaultPriority.valueOf(
                                        req.getPriority()
                                                .toUpperCase()));
                    } catch (Exception e) {
                        log.warn(
                                "Invalid priority: {}",
                                req.getPriority());
                    }
                }

                try {
                    fault.setStatus(
                            Fault.FaultStatus.valueOf(
                                    "ASSIGNED"));
                } catch (Exception e) {
                    log.warn(
                            "ASSIGNED status "
                                    + "not available");
                }

                faultRepository.save(fault);

                saveHistoryEvent(
                        fault, admin,
                        "FAULT_ASSIGNED_TO_WORK_GROUP",
                        "🔧",
                        "Bulk Assigned to Work Group",
                        "Bulk assigned to Work Group "
                                + workGroup.getName(),
                        "Unassigned",
                        workGroup.getName(),
                        false);

                successIds.add(faultId);

            } catch (Exception e) {
                failedIds.add(faultId);
                errors.add("Fault #" + faultId
                        + ": " + e.getMessage());
                log.error(
                        "Error assigning fault {}: {}",
                        faultId, e.getMessage());
            }
        }

        // Notify the Work Group's Team Lead
        User bulkTeamLead = workGroup.getTeamLead();
        if (req.isNotifyTeamLead()
                && bulkTeamLead != null
                && !successIds.isEmpty()) {
            webSocketEventPublisher.sendToUser(
                    bulkTeamLead.getId().toString(),
                    successIds.size()
                            + " New Faults Assigned",
                    successIds.size()
                            + " faults have been "
                            + "assigned to " + workGroup.getName(),
                    "FAULT_ASSIGNED");
        }

        log.info(
                "Bulk assign complete: {} success, "
                        + "{} failed",
                successIds.size(),
                failedIds.size());

        return FaultAssignmentDTO
                .BulkAssignResponse.builder()
                .totalRequested(
                        req.getFaultIds().size())
                .successCount(successIds.size())
                .failureCount(failedIds.size())
                .successFaultIds(successIds)
                .failedFaultIds(failedIds)
                .errors(errors)
                .processedAt(LocalDateTime.now())
                .build();
    }

    // ─── Self-Assign (SRS 5.5.1 — Team Lead "Assign to Me") ──────────────

    @Transactional
    public FaultAssignmentDTO.AssignmentResponse
    selfAssignFault(
            Long faultId,
            FaultAssignmentDTO.SelfAssignRequest req,
            Long teamLeadId) {

        Fault fault = faultRepository
                .findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: " + faultId));

        User teamLead = userRepository
                .findById(teamLeadId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Team lead not found: " + teamLeadId));

        if (fault.getWorkGroupId() == null
                || teamLead.getWorkgroup() == null
                || !fault.getWorkGroupId().equals(teamLead.getWorkgroup().getId())) {
            throw new AccessDeniedException(
                    "Fault #" + faultId + " is not in your Work Group's queue.");
        }

        if (fault.getAssignedTeamLeadId() != null) {
            throw new RuntimeException(
                    "Fault #" + faultId + " has already been claimed by "
                            + fault.getAssignedTeamLeadName() + ".");
        }

        fault.setAssignedTeamLeadId(teamLead.getId());
        fault.setAssignedTeamLeadName(teamLead.getFullName());
        faultRepository.save(fault);

        saveHistoryEvent(
                fault, teamLead,
                "TEAM_LEAD_SELF_ASSIGNED",
                "🙋",
                "Team Lead Self-Assigned",
                teamLead.getFullName() + " took this fault directly from "
                        + fault.getWorkGroupName() + "'s queue.",
                "Unassigned",
                teamLead.getFullName(),
                false);

        if (req != null && req.getNotes() != null && !req.getNotes().isEmpty()) {
            addNote(fault, teamLead, req.getNotes(), "ASSIGNMENT", true);
        }

        return FaultAssignmentDTO
                .AssignmentResponse.builder()
                .faultId(faultId)
                .faultStatus(fault.getStatus() != null ? fault.getStatus().name() : "ASSIGNED")
                .workGroupId(fault.getWorkGroupId())
                .workGroupName(fault.getWorkGroupName())
                .teamLeadId(teamLead.getId())
                .teamLeadName(teamLead.getFullName())
                .assignedBy(teamLead.getFullName())
                .assignedAt(LocalDateTime.now())
                .message(teamLead.getFullName() + " self-assigned fault #" + faultId)
                .notificationSent(false)
                .build();
    }

    // ─── Transfer to Admin (SRS 5.5.1) ────────────────────────────────────
    // Mirrors EOD "Forward to Admin" (5.4.2.1) and Issue Mismatch (5.3.1.2):
    // fault returns to the unassigned pool (REPORTED) for the Admin to
    // manually pick a different, capable Work Group. Full audit trail.

    @Transactional
    public FaultAssignmentDTO.AssignmentResponse
    transferToAdmin(
            Long faultId,
            FaultAssignmentDTO.TransferToAdminRequest req,
            Long teamLeadId) {

        Fault fault = faultRepository
                .findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: " + faultId));

        User teamLead = userRepository
                .findById(teamLeadId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Team lead not found: " + teamLeadId));

        if (fault.getWorkGroupId() == null
                || teamLead.getWorkgroup() == null
                || !fault.getWorkGroupId().equals(teamLead.getWorkgroup().getId())) {
            throw new AccessDeniedException(
                    "Fault #" + faultId + " is not in your Work Group.");
        }

        if (fault.getStatus() == Fault.FaultStatus.COMPLETED
                || fault.getStatus() == Fault.FaultStatus.CANCELLED) {
            throw new RuntimeException(
                    "Cannot transfer a " + fault.getStatus().name().toLowerCase() + " fault.");
        }

        String previousWorkGroup = fault.getWorkGroupName();

        fault.setStatus(Fault.FaultStatus.REPORTED);
        fault.setWorkGroupId(null);
        fault.setWorkGroupName(null);
        fault.setAssignedTeamLeadId(null);
        fault.setAssignedTeamLeadName(null);
        faultRepository.save(fault);

        // QA_Compliance_Consolidated_Report.md — the fault is now fully detached from this
        // Work Group/Team Lead, but its current Job row (if any) was previously left dangling,
        // still pointing at this Team Lead — surfaced incorrectly in their own
        // getTodaysJobsForTeamLead ("Team Jobs"/"Needs Attention" queue on the Team Lead app)
        // and reassignable via the generic reassignJob action to a technician for a fault that
        // no longer belongs to their Work Group at all. Job.teamLeadId is NOT NULL
        // (entity/Job.java:44), so it can never be nulled out to reflect the detachment — the
        // same treatment JobService.routeOpenJobsAtEod's FORWARD_TO_ADMIN case already
        // established for the identical "fault forcibly pulled away from this Team Lead" shape
        // (JobService.java:222-251) is applied here: cancel the stale Job rather than leave it
        // dangling or attempt an impossible field-clear. findFirstByFaultIdOrderByCreatedAtDesc
        // is the same "current job representing this fault" lookup IssueController already uses
        // for the identical question.
        jobRepository.findFirstByFaultIdOrderByCreatedAtDesc(faultId).ifPresent(job -> {
            if (job.getStatus() != Job.JobStatus.COMPLETED
                    && job.getStatus() != Job.JobStatus.CANCELLED) {
                job.setStatus(Job.JobStatus.CANCELLED);
                job.setRejectionReason(
                        "Fault #" + faultId + " transferred back to Admin by "
                                + teamLead.getFullName() + " from " + previousWorkGroup
                                + ". Reason: " + req.getReason());
                job.setRejectedByRole("TEAM_LEAD");
                jobRepository.save(job);
            }
        });

        saveHistoryEvent(
                fault, teamLead,
                "TRANSFERRED_TO_ADMIN",
                "↩️",
                "Transferred back to Admin",
                teamLead.getFullName() + " transferred this fault from "
                        + previousWorkGroup + " back to Admin. Reason: " + req.getReason()
                        + (req.getNotes() != null && !req.getNotes().isBlank()
                                ? " | " + req.getNotes() : ""),
                previousWorkGroup,
                "Admin queue (unassigned)",
                false);

        webSocketEventPublisher.sendToRole(
                "admin",
                "Fault Transferred Back",
                "Fault #" + faultId + " was transferred back by "
                        + teamLead.getFullName() + " from " + previousWorkGroup
                        + ". Reason: " + req.getReason(),
                "FAULT_TRANSFERRED_TO_ADMIN");

        return FaultAssignmentDTO
                .AssignmentResponse.builder()
                .faultId(faultId)
                .faultStatus(fault.getStatus().name())
                .assignedBy(teamLead.getFullName())
                .assignedAt(LocalDateTime.now())
                .message("Fault #" + faultId + " transferred back to Admin from "
                        + previousWorkGroup)
                .notificationSent(true)
                .build();
    }

    // ─── Get Timeline ─────────────────────────────────────

    public List<FaultAssignmentDTO
            .TimelineEventDTO>
    getFaultTimeline(Long faultId) {
        log.debug(
                "Getting timeline for fault {}",
                faultId);

        Fault fault = faultRepository
                .findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: "
                                        + faultId));

        List<FaultHistory> history =
                faultHistoryRepository
                        .findByFaultId(faultId);

        List<FaultAssignmentDTO.TimelineEventDTO>
                timeline = new ArrayList<>();

        // Add fault creation event first
        timeline.add(
                FaultAssignmentDTO
                        .TimelineEventDTO.builder()
                        .id(0L)
                        .eventType("FAULT_CREATED")
                        .eventIcon("📋")
                        .eventColor("#003087")
                        .title("Fault Reported")
                        .description(
                                "Fault #" + faultId
                                        + " was reported"
                                        + (fault.getCategory()
                                        != null
                                        ? " — "
                                        + fault.getCategory()
                                        : ""))
                        .actorName(
                                fault.getCustomerName() != null
                                        ? fault.getCustomerName()
                                        : "Customer")
                        .actorRole("CLIENT")
                        .timestamp(fault.getCreatedAt())
                        .timeAgo(getTimeAgo(
                                fault.getCreatedAt()))
                        .isSystem(false)
                        .build());

        // Add history events
        for (FaultHistory event : history) {
            timeline.add(
                    FaultAssignmentDTO
                            .TimelineEventDTO.builder()
                            .id(event.getId())
                            .eventType(
                                    event.getEventType())
                            .eventIcon(getEventIcon(
                                    event.getEventType()))
                            .eventColor(getEventColor(
                                    event.getEventType()))
                            .title(event.getTitle())
                            .description(
                                    event.getDescription())
                            .actorName(
                                    event.getActor() != null
                                            ? event.getActor()
                                            .getFullName()
                                            : "System")
                            .actorRole(
                                    event.getActor() != null
                                            && event.getActor()
                                            .getRole() != null
                                            ? event.getActor()
                                            .getRole().name()
                                            : "SYSTEM")
                            .previousValue(
                                    event.getPreviousValue())
                            .newValue(event.getNewValue())
                            .timestamp(
                                    event.getCreatedAt())
                            .timeAgo(getTimeAgo(
                                    event.getCreatedAt()))
                            .isSystem(
                                    event.getIsSystem()
                                            != null
                                            && event.getIsSystem())
                            .build());
        }

        // Sort chronologically ascending
        timeline.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return a.getTimestamp()
                    .compareTo(b.getTimestamp());
        });

        return timeline;
    }

    // ─── Add Note ─────────────────────────────────────────

    @Transactional
    public FaultAssignmentDTO.FaultNoteResponse
    addFaultNote(
            Long faultId,
            FaultAssignmentDTO.AddNoteRequest req,
            Long userId) {

        log.info(
                "Adding note to fault {}",
                faultId);

        Fault fault = faultRepository
                .findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: "
                                        + faultId));

        User author = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "User not found: "
                                        + userId));

        FaultNote note = addNote(
                fault,
                author,
                req.getContent(),
                req.getNoteType() != null
                        ? req.getNoteType()
                        : "GENERAL",
                req.isInternal());

        // Save history event
        saveHistoryEvent(
                fault, author,
                "NOTE_ADDED",
                "💬",
                "Note Added",
                req.isInternal()
                        ? "Internal note added"
                        : "Note added by "
                        + author.getFullName(),
                null, null, false);

        return mapNoteToResponse(note);
    }

    // ─── Get Notes ────────────────────────────────────────

    public List<FaultAssignmentDTO
            .FaultNoteResponse>
    getFaultNotes(
            Long faultId,
            boolean includeInternal) {
        log.debug(
                "Getting notes for fault {}",
                faultId);

        // Verify fault exists
        faultRepository.findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: "
                                        + faultId));

        List<FaultNote> notes;
        if (includeInternal) {
            notes = faultNoteRepository
                    .findByFaultId(faultId);
        } else {
            notes = faultNoteRepository
                    .findPublicByFaultId(faultId);
        }

        return notes.stream()
                .map(this::mapNoteToResponse)
                .collect(Collectors.toList());
    }

    // ─── Private Helpers ──────────────────────────────────

    private FaultNote addNote(
            Fault fault,
            User author,
            String content,
            String noteType,
            boolean isInternal) {

        FaultNote note = FaultNote.builder()
                .faultId(fault.getId())
                .addedBy(author.getId())
                .addedByName(author.getFullName())
                .note(content)
                .noteType(noteType)
                .isInternal(isInternal)
                .build();

        return faultNoteRepository.save(note);
    }

    private void saveHistoryEvent(
            Fault fault,
            User actor,
            String eventType,
            String icon,
            String title,
            String description,
            String previousValue,
            String newValue,
            boolean isSystem) {

        FaultHistory history =
                FaultHistory.builder()
                        .fault(fault)
                        .faultNumber(fault.getFaultNumber() != null
                                ? fault.getFaultNumber()
                                : "FAULT-" + fault.getId())
                        .actor(actor)
                        .eventType(eventType)
                        .title(title)
                        .description(description)
                        .previousValue(previousValue)
                        .newValue(newValue)
                        .isSystem(isSystem)
                        .ipAddress(lk.slt.fieldops.shared.RequestContext.getClientIp())
                        .build();

        faultHistoryRepository.save(history);
    }

    private FaultAssignmentDTO.FaultNoteResponse
    mapNoteToResponse(FaultNote note) {
        List<String> attachments =
                note.getAttachments() != null
                        && !note.getAttachments().isEmpty()
                        ? Arrays.asList(note.getAttachments().split(","))
                        : Collections.emptyList();

        return FaultAssignmentDTO
                .FaultNoteResponse.builder()
                .id(note.getId())
                .faultId(note.getFaultId())
                .content(note.getNote())
                .noteType(note.getNoteType())
                .isInternal(note.getIsInternal() != null && note.getIsInternal())
                .authorId(note.getAddedBy())
                .authorName(note.getAddedByName())
                .authorRole(null)
                .attachments(attachments)
                .createdAt(note.getCreatedAt())
                .timeAgo(getTimeAgo(note.getCreatedAt()))
                .build();
    }

    private String getEventIcon(
            String eventType) {
        if (eventType == null) return "📋";
        switch (eventType) {
            case "FAULT_CREATED":
                return "📋";
            case "FAULT_ASSIGNED":
            case "FAULT_REASSIGNED":
                return "🔧";
            case "FAULT_ESCALATED":
                return "⚠️";
            case "STATUS_CHANGED":
                return "🔄";
            case "NOTE_ADDED":
                return "💬";
            case "PAYMENT_SUBMITTED":
                return "💰";
            case "FAULT_COMPLETED":
                return "✅";
            case "FAULT_CANCELLED":
                return "❌";
            default:
                return "📌";
        }
    }

    private String getEventColor(
            String eventType) {
        if (eventType == null) return "#9E9E9E";
        switch (eventType) {
            case "FAULT_CREATED":
                return "#003087";
            case "FAULT_ASSIGNED":
            case "FAULT_REASSIGNED":
                return "#0099CC";
            case "FAULT_ESCALATED":
                return "#FF5722";
            case "STATUS_CHANGED":
                return "#FF9800";
            case "NOTE_ADDED":
                return "#9C27B0";
            case "FAULT_COMPLETED":
                return "#4CAF50";
            case "FAULT_CANCELLED":
                return "#F44336";
            default:
                return "#9E9E9E";
        }
    }

    private String getTimeAgo(
            LocalDateTime dateTime) {
        if (dateTime == null) return "Unknown";
        long seconds = ChronoUnit.SECONDS.between(
                dateTime, LocalDateTime.now());
        if (seconds < 60)
            return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60)
            return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24)
            return hours + "h ago";
        long days = hours / 24;
        if (days < 7)
            return days + "d ago";
        return dateTime.toLocalDate().toString();
    }
}