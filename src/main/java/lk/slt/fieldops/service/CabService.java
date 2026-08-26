package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.CabDTO;
import lk.slt.fieldops.dto.CreateCabRequest;
import lk.slt.fieldops.entity.Cab;
import lk.slt.fieldops.entity.Exchange;
import lk.slt.fieldops.repository.CabRepository;
import lk.slt.fieldops.repository.ExchangeRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** CabService — basic CRUD (Stage C scaffolding, no workflow logic yet). */
@Service
public class CabService {

    private final CabRepository      cabRepo;
    private final ExchangeRepository exchangeRepo;

    public CabService(CabRepository cabRepo, ExchangeRepository exchangeRepo) {
        this.cabRepo      = cabRepo;
        this.exchangeRepo = exchangeRepo;
    }

    @Transactional
    public CabDTO create(CreateCabRequest req) {
        if (cabRepo.existsByExchangeIdAndCode(req.getExchangeId(), req.getCode())) {
            throw new RuntimeException("Cab code '" + req.getCode() + "' already exists under this Exchange.");
        }
        Cab c = new Cab();
        mapRequestToEntity(req, c);
        return mapToDTO(cabRepo.save(c));
    }

    @Transactional
    public CabDTO update(Long id, CreateCabRequest req) {
        Cab c = findOrThrow(id);
        mapRequestToEntity(req, c);
        return mapToDTO(cabRepo.save(c));
    }

    @Transactional(readOnly = true)
    public CabDTO getById(Long id) { return mapToDTO(findOrThrow(id)); }

    /** Unscoped — direct/internal callers only. Controller callers must use the overload below. */
    @Transactional(readOnly = true)
    public List<CabDTO> getAll() {
        return getAll(null);
    }

    /** @deprecated retained for source compatibility; delegates with activeOnly=false (current default — show all). */
    @Transactional(readOnly = true)
    public List<CabDTO> getAll(Long opmcFilter) {
        return getAll(opmcFilter, false);
    }

    /**
     * Stage F #2/#3-style OPMC scoping (Exchange/Cab/Dp/Circuit gap #2, QA_Compliance_Consolidated_Report.md)
     * — same pattern as {@code PaymentService.getAll(Long)}. A Cab has no direct OPMC column; its
     * OPMC is resolved via Exchange, which is nullable (documented master-data-export gap on Cab) —
     * a Cab whose Exchange link is broken resolves to opmcId=null and is filtered out for every
     * non-Super-Admin caller, the same fail-closed behavior {@link lk.slt.fieldops.shared.OpmcAccessGuard}
     * uses everywhere else, rather than an ambiguous row leaking into every OPMC's view.
     * {@code activeOnly} wires up {@code CabRepository.findByIsActiveTrue()} (Exchange/Cab/Dp/Circuit
     * + WorkGroup Minor, QA_Compliance_Consolidated_Report.md).
     * @param opmcFilter resolved by the caller via OpmcAccessGuard#resolveOpmcFilter, never trusted
     *                   from client input. null means unscoped (Super Admin).
     * @param activeOnly false (default) preserves existing behavior — all Cabs regardless of
     *                    {@code isActive}. true restricts to active ones only.
     */
    @Transactional(readOnly = true)
    public List<CabDTO> getAll(Long opmcFilter, boolean activeOnly) {
        List<Cab> base = activeOnly ? cabRepo.findByIsActiveTrue() : cabRepo.findAll();
        return base.stream()
            .filter(c -> opmcFilter == null || opmcFilter.equals(opmcIdOf(c)))
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    /** Unscoped — direct/internal callers only. Controller callers must use the overload below. */
    @Transactional(readOnly = true)
    public List<CabDTO> getByExchange(Long exchangeId) {
        return getByExchange(exchangeId, null);
    }

    /** @deprecated retained for source compatibility; delegates with activeOnly=false (current default — show all). */
    @Transactional(readOnly = true)
    public List<CabDTO> getByExchange(Long exchangeId, Long opmcFilter) {
        return getByExchange(exchangeId, opmcFilter, false);
    }

    /**
     * @param exchangeId the Exchange to list Cabs under — caller-supplied, NOT trusted for access
     *                    control on its own (see opmcFilter).
     * @param opmcFilter same semantics as {@link #getAll(Long, boolean)}. Applied after the query so
     *                   passing an Exchange belonging to another OPMC can never surface real rows.
     * @param activeOnly same semantics as {@link #getAll(Long, boolean)}, composed as an additional
     *                   in-memory filter since {@code findByExchangeId} has no active-scoped variant.
     */
    @Transactional(readOnly = true)
    public List<CabDTO> getByExchange(Long exchangeId, Long opmcFilter, boolean activeOnly) {
        return cabRepo.findByExchangeId(exchangeId).stream()
            .filter(c -> opmcFilter == null || opmcFilter.equals(opmcIdOf(c)))
            .filter(c -> !activeOnly || Boolean.TRUE.equals(c.getIsActive()))
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    private Long opmcIdOf(Cab c) {
        return (c.getExchange() != null && c.getExchange().getOpmc() != null)
            ? c.getExchange().getOpmc().getId() : null;
    }

    @Transactional
    public CabDTO deactivate(Long id) {
        Cab c = findOrThrow(id);
        c.setIsActive(false);
        return mapToDTO(cabRepo.save(c));
    }

    /** Exchange/CAB/DP/Circuit hierarchy gap #1 (QA_Compliance_Consolidated_Report.md) — same shape as WorkGroupService.activate(). */
    @Transactional
    public CabDTO activate(Long id) {
        Cab c = findOrThrow(id);
        c.setIsActive(true);
        return mapToDTO(cabRepo.save(c));
    }

    private Cab findOrThrow(Long id) {
        return cabRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Cab not found with id: " + id));
    }

    private void mapRequestToEntity(CreateCabRequest req, Cab c) {
        c.setName(req.getName());
        c.setCode(req.getCode());
        Exchange exchange = exchangeRepo.findById(req.getExchangeId())
            .orElseThrow(() -> new RuntimeException("Exchange " + req.getExchangeId() + " does not exist."));
        c.setExchange(exchange);
    }

    private CabDTO mapToDTO(Cab c) {
        CabDTO dto = new CabDTO();
        dto.setId(c.getId());
        dto.setName(c.getName());
        dto.setCode(c.getCode());
        dto.setExchangeId(c.getExchange() != null ? c.getExchange().getId() : null);
        dto.setExchangeName(c.getExchange() != null ? c.getExchange().getName() : null);
        dto.setIsActive(c.getIsActive());
        return dto;
    }
}
