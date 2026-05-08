package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultNoteRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.websocket
        .WebSocketEventPublisher;
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
                        + "technician {}",
                faultId,
                req.getTechnicianId());

        Fault fault = faultRepository
                .findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: "
                                        + faultId));

        User technician = userRepository
                .findById(req.getTechnicianId())
                .orElseThrow(() ->
                        new RuntimeException(
                                "Technician not found: "
                                        + req
                                        .getTechnicianId()));

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

        String previousTech =
                fault.getAssignedTeamLeadName() != null
                        ? fault.getAssignedTeamLeadName()
                        : "Unassigned";

        // Update fault
        fault.setAssignedTeamLeadId(technician.getId());
        fault.setAssignedTeamLeadName(technician.getFullName());
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
                "FAULT_ASSIGNED",
                "🔧",
                "Fault Assigned",
                "Fault assigned to "
                        + technician.getFullName(),
                previousTech,
                technician.getFullName(),
                false);

        // Add note if provided
        if (req.getNotes() != null
                && !req.getNotes().isEmpty()) {
            addNote(fault, admin,
                    req.getNotes(),
                    "ASSIGNMENT", true);
        }

        // WebSocket notifications
        if (req.isNotifyTechnician()) {
            webSocketEventPublisher
                    .publishTechnicianAssigned(
                            fault.getCustomerId() != null
                                    ? fault.getCustomerId().toString()
                                    : "",
                            technician.getFullName(),
                            faultId.toString());

            webSocketEventPublisher.sendToUser(
                    technician.getId().toString(),
                    "New Job Assigned",
                    "Fault #" + faultId
                            + " has been assigned to you",
                    "FAULT_ASSIGNED");
        }

        if (req.isNotifyCustomer()
                && fault.getCustomerId() != null) {
            webSocketEventPublisher.sendToUser(
                    fault.getCustomerId().toString(),
                    "Technician Assigned",
                    technician.getFullName()
                            + " has been assigned "
                            + "to your fault #"
                            + faultId,
                    "TECHNICIAN_ASSIGNED");
        }

        log.info(
                "Fault {} assigned to {} by {}",
                faultId,
                technician.getFullName(),
                admin.getFullName());

        return FaultAssignmentDTO
                .AssignmentResponse.builder()
                .faultId(faultId)
                .faultStatus(
                        fault.getStatus() != null
                                ? fault.getStatus().name()
                                : "ASSIGNED")
                .technicianId(technician.getId())
                .technicianName(
                        technician.getFullName())
                .technicianPhone(
                        technician.getPhone())
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
                        + technician.getFullName())
                .notificationSent(
                        req.isNotifyTechnician())
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
                        + "technician {}",
                faultId,
                req.getNewTechnicianId());

        Fault fault = faultRepository
                .findById(faultId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Fault not found: "
                                        + faultId));

        User newTech = userRepository
                .findById(req.getNewTechnicianId())
                .orElseThrow(() ->
                        new RuntimeException(
                                "Technician not found: "
                                        + req
                                        .getNewTechnicianId()));

        User admin = userRepository
                .findById(adminId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Admin not found: "
                                        + adminId));

        Long prevTechId = fault.getAssignedTeamLeadId();
        String prevName = fault.getAssignedTeamLeadName() != null
                ? fault.getAssignedTeamLeadName()
                : "Unassigned";

        // Notify previous technician
        if (req.isNotifyPreviousTechnician()
                && prevTechId != null) {
            webSocketEventPublisher.sendToUser(
                    prevTechId.toString(),
                    "Job Reassigned",
                    "Fault #" + faultId
                            + " has been reassigned. "
                            + "Reason: " + req.getReason(),
                    "FAULT_REASSIGNED");
        }

        // Update fault
        fault.setAssignedTeamLeadId(newTech.getId());
        fault.setAssignedTeamLeadName(newTech.getFullName());
        faultRepository.save(fault);

        // Save history event
        saveHistoryEvent(
                fault, admin,
                "FAULT_REASSIGNED",
                "🔄",
                "Fault Reassigned",
                "Reassigned from "
                        + prevName
                        + " to "
                        + newTech.getFullName()
                        + ". Reason: "
                        + req.getReason(),
                prevName,
                newTech.getFullName(),
                false);

        // Add note
        String noteContent =
                "Reassigned to "
                        + newTech.getFullName()
                        + ". Reason: "
                        + req.getReason()
                        + (req.getNotes() != null
                        && !req.getNotes().isEmpty()
                        ? " | " + req.getNotes()
                        : "");
        addNote(fault, admin,
                noteContent, "REASSIGNMENT", true);

        // Notify new technician
        if (req.isNotifyTechnician()) {
            webSocketEventPublisher.sendToUser(
                    newTech.getId().toString(),
                    "Fault Assigned to You",
                    "Fault #" + faultId
                            + " has been reassigned to you",
                    "FAULT_ASSIGNED");
        }

        return FaultAssignmentDTO
                .AssignmentResponse.builder()
                .faultId(faultId)
                .faultStatus(
                        fault.getStatus() != null
                                ? fault.getStatus().name()
                                : "ASSIGNED")
                .technicianId(newTech.getId())
                .technicianName(newTech.getFullName())
                .technicianPhone(newTech.getPhone())
                .assignedBy(admin.getFullName())
                .assignedAt(LocalDateTime.now())
                .message("Fault reassigned from "
                        + prevName
                        + " to "
                        + newTech.getFullName())
                .notificationSent(
                        req.isNotifyTechnician())
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
                        + "technician {}",
                req.getFaultIds().size(),
                req.getTechnicianId());

        User technician = userRepository
                .findById(req.getTechnicianId())
                .orElseThrow(() ->
                        new RuntimeException(
                                "Technician not found: "
                                        + req
                                        .getTechnicianId()));

        User admin = userRepository
                .findById(adminId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Admin not found: "
                                        + adminId));

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

                fault.setAssignedTeamLeadId(technician.getId());
                fault.setAssignedTeamLeadName(technician.getFullName());

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
                        "FAULT_ASSIGNED",
                        "🔧",
                        "Bulk Assigned",
                        "Bulk assigned to "
                                + technician
                                .getFullName(),
                        "Unassigned",
                        technician.getFullName(),
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

        // Notify technician
        if (req.isNotifyTechnician()
                && !successIds.isEmpty()) {
            webSocketEventPublisher.sendToUser(
                    technician.getId().toString(),
                    successIds.size()
                            + " New Jobs Assigned",
                    successIds.size()
                            + " faults have been "
                            + "assigned to you",
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