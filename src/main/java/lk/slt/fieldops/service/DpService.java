package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.CreateDpRequest;
import lk.slt.fieldops.dto.DpDTO;
import lk.slt.fieldops.entity.Cab;
import lk.slt.fieldops.entity.Dp;
import lk.slt.fieldops.repository.CabRepository;
import lk.slt.fieldops.repository.DpRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** DpService — basic CRUD (Stage C scaffolding, no workflow logic yet). */
@Service
public class DpService {

    private final DpRepository  dpRepo;
    private final CabRepository cabRepo;

    public DpService(DpRepository dpRepo, CabRepository cabRepo) {
        this.dpRepo  = dpRepo;
        this.cabRepo = cabRepo;
    }

    @Transactional
    public DpDTO create(CreateDpRequest req) {
        if (dpRepo.existsByCabIdAndCode(req.getCabId(), req.getCode())) {
            throw new RuntimeException("DP code '" + req.getCode() + "' already exists under this Cab.");
        }
        Dp dp = new Dp();
        mapRequestToEntity(req, dp);
        return mapToDTO(dpRepo.save(dp));
    }

    @Transactional
    public DpDTO update(Long id, CreateDpRequest req) {
        Dp dp = findOrThrow(id);
        mapRequestToEntity(req, dp);
        return mapToDTO(dpRepo.save(dp));
    }

    @Transactional(readOnly = true)
    public DpDTO getById(Long id) { return mapToDTO(findOrThrow(id)); }

    /** Unscoped — direct/internal callers only. Controller callers must use the overload below. */
    @Transactional(readOnly = true)
    public List<DpDTO> getAll() {
        return getAll(null);
    }

    /**
     * Stage F #2/#3-style OPMC scoping (Exchange/Cab/Dp/Circuit gap #2, QA_Compliance_Consolidated_Report.md)
     * — same pattern as {@code PaymentService.getAll(Long)}. A DP's OPMC is resolved via
     * Cab -> Exchange, and Cab.exchange is nullable, so a DP under a Cab with a broken Exchange
     * link resolves to opmcId=null and is filtered out for every non-Super-Admin caller — fail
     * closed, same as {@link lk.slt.fieldops.shared.OpmcAccessGuard} everywhere else.
     * @param opmcFilter resolved by the caller via OpmcAccessGuard#resolveOpmcFilter, never trusted
     *                   from client input. null means unscoped (Super Admin).
     */
    @Transactional(readOnly = true)
    public List<DpDTO> getAll(Long opmcFilter) {
        return dpRepo.findAll().stream()
            .filter(dp -> opmcFilter == null || opmcFilter.equals(opmcIdOf(dp)))
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    /** Unscoped — direct/internal callers only. Controller callers must use the overload below. */
    @Transactional(readOnly = true)
    public List<DpDTO> getByCab(Long cabId) {
        return getByCab(cabId, null);
    }

    /**
     * @param cabId      the Cab to list DPs under — caller-supplied, NOT trusted for access
     *                   control on its own (see opmcFilter).
     * @param opmcFilter same semantics as {@link #getAll(Long)}. Applied after the query so passing
     *                   a Cab belonging to another OPMC can never surface real rows.
     */
    @Transactional(readOnly = true)
    public List<DpDTO> getByCab(Long cabId, Long opmcFilter) {
        return dpRepo.findByCabId(cabId).stream()
            .filter(dp -> opmcFilter == null || opmcFilter.equals(opmcIdOf(dp)))
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    private Long opmcIdOf(Dp dp) {
        if (dp.getCab() == null || dp.getCab().getExchange() == null
                || dp.getCab().getExchange().getOpmc() == null) {
            return null;
        }
        return dp.getCab().getExchange().getOpmc().getId();
    }

    @Transactional
    public DpDTO deactivate(Long id) {
        Dp dp = findOrThrow(id);
        dp.setIsActive(false);
        return mapToDTO(dpRepo.save(dp));
    }

    /** Exchange/CAB/DP/Circuit hierarchy gap #1 (QA_Compliance_Consolidated_Report.md) — same shape as WorkGroupService.activate(). */
    @Transactional
    public DpDTO activate(Long id) {
        Dp dp = findOrThrow(id);
        dp.setIsActive(true);
        return mapToDTO(dpRepo.save(dp));
    }

    private Dp findOrThrow(Long id) {
        return dpRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("DP not found with id: " + id));
    }

    private void mapRequestToEntity(CreateDpRequest req, Dp dp) {
        dp.setName(req.getName());
        dp.setCode(req.getCode());
        Cab cab = cabRepo.findById(req.getCabId())
            .orElseThrow(() -> new RuntimeException("Cab " + req.getCabId() + " does not exist."));
        dp.setCab(cab);
    }

    private DpDTO mapToDTO(Dp dp) {
        DpDTO dto = new DpDTO();
        dto.setId(dp.getId());
        dto.setName(dp.getName());
        dto.setCode(dp.getCode());
        dto.setCabId(dp.getCab() != null ? dp.getCab().getId() : null);
        dto.setCabName(dp.getCab() != null ? dp.getCab().getName() : null);
        dto.setIsActive(dp.getIsActive());
        return dto;
    }
}
