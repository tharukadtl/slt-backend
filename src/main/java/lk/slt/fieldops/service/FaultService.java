package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.*;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.FaultNote;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultNoteRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *   getFaultsByBranch()    → Admin: all faults for a branch
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

    public FaultService(FaultRepository faultRepo,
                        FaultHistoryRepository historyRepo,
                        FaultNoteRepository noteRepo) {
        this.faultRepo   = faultRepo;
        this.historyRepo = historyRepo;
        this.noteRepo    = noteRepo;
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
        fault.setBranchId(request.getBranchId());
        fault.setCategory(parseCategoryOrThrow(request.getCategory()));
        fault.setDescription(request.getDescription());
        fault.setLocationAddress(request.getLocationAddress());
        fault.setLocationCity(request.getLocationCity());
        fault.setLocationDistrict(request.getLocationDistrict());
        fault.setLatitude(request.getLatitude());
        fault.setLongitude(request.getLongitude());
        fault.setPriority(parsePriorityOrDefault(request.getPriority()));
        fault.setStatus(Fault.FaultStatus.REPORTED);

        Fault saved = faultRepo.save(fault);

        // Write history — ALWAYS write history on every status change
        writeHistory(saved, null, Fault.FaultStatus.REPORTED,
                customerId, customerName, FaultHistory.ChangedByRole.CLIENT,
                "Fault reported by client");

        return mapToDTO(saved);
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
    public List<FaultDTO> getFaultsByBranch(Long branchId, String status) {
        List<Fault> faults;
        if (status != null && !status.isBlank()) {
            try {
                faults = faultRepo.findByBranchIdAndStatusOrderByPriorityAscReportedAtAsc(
                        branchId, Fault.FaultStatus.valueOf(status));
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Invalid status filter: " + status);
            }
        } else {
            faults = faultRepo.findByBranchIdOrderByReportedAtDesc(branchId);
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

    @Transactional(readOnly = true)
    public List<FaultDTO> getFaultsByTeamLead(Long teamLeadId) {
        return faultRepo.findByAssignedTechnicianId(teamLeadId)
                .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FaultDTO> getAllFaults() {
        return faultRepo.findAll(
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "reportedAt"))
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

    private Fault.FaultCategory parseCategoryOrThrow(String category) {
        try {
            return Fault.FaultCategory.valueOf(category);
        } catch (Exception e) {
            throw new RuntimeException("Invalid category: " + category +
                ". Valid: INTERNET, PHONE, TV, OTHER");
        }
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
        dto.setBranchId(f.getBranchId());
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
