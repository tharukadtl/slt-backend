package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.*;
import lk.slt.fieldops.entity.CauseOfFault;
import lk.slt.fieldops.entity.Circuit;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.FaultNote;
import lk.slt.fieldops.entity.Notification;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CauseOfFaultRepository;
import lk.slt.fieldops.repository.CircuitRepository;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultNoteRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FaultService — the largest service in the system.
 * Handles ALL fault operations and status transitions.
 *
 * KEY RULE: Every status change MUST:
 *   1. Update the fault status
 *   2. Write a row to fault_history
 *
 * Methods:
 *   reportFault()          → Client reports (REPORTED)
 *   assignToTeamLead()     → Admin assigns (ASSIGNED + due date)
 *   updateStatus()         → Tech updates (IN_PROGRESS / HOLD / COMPLETED)
 *   cancelFault()          → Admin/Client cancels (CANCELLED)
 *   getFaultById()         → Get one fault
 *   getFaultsByOpmc()      → Admin: all faults for an OPMC
 *   getMyFaults()          → Client: their own faults
 *   getFaultHistory()      → Full timeline for a fault
 *   addNote()              → Admin adds internal note
 *   getNotes()             → Get all notes for a fault
 *   getOpenFaults()        → Super Admin: all open faults
 */
@Service
public class FaultService {

    private final FaultRepository        faultRepo;
    private final FaultHistoryRepository historyRepo;
    private final FaultNoteRepository    noteRepo;
    private final UserRepository         userRepo;
    private final NotificationService    notificationService;
    private final ExchangeService        exchangeService;
    private final CircuitRepository      circuitRepo;
    private final CauseOfFaultRepository causeOfFaultRepo;

    public FaultService(FaultRepository faultRepo,
                        FaultHistoryRepository historyRepo,
                        FaultNoteRepository noteRepo,
                        UserRepository userRepo,
                        NotificationService notificationService,
                        ExchangeService exchangeService,
                        CircuitRepository circuitRepo,
                        CauseOfFaultRepository causeOfFaultRepo) {
        this.faultRepo   = faultRepo;
        this.historyRepo = historyRepo;
        this.noteRepo    = noteRepo;
        this.userRepo    = userRepo;
        this.notificationService = notificationService;
        this.exchangeService = exchangeService;
        this.circuitRepo = circuitRepo;
        this.causeOfFaultRepo = causeOfFaultRepo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. CLIENT REPORTS A FAULT  →  status = REPORTED
    // ══════════════════════════════════════════════════════════════════════════
    @Transactional
    public FaultDTO reportFault(ReportFaultRequest request, Long customerId,
                                String customerName, String customerPhone) {
        Fault fault = new Fault();
        fault.setFaultNumber(generateFaultNumber());
        fault.setCustomerId(customerId);
        fault.setCustomerName(customerName);
        fault.setCustomerPhone(customerPhone);
        fault.setOpmcId(request.getOpmcId());
        fault.setCategory(parseCategoryOrThrow(request.getCategory()));
        // Client-supplied free text: HTML-escape before persisting so a stored <script> payload
        // can never be rendered as executable markup by any downstream consumer (SEC-003).
        fault.setDescription(escapeHtml(request.getDescription()));
        fault.setLocationAddress(request.getLocationAddress());
        fault.setLocationCity(request.getLocationCity());
        fault.setLocationDistrict(request.getLocationDistrict());
        fault.setLatitude(request.getLatitude());
        fault.setLongitude(request.getLongitude());

        // H1b: auto-derive the nearest geocoded Exchange, only as a fallback when no Circuit was
        // manually attached and GPS is actually present. Written once at creation, not
        // re-derived on later edits (see Fault.java's nearestExchangeId field comment).
        if (fault.getCircuitId() == null && request.getLatitude() != null && request.getLongitude() != null) {
            exchangeService.findNearestGeocoded(request.getLatitude(), request.getLongitude())
                .ifPresent(match -> {
                    fault.setNearestExchangeId(match.getExchangeId());
                    fault.setNearestExchangeDistanceKm(match.getDistanceKm());
                });
        }

        fault.setPriority(parsePriorityOrDefault(request.getPriority()));
        fault.setStatus(Fault.FaultStatus.REPORTED);
        fault.setPhotoUrls(joinPhotoUrls(request.getPhotoUrls()));

        Fault saved = faultRepo.save(fault);

        // Write history — ALWAYS write history on every status change
        writeHistory(saved, null, Fault.FaultStatus.REPORTED,
                customerId, customerName, FaultHistory.ChangedByRole.CLIENT,
                "Fault reported by client");

        // Notify all admins so a new fault doesn't sit unseen
        notifyAdminsOfNewFault(saved);

        return mapToDTO(saved);
    }

    /**
     * HTML-escapes client-supplied free text. UTF-8 is passed explicitly so only the five markup
     * characters are converted — Sinhala/Tamil and other non-ASCII text in a legitimate plain-text
     * description is left untouched (the no-arg overload would turn it into numeric references).
     */
    private String escapeHtml(String value) {
        return value == null ? null : HtmlUtils.htmlEscape(value, "UTF-8");
    }

    private void notifyAdminsOfNewFault(Fault fault) {
        List<User> admins = new java.util.ArrayList<>();
        admins.addAll(userRepo.findByRoleAndIsActiveTrue(User.Role.ADMIN));
        admins.addAll(userRepo.findByRoleAndIsActiveTrue(User.Role.SUPER_ADMIN));
        for (User admin : admins) {
            notificationService.notifyFaultReported(
                admin.getId(), admin.getFcmToken(), fault.getFaultNumber(), fault.getId());
        }
    }

    // FAULT-008 (FR-21/22): updateStatus previously wrote fault_history but never told the
    // reporting client anything changed. Reuses NotificationService.notifyUser — the same FCM +
    // in-app dispatch every other fault/job/payment event already goes through — rather than a
    // separate push path.
    private void notifyCustomerOfStatusChange(Fault fault, Fault.FaultStatus newStatus) {
        User customer = userRepo.findById(fault.getCustomerId()).orElse(null);
        Notification.NotificationType type = switch (newStatus) {
            case IN_PROGRESS -> Notification.NotificationType.FAULT_IN_PROGRESS;
            case HOLD        -> Notification.NotificationType.FAULT_ON_HOLD;
            case COMPLETED   -> Notification.NotificationType.FAULT_COMPLETED;
            case CANCELLED   -> Notification.NotificationType.FAULT_CANCELLED;
            default          -> Notification.NotificationType.GENERAL;
        };
        notificationService.notifyUser(
            fault.getCustomerId(),
            customer != null ? customer.getFcmToken() : null,
            type,
            "Fault Update",
            "Fault #" + fault.getFaultNumber() + " status changed to " + newStatus + ".",
            fault.getId(), "FAULT");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. ADMIN ASSIGNS TO TEAM LEAD  →  status = ASSIGNED
    // ══════════════════════════════════════════════════════════════════════════
    @Transactional
    public FaultDTO assignToTeamLead(Long faultId, AssignFaultRequest request,
                                      Long adminId, String adminName,
                                      String teamLeadName) {
        Fault fault = findOrThrow(faultId);

        // Validate: can only assign REPORTED faults
        if (fault.getStatus() != Fault.FaultStatus.REPORTED &&
            fault.getStatus() != Fault.FaultStatus.ASSIGNED) {
            throw new RuntimeException(
                "Cannot assign fault with status: " + fault.getStatus() +
                ". Only REPORTED or ASSIGNED faults can be reassigned.");
        }

        Fault.FaultStatus oldStatus = fault.getStatus();

        fault.setAssignedTeamLeadId(request.getTeamLeadId());
        fault.setAssignedTeamLeadName(teamLeadName);
        fault.setAssignedAt(LocalDateTime.now());
        fault.setStatus(Fault.FaultStatus.ASSIGNED);
        fault.setUpdatedBy(adminId);

        // Auto-set due date based on priority (SRS §6.2.1)
        fault.setDueDate(calculateDueDate(fault.getPriority()));

        Fault saved = faultRepo.save(fault);

        writeHistory(saved, oldStatus, Fault.FaultStatus.ASSIGNED,
                adminId, adminName, FaultHistory.ChangedByRole.ADMIN,
                "Assigned to Team Lead: " + teamLeadName +
                (request.getNotes() != null ? " | Notes: " + request.getNotes() : ""));

        return mapToDTO(saved);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. TECHNICIAN UPDATES STATUS  →  IN_PROGRESS / HOLD / COMPLETED
    // ══════════════════════════════════════════════════════════════════════════
    @Transactional
    public FaultDTO updateStatus(Long faultId, UpdateFaultRequest request,
                                  Long userId, String userName,
                                  FaultHistory.ChangedByRole userRole) {
        Fault fault = findOrThrow(faultId);
        Fault.FaultStatus oldStatus = fault.getStatus();
        Fault.FaultStatus newStatus;

        try {
            newStatus = Fault.FaultStatus.valueOf(request.getNewStatus());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid status: " + request.getNewStatus() +
                ". Valid values: IN_PROGRESS, HOLD, COMPLETED, CANCELLED");
        }

        // Validate the status transition is legal
        validateTransition(oldStatus, newStatus);

        // Validate HOLD requires a reason
        if (newStatus == Fault.FaultStatus.HOLD &&
            (request.getReason() == null || request.getReason().isBlank())) {
            throw new RuntimeException("A reason is required when putting a fault on HOLD.");
        }

        // Apply the status change
        fault.setStatus(newStatus);
        fault.setUpdatedBy(userId);

        if (newStatus == Fault.FaultStatus.IN_PROGRESS && fault.getStartedAt() == null) {
            fault.setStartedAt(LocalDateTime.now());
        }
        if (newStatus == Fault.FaultStatus.HOLD) {
            fault.setHoldReason(request.getReason());
        }
        if (newStatus == Fault.FaultStatus.COMPLETED) {
            fault.setCompletedAt(LocalDateTime.now());
            fault.setCauseOfFault(request.getCauseOfFault());
            fault.setCompletionRemarks(request.getCompletionRemarks());
        }

        Fault saved = faultRepo.save(fault);

        writeHistory(saved, oldStatus, newStatus,
                userId, userName, userRole,
                request.getReason() != null ? request.getReason() :
                "Status updated to " + newStatus);

        notifyCustomerOfStatusChange(saved, newStatus);

        return mapToDTO(saved);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. CANCEL FAULT
    // ══════════════════════════════════════════════════════════════════════════
    @Transactional
    public FaultDTO cancelFault(Long faultId, String reason,
                                 Long userId, String userName,
                                 FaultHistory.ChangedByRole userRole) {
        Fault fault = findOrThrow(faultId);

        if (fault.getStatus() == Fault.FaultStatus.COMPLETED) {
            throw new RuntimeException("Cannot cancel a completed fault.");
        }

        Fault.FaultStatus oldStatus = fault.getStatus();
        fault.setStatus(Fault.FaultStatus.CANCELLED);
        fault.setUpdatedBy(userId);

        Fault saved = faultRepo.save(fault);
        writeHistory(saved, oldStatus, Fault.FaultStatus.CANCELLED,
                userId, userName, userRole,
                reason != null ? reason : "Cancelled");

        return mapToDTO(saved);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. READ METHODS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public FaultDTO getFaultById(Long id) {
        return mapToDTO(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<FaultDTO> getFaultsByOpmc(Long opmcId, String status) {
        List<Fault> faults;
        if (status != null && !status.isBlank()) {
            try {
                faults = faultRepo.findByOpmcIdAndStatusOrderByPriorityAscReportedAtAsc(
                        opmcId, Fault.FaultStatus.valueOf(status));
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Invalid status filter: " + status);
            }
        } else {
            faults = faultRepo.findByOpmcIdOrderByReportedAtDesc(opmcId);
        }
        return faults.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FaultDTO> getMyFaults(Long customerId) {
        return faultRepo.findByCustomerIdOrderByReportedAtDesc(customerId)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional
    public FaultDTO updateIssue(Long id, String category, String description,
                                String locationAddress, Double latitude, Double longitude) {
        Fault fault = findOrThrow(id);
        if (fault.getStatus() == Fault.FaultStatus.COMPLETED ||
            fault.getStatus() == Fault.FaultStatus.CANCELLED) {
            throw new RuntimeException(
                "Cannot update a " + fault.getStatus() + " issue.");
        }
        if (category != null) fault.setCategory(parseCategoryOrThrow(category));
        if (description != null && !description.isBlank()) fault.setDescription(description);
        if (locationAddress != null) fault.setLocationAddress(locationAddress);
        if (latitude != null) fault.setLatitude(latitude);
        if (longitude != null) fault.setLongitude(longitude);
        return mapToDTO(faultRepo.save(fault));
    }

    // H1c: manually attach a Circuit (Admin/Team Lead, via the cascading Opmc -> Exchange ->
    // Cab -> Dp -> Circuit picker). Deliberately a dedicated method/endpoint rather than folding
    // circuitId into updateIssue's generic partial-update — every other fault mutation in this
    // service writes a fault_history row (updateStatus, assignToTeamLead) and this should too,
    // and the circuitId existence check belongs centralized here rather than trusted from the
    // caller. writeHistory() itself is STATUS_CHANGED-specific (requires a FaultStatus pair), so
    // this writes its own FaultHistory row directly rather than force-fitting that helper.
    @Transactional
    public FaultDTO attachCircuit(Long faultId, Long circuitId,
                                   Long actorId, String actorName,
                                   FaultHistory.ChangedByRole actorRole) {
        Fault fault = findOrThrow(faultId);
        if (fault.getStatus() == Fault.FaultStatus.COMPLETED ||
            fault.getStatus() == Fault.FaultStatus.CANCELLED) {
            throw new RuntimeException(
                "Cannot attach a Circuit to a " + fault.getStatus() + " fault.");
        }

        Circuit circuit = circuitRepo.findById(circuitId)
            .orElseThrow(() -> new ResourceNotFoundException("Circuit not found with id: " + circuitId));

        String previousCircuitCode = fault.getCircuitCode();
        fault.setCircuitId(circuit.getId());
        fault.setCircuitCode(circuit.getCode());
        fault.setUpdatedBy(actorId);
        Fault saved = faultRepo.save(fault);

        FaultHistory h = new FaultHistory();
        h.setFault(saved);
        h.setFaultNumber(saved.getFaultNumber());
        h.setEventType("CIRCUIT_ATTACHED");
        h.setTitle((previousCircuitCode == null ? "Circuit attached: " : "Circuit changed: ")
            + circuit.getCode());
        h.setDescription(actorName + " [" + actorRole + "] attached Circuit " + circuit.getCode()
            + (previousCircuitCode != null ? " (was " + previousCircuitCode + ")" : ""));
        h.setPreviousValue(previousCircuitCode);
        h.setNewValue(circuit.getCode());
        h.setIsSystem(false);
        h.setIpAddress(lk.slt.fieldops.shared.RequestContext.getClientIp());
        historyRepo.save(h);

        return mapToDTO(saved);
    }

    /**
     * Stage 2 (QA_Compliance_Consolidated_Report.md causeId resolution) — Admin/Team-Lead post-hoc
     * classification, additive alongside the free-text causeOfFault Stage 1 already captures at
     * completion. Deliberately does NOT mirror attachCircuit's terminal-status guard: Circuit
     * identity is relevant during active troubleshooting and stops making sense once a fault is
     * closed, but cause classification's entire premise (see the Stage 2 design investigation) is a
     * reviewer reading a COMPLETED fault's real diagnostic input after the fact — blocking COMPLETED
     * here would forbid the primary intended use case. CANCELLED is still blocked: a cancelled fault
     * was never diagnosed, so there is nothing real to classify.
     */
    @Transactional
    public FaultDTO attachCause(Long faultId, Long causeId,
                                 Long actorId, String actorName,
                                 FaultHistory.ChangedByRole actorRole) {
        Fault fault = findOrThrow(faultId);
        if (fault.getStatus() == Fault.FaultStatus.CANCELLED) {
            throw new RuntimeException("Cannot attach a Cause to a CANCELLED fault.");
        }

        CauseOfFault cause = causeOfFaultRepo.findById(causeId)
            .orElseThrow(() -> new ResourceNotFoundException("CauseOfFault not found with id: " + causeId));

        String previousCauseCode = fault.getCauseCode();
        fault.setCauseId(cause.getId());
        fault.setCauseCode(cause.getCauseCode());
        fault.setUpdatedBy(actorId);
        Fault saved = faultRepo.save(fault);

        FaultHistory h = new FaultHistory();
        h.setFault(saved);
        h.setFaultNumber(saved.getFaultNumber());
        h.setEventType("CAUSE_CLASSIFIED");
        h.setTitle((previousCauseCode == null ? "Cause classified: " : "Cause reclassified: ")
            + cause.getCauseCode());
        h.setDescription(actorName + " [" + actorRole + "] classified the cause as "
            + cause.getCauseCode() + " (" + cause.getDescription() + ")"
            + (previousCauseCode != null ? " (was " + previousCauseCode + ")" : ""));
        h.setPreviousValue(previousCauseCode);
        h.setNewValue(cause.getCauseCode());
        h.setIsSystem(false);
        h.setIpAddress(lk.slt.fieldops.shared.RequestContext.getClientIp());
        historyRepo.save(h);

        return mapToDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<FaultDTO> getFaultsByTeamLead(Long teamLeadId) {
        return faultRepo.findByAssignedTechnicianId(teamLeadId)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * SRS 5.5.1 (Stage D) — a Team Lead's Work Group incoming queue: every open
     * fault currently routed to their Work Group, whether or not they've self-
     * assigned it yet. {@code getFaultsByTeamLead} above only returns faults
     * someone has actually claimed (assignedTeamLeadId set), so it alone can no
     * longer show a fault sitting unclaimed in the queue.
     */
    @Transactional(readOnly = true)
    public List<FaultDTO> getFaultsForTeamLeadWorkGroup(Long teamLeadUserId) {
        User teamLead = userRepo.findById(teamLeadUserId).orElse(null);
        if (teamLead == null || teamLead.getWorkgroup() == null) {
            return List.of();
        }
        return faultRepo.findOpenByWorkGroupId(teamLead.getWorkgroup().getId())
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FaultDTO> getAllFaults() {
        return faultRepo.findAll(
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "reportedAt"))
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * Admin fault list with optional server-side filters. Both filters are optional:
     * with neither supplied this is identical to {@link #getAllFaults()}, either one
     * alone narrows on that field, and supplying both AND-s them. Narrowing is done by
     * the database query, not by filtering an already-fetched list.
     *
     * <p>{@code status} accepts the display alias {@code OPEN} for {@code REPORTED},
     * matching {@code FaultDTO.getStatusDisplay()}, so a caller can filter with the same
     * value it renders.</p>
     */
    @Transactional(readOnly = true)
    public List<FaultDTO> getAllFaults(String status, String category) {
        Fault.FaultStatus   statusFilter   = parseStatusFilterOrThrow(status);
        Fault.FaultCategory categoryFilter = parseCategoryFilterOrThrow(category);

        if (statusFilter == null && categoryFilter == null) {
            return getAllFaults();
        }
        return faultRepo.findByOptionalStatusAndCategory(statusFilter, categoryFilter)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * RES-023 — OPMC-scoped variant for ADMIN callers. {@code opmcId} must be
     * derived server-side from the caller's own record (see FaultController),
     * never accepted as a client-supplied parameter — the same IDOR-safe
     * pattern ResourcePlanConfirmationService.getConfirmedPlanForTeamLead uses.
     */
    @Transactional(readOnly = true)
    public List<FaultDTO> getAllFaultsForOpmc(Long opmcId, String status, String category) {
        Fault.FaultStatus   statusFilter   = parseStatusFilterOrThrow(status);
        Fault.FaultCategory categoryFilter = parseCategoryFilterOrThrow(category);

        return faultRepo.findByOpmcIdAndOptionalStatusAndCategory(opmcId, statusFilter, categoryFilter)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FaultDTO> getOpenFaults() {
        return faultRepo.findAllOpenFaults()
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FaultHistory> getFaultHistory(Long faultId) {
        findOrThrow(faultId); // verify fault exists
        return historyRepo.findByFaultIdOrderByChangedAtAsc(faultId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. NOTES
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public FaultNote addNote(Long faultId, String noteText,
                              Long addedBy, String addedByName) {
        findOrThrow(faultId); // verify fault exists
        FaultNote note = new FaultNote();
        note.setFaultId(faultId);
        note.setNote(noteText);
        note.setAddedBy(addedBy);
        note.setAddedByName(addedByName);
        return noteRepo.save(note);
    }

    @Transactional(readOnly = true)
    public List<FaultNote> getNotes(Long faultId) {
        findOrThrow(faultId);
        return noteRepo.findByFaultIdOrderByCreatedAtDesc(faultId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Fault findOrThrow(Long id) {
        return faultRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fault not found with id: " + id));
    }

    /**
     * ALWAYS call this after every status change.
     * Inserts one row into fault_history.
     */
    private void writeHistory(Fault fault,
                               Fault.FaultStatus oldStatus,
                               Fault.FaultStatus newStatus,
                               Long changedById, String changedByName,
                               FaultHistory.ChangedByRole role,
                               String reason) {
        FaultHistory h = new FaultHistory();
        h.setFault(fault);
        h.setFaultNumber(fault.getFaultNumber());
        h.setEventType("STATUS_CHANGED");
        h.setTitle("Status: " + (oldStatus != null ? oldStatus.name() : "?") + " → " + newStatus.name());
        h.setDescription(changedByName + " [" + role + "]" + (reason != null && !reason.isBlank() ? ": " + reason : ""));
        h.setPreviousValue(oldStatus != null ? oldStatus.name() : null);
        h.setNewValue(newStatus.name());
        h.setIsSystem(false);
        h.setIpAddress(lk.slt.fieldops.shared.RequestContext.getClientIp());
        historyRepo.save(h);
    }

    /**
     * Due date rules from SRS §6.2.1:
     *   HIGH   → 4 hours
     *   MEDIUM → 24 hours
     *   LOW    → 72 hours
     */
    private LocalDateTime calculateDueDate(Fault.FaultPriority priority) {
        LocalDateTime now = LocalDateTime.now();
        return switch (priority) {
            case HIGH   -> now.plusHours(4);
            case MEDIUM -> now.plusHours(24);
            case LOW    -> now.plusHours(72);
        };
    }

    /**
     * Check that the status transition is legal.
     * Prevents impossible transitions like COMPLETED → IN_PROGRESS.
     */
    private void validateTransition(Fault.FaultStatus current,
                                     Fault.FaultStatus requested) {
        boolean valid = switch (current) {
            case REPORTED   -> requested == Fault.FaultStatus.ASSIGNED ||
                               requested == Fault.FaultStatus.CANCELLED;
            case ASSIGNED   -> requested == Fault.FaultStatus.IN_PROGRESS ||
                               requested == Fault.FaultStatus.CANCELLED;
            case IN_PROGRESS-> requested == Fault.FaultStatus.HOLD ||
                               requested == Fault.FaultStatus.COMPLETED ||
                               requested == Fault.FaultStatus.CANCELLED;
            case HOLD       -> requested == Fault.FaultStatus.IN_PROGRESS ||
                               requested == Fault.FaultStatus.ASSIGNED ||
                               requested == Fault.FaultStatus.CANCELLED;
            case COMPLETED  -> false;   // Cannot change a completed fault
            case CANCELLED  -> false;   // Cannot change a cancelled fault
        };

        if (!valid) {
            throw new RuntimeException(
                "Invalid status transition: " + current + " → " + requested +
                ". This transition is not allowed.");
        }
    }

    /** Validates and joins client-supplied photo URLs — max 5, JPEG/PNG only. */
    private String joinPhotoUrls(String[] urls) {
        if (urls == null || urls.length == 0) return null;
        if (urls.length > 5) {
            throw new RuntimeException("A maximum of 5 photos is allowed per fault report.");
        }
        for (String url : urls) {
            String lower = url == null ? "" : url.toLowerCase();
            if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png")) {
                throw new RuntimeException("Only JPEG/PNG photos are allowed: " + url);
            }
        }
        return String.join(",", urls);
    }

    private Fault.FaultCategory parseCategoryOrThrow(String category) {
        try {
            return Fault.FaultCategory.valueOf(category);
        } catch (Exception e) {
            throw new RuntimeException("Invalid category: " + category +
                ". Valid: INTERNET, PHONE, FIBER, TV, OTHER");
        }
    }

    /** Blank/absent → no filter (null). Accepts the OPEN display alias for REPORTED. */
    private Fault.FaultStatus parseStatusFilterOrThrow(String status) {
        if (status == null || status.isBlank()) return null;
        String value = status.trim().toUpperCase();
        if ("OPEN".equals(value)) return Fault.FaultStatus.REPORTED;
        try {
            return Fault.FaultStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid status filter: " + status +
                ". Valid: OPEN, REPORTED, ASSIGNED, IN_PROGRESS, HOLD, COMPLETED, CANCELLED");
        }
    }

    /** Blank/absent → no filter (null). */
    private Fault.FaultCategory parseCategoryFilterOrThrow(String category) {
        if (category == null || category.isBlank()) return null;
        return parseCategoryOrThrow(category.trim().toUpperCase());
    }

    private Fault.FaultPriority parsePriorityOrDefault(String priority) {
        if (priority == null || priority.isBlank()) return Fault.FaultPriority.MEDIUM;
        try {
            return Fault.FaultPriority.valueOf(priority);
        } catch (Exception e) {
            return Fault.FaultPriority.MEDIUM;
        }
    }

    private String generateFaultNumber() {
        int year  = LocalDateTime.now().getYear();
        long count = faultRepo.countFaultsByYear(year) + 1;
        return String.format("FLT-%d-%05d", year, count);
    }

    /** Convert Fault entity → FaultDTO */
    public FaultDTO mapToDTO(Fault f) {
        FaultDTO dto = new FaultDTO();
        dto.setId(f.getId());
        dto.setFaultNumber(f.getFaultNumber());
        dto.setOpmcId(f.getOpmcId());
        dto.setWorkGroupId(f.getWorkGroupId());
        dto.setWorkGroupName(f.getWorkGroupName());
        dto.setCustomerId(f.getCustomerId());
        dto.setCustomerName(f.getCustomerName());
        dto.setCustomerPhone(f.getCustomerPhone());
        dto.setSubscriptionNumber(f.getSubscriptionNumber());
        dto.setCategory(f.getCategory() != null ? f.getCategory().name() : null);
        dto.setDescription(f.getDescription());
        dto.setLocationAddress(f.getLocationAddress());
        dto.setLocationCity(f.getLocationCity());
        dto.setLocationDistrict(f.getLocationDistrict());
        dto.setLatitude(f.getLatitude());
        dto.setLongitude(f.getLongitude());
        dto.setNearestExchangeId(f.getNearestExchangeId());
        dto.setNearestExchangeDistanceKm(f.getNearestExchangeDistanceKm());
        dto.setCircuitId(f.getCircuitId());
        dto.setCircuitCode(f.getCircuitCode());
        dto.setCauseId(f.getCauseId());
        dto.setCauseCode(f.getCauseCode());
        dto.setPhotoUrls(f.getPhotoUrls());
        dto.setPriority(f.getPriority() != null ? f.getPriority().name() : null);
        dto.setStatus(f.getStatus() != null ? f.getStatus().name() : null);
        dto.setAssignedTeamLeadId(f.getAssignedTeamLeadId());
        dto.setAssignedTeamLeadName(f.getAssignedTeamLeadName());
        dto.setAssignedAt(f.getAssignedAt());
        dto.setDueDate(f.getDueDate());
        dto.setIsOverdue(f.getIsOverdue());
        dto.setSlaBreached(f.getSlaBreached());
        dto.setHoldReason(f.getHoldReason());
        dto.setCauseOfFault(f.getCauseOfFault());
        dto.setCompletionRemarks(f.getCompletionRemarks());
        dto.setStartedAt(f.getStartedAt());
        dto.setCompletedAt(f.getCompletedAt());
        dto.setCustomerRating(f.getCustomerRating());
        dto.setCustomerFeedback(f.getCustomerFeedback());
        dto.setReportedAt(f.getReportedAt());
        dto.setUpdatedAt(f.getUpdatedAt());
        if (f.getReportedAt() != null) {
            dto.setAgeHours(ChronoUnit.HOURS.between(f.getReportedAt(), LocalDateTime.now()));
        }
        return dto;
    }
}
