package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.CircuitDTO;
import lk.slt.fieldops.dto.CreateCircuitRequest;
import lk.slt.fieldops.entity.Circuit;
import lk.slt.fieldops.entity.Dp;
import lk.slt.fieldops.repository.CircuitRepository;
import lk.slt.fieldops.repository.DpRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CircuitService — basic CRUD (Stage C scaffolding, no workflow logic yet).
 * A Circuit is the actual attach point a Fault references, resolved to its
 * full OPMC → Exchange → CAB → DP chain via the DP relationship.
 */
@Service
public class CircuitService {

    private final CircuitRepository circuitRepo;
    private final DpRepository      dpRepo;

    public CircuitService(CircuitRepository circuitRepo, DpRepository dpRepo) {
        this.circuitRepo = circuitRepo;
        this.dpRepo      = dpRepo;
    }

    @Transactional
    public CircuitDTO create(CreateCircuitRequest req) {
        if (circuitRepo.existsByCode(req.getCode())) {
            throw new RuntimeException("Circuit code '" + req.getCode() + "' already exists.");
        }
        Circuit c = new Circuit();
        mapRequestToEntity(req, c);
        return mapToDTO(circuitRepo.save(c));
    }

    @Transactional
    public CircuitDTO update(Long id, CreateCircuitRequest req) {
        Circuit c = findOrThrow(id);
        mapRequestToEntity(req, c);
        return mapToDTO(circuitRepo.save(c));
    }

    @Transactional(readOnly = true)
    public CircuitDTO getById(Long id) { return mapToDTO(findOrThrow(id)); }

    /** Unscoped — direct/internal callers only. Controller callers must use the overload below. */
    @Transactional(readOnly = true)
    public List<CircuitDTO> getAll() {
        return getAll(null);
    }

    /** @deprecated retained for source compatibility; delegates with activeOnly=false (current default — show all). */
    @Transactional(readOnly = true)
    public List<CircuitDTO> getAll(Long opmcFilter) {
        return getAll(opmcFilter, false);
    }

    /**
     * Stage F #2/#3-style OPMC scoping (Exchange/Cab/Dp/Circuit gap #2, QA_Compliance_Consolidated_Report.md)
     * — same pattern as {@code PaymentService.getAll(Long)}. A Circuit's OPMC is resolved via
     * DP -> Cab -> Exchange, and both Circuit.dp and Cab.exchange are nullable, so a Circuit whose
     * chain is broken anywhere resolves to opmcId=null and is filtered out for every non-Super-Admin
     * caller — fail closed, same as {@link lk.slt.fieldops.shared.OpmcAccessGuard} everywhere else.
     * {@code activeOnly} wires up {@code CircuitRepository.findByIsActiveTrue()}
     * (Exchange/Cab/Dp/Circuit + WorkGroup Minor, QA_Compliance_Consolidated_Report.md).
     * @param opmcFilter resolved by the caller via OpmcAccessGuard#resolveOpmcFilter, never trusted
     *                   from client input. null means unscoped (Super Admin).
     * @param activeOnly false (default) preserves existing behavior — all Circuits regardless of
     *                    {@code isActive}. true restricts to active ones only.
     */
    @Transactional(readOnly = true)
    public List<CircuitDTO> getAll(Long opmcFilter, boolean activeOnly) {
        List<Circuit> base = activeOnly ? circuitRepo.findByIsActiveTrue() : circuitRepo.findAll();
        return base.stream()
            .filter(c -> opmcFilter == null || opmcFilter.equals(opmcIdOf(c)))
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    /** Unscoped — direct/internal callers only. Controller callers must use the overload below. */
    @Transactional(readOnly = true)
    public List<CircuitDTO> getByDp(Long dpId) {
        return getByDp(dpId, null);
    }

    /** @deprecated retained for source compatibility; delegates with activeOnly=false (current default — show all). */
    @Transactional(readOnly = true)
    public List<CircuitDTO> getByDp(Long dpId, Long opmcFilter) {
        return getByDp(dpId, opmcFilter, false);
    }

    /**
     * @param dpId       the DP to list Circuits under — caller-supplied, NOT trusted for access
     *                   control on its own (see opmcFilter).
     * @param opmcFilter same semantics as {@link #getAll(Long, boolean)}. Applied after the query so
     *                   passing a DP belonging to another OPMC can never surface real rows.
     * @param activeOnly same semantics as {@link #getAll(Long, boolean)}, composed as an additional
     *                   in-memory filter since {@code findByDpId} has no active-scoped variant.
     */
    @Transactional(readOnly = true)
    public List<CircuitDTO> getByDp(Long dpId, Long opmcFilter, boolean activeOnly) {
        return circuitRepo.findByDpId(dpId).stream()
            .filter(c -> opmcFilter == null || opmcFilter.equals(opmcIdOf(c)))
            .filter(c -> !activeOnly || Boolean.TRUE.equals(c.getIsActive()))
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    private Long opmcIdOf(Circuit c) {
        if (c.getDp() == null || c.getDp().getCab() == null
                || c.getDp().getCab().getExchange() == null
                || c.getDp().getCab().getExchange().getOpmc() == null) {
            return null;
        }
        return c.getDp().getCab().getExchange().getOpmc().getId();
    }

    @Transactional
    public CircuitDTO deactivate(Long id) {
        Circuit c = findOrThrow(id);
        c.setIsActive(false);
        return mapToDTO(circuitRepo.save(c));
    }

    /** Exchange/CAB/DP/Circuit hierarchy gap #1 (QA_Compliance_Consolidated_Report.md) — same shape as WorkGroupService.activate(). */
    @Transactional
    public CircuitDTO activate(Long id) {
        Circuit c = findOrThrow(id);
        c.setIsActive(true);
        return mapToDTO(circuitRepo.save(c));
    }

    private Circuit findOrThrow(Long id) {
        return circuitRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Circuit not found with id: " + id));
    }

    private void mapRequestToEntity(CreateCircuitRequest req, Circuit c) {
        c.setCode(req.getCode());
        Dp dp = dpRepo.findById(req.getDpId())
            .orElseThrow(() -> new RuntimeException("DP " + req.getDpId() + " does not exist."));
        c.setDp(dp);
    }

    private CircuitDTO mapToDTO(Circuit c) {
        CircuitDTO dto = new CircuitDTO();
        dto.setId(c.getId());
        dto.setCode(c.getCode());
        dto.setIsActive(c.getIsActive());
        if (c.getCircuitCategory() != null) {
            dto.setCircuitCategoryId(c.getCircuitCategory().getId());
            dto.setCircuitCategoryCode(c.getCircuitCategory().getCode());
        }
        if (c.getDp() != null) {
            dto.setDpId(c.getDp().getId());
            dto.setDpName(c.getDp().getName());
            if (c.getDp().getCab() != null) {
                dto.setCabId(c.getDp().getCab().getId());
                dto.setCabName(c.getDp().getCab().getName());
                if (c.getDp().getCab().getExchange() != null) {
                    dto.setExchangeId(c.getDp().getCab().getExchange().getId());
                    dto.setExchangeName(c.getDp().getCab().getExchange().getName());
                    if (c.getDp().getCab().getExchange().getOpmc() != null) {
                        dto.setOpmcId(c.getDp().getCab().getExchange().getOpmc().getId());
                        dto.setOpmcName(c.getDp().getCab().getExchange().getOpmc().getName());
                    }
                }
            }
        }
        return dto;
    }
}
