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

    /**
     * Stage F #2/#3-style OPMC scoping (Exchange/Cab/Dp/Circuit gap #2, QA_Compliance_Consolidated_Report.md)
     * — same pattern as {@code PaymentService.getAll(Long)}. A Cab has no direct OPMC column; its
     * OPMC is resolved via Exchange, which is nullable (documented master-data-export gap on Cab) —
     * a Cab whose Exchange link is broken resolves to opmcId=null and is filtered out for every
     * non-Super-Admin caller, the same fail-closed behavior {@link lk.slt.fieldops.shared.OpmcAccessGuard}
     * uses everywhere else, rather than an ambiguous row leaking into every OPMC's view.
     * @param opmcFilter resolved by the caller via OpmcAccessGuard#resolveOpmcFilter, never trusted
     *                   from client input. null means unscoped (Super Admin).
     */
    @Transactional(readOnly = true)
    public List<CabDTO> getAll(Long opmcFilter) {
        return cabRepo.findAll().stream()
            .filter(c -> opmcFilter == null || opmcFilter.equals(opmcIdOf(c)))
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    /** Unscoped — direct/internal callers only. Controller callers must use the overload below. */
    @Transactional(readOnly = true)
    public List<CabDTO> getByExchange(Long exchangeId) {
        return getByExchange(exchangeId, null);
    }

    /**
     * @param exchangeId the Exchange to list Cabs under — caller-supplied, NOT trusted for access
     *                    control on its own (see opmcFilter).
     * @param opmcFilter same semantics as {@link #getAll(Long)}. Applied after the query so passing
     *                   an Exchange belonging to another OPMC can never surface real rows.
     */
    @Transactional(readOnly = true)
    public List<CabDTO> getByExchange(Long exchangeId, Long opmcFilter) {
        return cabRepo.findByExchangeId(exchangeId).stream()
            .filter(c -> opmcFilter == null || opmcFilter.equals(opmcIdOf(c)))
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
