package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.CreateWorkGroupRequest;
import lk.slt.fieldops.dto.WorkGroupDTO;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
import lk.slt.fieldops.shared.exception.ConflictException;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * WorkGroupService — CRUD for WorkGroup (Stage C scaffolding).
 * Assignment-workflow logic (who gets routed to a work group's queue, etc.)
 * is a later stage — this is structure only.
 */
@Service
public class WorkGroupService {

    private final WorkGroupRepository workGroupRepo;
    private final OpmcRepository      opmcRepo;
    private final UserRepository      userRepo;

    public WorkGroupService(WorkGroupRepository workGroupRepo, OpmcRepository opmcRepo, UserRepository userRepo) {
        this.workGroupRepo = workGroupRepo;
        this.opmcRepo      = opmcRepo;
        this.userRepo      = userRepo;
    }

    @Transactional
    public WorkGroupDTO create(CreateWorkGroupRequest req) {
        WorkGroup wg = new WorkGroup();
        mapRequestToEntity(req, wg, null);
        return mapToDTO(workGroupRepo.save(wg));
    }

    @Transactional
    public WorkGroupDTO update(Long id, CreateWorkGroupRequest req) {
        WorkGroup wg = findOrThrow(id);
        mapRequestToEntity(req, wg, id);
        return mapToDTO(workGroupRepo.save(wg));
    }

    @Transactional(readOnly = true)
    public WorkGroupDTO getById(Long id) {
        return mapToDTO(findOrThrow(id));
    }

    /** Unscoped, activeOnly=false (current default — show all). Controller callers use the overload below. */
    @Transactional(readOnly = true)
    public List<WorkGroupDTO> getAll() {
        return getAll(false);
    }

    /**
     * {@code activeOnly} wires up {@code WorkGroupRepository.findByIsActiveTrue()}
     * (Exchange/Cab/Dp/Circuit + WorkGroup Minor, QA_Compliance_Consolidated_Report.md) — declared
     * but never called before this fix.
     * @param activeOnly false (default) preserves existing behavior — all Work Groups regardless of
     *                    {@code isActive}. true restricts to active ones only.
     */
    @Transactional(readOnly = true)
    public List<WorkGroupDTO> getAll(boolean activeOnly) {
        List<WorkGroup> base = activeOnly ? workGroupRepo.findByIsActiveTrue() : workGroupRepo.findAll();
        return base.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * Pre-existing behavior, deliberately left as-is rather than expanded: this has always returned
     * ACTIVE Work Groups only for the given OPMC (findActiveByOpmcId — there is no unfiltered
     * findByOpmcId on this repository), regardless of any activeOnly parameter. Adding a genuine
     * "all Work Groups in this OPMC, including inactive" path would mean introducing a new query
     * and would change this endpoint's long-standing default for existing callers — out of scope for
     * wiring up the previously-unused findByIsActiveTrue() method, which this already (if
     * implicitly) satisfies. See the activeOnly Minor's QA_Compliance_Consolidated_Report.md entry
     * for this scoping note.
     */
    @Transactional(readOnly = true)
    public List<WorkGroupDTO> getByOpmc(Long opmcId) {
        return workGroupRepo.findActiveByOpmcId(opmcId).stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional
    public WorkGroupDTO deactivate(Long id) {
        WorkGroup wg = findOrThrow(id);
        wg.setIsActive(false);
        return mapToDTO(workGroupRepo.save(wg));
    }

    @Transactional
    public WorkGroupDTO activate(Long id) {
        WorkGroup wg = findOrThrow(id);
        wg.setIsActive(true);
        return mapToDTO(workGroupRepo.save(wg));
    }

    private WorkGroup findOrThrow(Long id) {
        return workGroupRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("WorkGroup not found with id: " + id));
    }

    private void mapRequestToEntity(CreateWorkGroupRequest req, WorkGroup wg, Long selfId) {
        wg.setName(req.getName());

        Opmc opmc = opmcRepo.findById(req.getOpmcId())
            .orElseThrow(() -> new RuntimeException("OPMC " + req.getOpmcId() + " does not exist."));
        wg.setOpmc(opmc);

        if (req.getTeamLeadId() != null) {
            User teamLead = userRepo.findById(req.getTeamLeadId())
                .orElseThrow(() -> new RuntimeException("User " + req.getTeamLeadId() + " does not exist."));
            if (teamLead.getRole() != User.Role.TEAM_LEAD) {
                throw new RuntimeException("User " + req.getTeamLeadId() + " is not a Team Lead.");
            }
            // RES-020 — a Team Lead cannot lead two Work Groups simultaneously.
            boolean alreadyLeadsAnother = workGroupRepo.findByTeamLeadId(req.getTeamLeadId()).stream()
                .anyMatch(existing -> !existing.getId().equals(selfId));
            if (alreadyLeadsAnother) {
                throw new ConflictException(
                    "User " + req.getTeamLeadId() + " already leads another Work Group.");
            }
            wg.setTeamLead(teamLead);
        } else {
            wg.setTeamLead(null);
        }
    }

    private WorkGroupDTO mapToDTO(WorkGroup wg) {
        WorkGroupDTO dto = new WorkGroupDTO();
        dto.setId(wg.getId());
        dto.setName(wg.getName());
        dto.setOpmcId(wg.getOpmc() != null ? wg.getOpmc().getId() : null);
        dto.setOpmcName(wg.getOpmc() != null ? wg.getOpmc().getName() : null);
        dto.setTeamLeadId(wg.getTeamLead() != null ? wg.getTeamLead().getId() : null);
        dto.setTeamLeadName(wg.getTeamLead() != null ? wg.getTeamLead().getFullName() : null);
        dto.setIsActive(wg.getIsActive());
        dto.setCreatedAt(wg.getCreatedAt());
        dto.setUpdatedAt(wg.getUpdatedAt());
        return dto;
    }
}
