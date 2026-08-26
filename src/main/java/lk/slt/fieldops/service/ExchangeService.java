package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.CreateExchangeRequest;
import lk.slt.fieldops.dto.ExchangeDTO;
import lk.slt.fieldops.dto.NearestExchangeMatch;
import lk.slt.fieldops.entity.Exchange;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.repository.ExchangeRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.shared.GeoUtils;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** ExchangeService — basic CRUD (Stage C scaffolding, no workflow logic yet). */
@Service
public class ExchangeService {

    private final ExchangeRepository exchangeRepo;
    private final OpmcRepository     opmcRepo;

    public ExchangeService(ExchangeRepository exchangeRepo, OpmcRepository opmcRepo) {
        this.exchangeRepo = exchangeRepo;
        this.opmcRepo     = opmcRepo;
    }

    @Transactional
    public ExchangeDTO create(CreateExchangeRequest req) {
        if (exchangeRepo.existsByCode(req.getCode())) {
            throw new RuntimeException("Exchange code '" + req.getCode() + "' already exists.");
        }
        Exchange e = new Exchange();
        mapRequestToEntity(req, e);
        return mapToDTO(exchangeRepo.save(e));
    }

    @Transactional
    public ExchangeDTO update(Long id, CreateExchangeRequest req) {
        Exchange e = findOrThrow(id);
        mapRequestToEntity(req, e);
        return mapToDTO(exchangeRepo.save(e));
    }

    @Transactional(readOnly = true)
    public ExchangeDTO getById(Long id) { return mapToDTO(findOrThrow(id)); }

    /** Unscoped — direct/internal callers only. Controller callers must use the overload below. */
    @Transactional(readOnly = true)
    public List<ExchangeDTO> getAll() {
        return getAll(null);
    }

    /** @deprecated retained for source compatibility; delegates with activeOnly=false (current default — show all). */
    @Transactional(readOnly = true)
    public List<ExchangeDTO> getAll(Long opmcFilter) {
        return getAll(opmcFilter, false);
    }

    /**
     * Stage F #2/#3-style OPMC scoping (Exchange/Cab/Dp/Circuit gap #2, QA_Compliance_Consolidated_Report.md)
     * — same pattern as {@code PaymentService.getAll(Long)}. {@code activeOnly} wires up
     * {@code ExchangeRepository.findByIsActiveTrue()} (Exchange/Cab/Dp/Circuit + WorkGroup Minor,
     * QA_Compliance_Consolidated_Report.md — the method existed but nothing ever called it).
     * @param opmcFilter resolved by the caller via {@link lk.slt.fieldops.shared.OpmcAccessGuard#resolveOpmcFilter},
     *                   never trusted from client input. null means unscoped (Super Admin); otherwise only
     *                   Exchanges belonging to this OPMC.
     * @param activeOnly false (default) preserves existing behavior — all Exchanges regardless of
     *                    {@code isActive}. true restricts to active ones only.
     */
    @Transactional(readOnly = true)
    public List<ExchangeDTO> getAll(Long opmcFilter, boolean activeOnly) {
        List<Exchange> base = activeOnly ? exchangeRepo.findByIsActiveTrue() : exchangeRepo.findAll();
        return base.stream()
            .filter(e -> opmcFilter == null || opmcFilter.equals(opmcIdOf(e)))
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    /** Unscoped — direct/internal callers only. Controller callers must use the overload below. */
    @Transactional(readOnly = true)
    public List<ExchangeDTO> getByOpmc(Long opmcId) {
        return getByOpmc(opmcId, null);
    }

    /** @deprecated retained for source compatibility; delegates with activeOnly=false (current default — show all). */
    @Transactional(readOnly = true)
    public List<ExchangeDTO> getByOpmc(Long opmcId, Long opmcFilter) {
        return getByOpmc(opmcId, opmcFilter, false);
    }

    /**
     * @param opmcId     the OPMC to list Exchanges under — caller-supplied, NOT trusted for access
     *                   control on its own (a non-Super-Admin caller passing another OPMC's id gets
     *                   an empty list, not that OPMC's data — see opmcFilter).
     * @param opmcFilter same semantics as {@link #getAll(Long, boolean)}. Applied after the query so
     *                   passing someone else's opmcId can never surface real rows regardless of what
     *                   the query itself returns.
     * @param activeOnly same semantics as {@link #getAll(Long, boolean)}. {@code findByOpmcId} has no
     *                   active-scoped variant, so this composes as an additional in-memory filter on
     *                   the same stream rather than a second repository query — same end result.
     */
    @Transactional(readOnly = true)
    public List<ExchangeDTO> getByOpmc(Long opmcId, Long opmcFilter, boolean activeOnly) {
        return exchangeRepo.findByOpmcId(opmcId).stream()
            .filter(e -> opmcFilter == null || opmcFilter.equals(opmcIdOf(e)))
            .filter(e -> !activeOnly || Boolean.TRUE.equals(e.getIsActive()))
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    private Long opmcIdOf(Exchange e) {
        return e.getOpmc() != null ? e.getOpmc().getId() : null;
    }

    @Transactional
    public ExchangeDTO deactivate(Long id) {
        Exchange e = findOrThrow(id);
        e.setIsActive(false);
        return mapToDTO(exchangeRepo.save(e));
    }

    /** Exchange/CAB/DP/Circuit hierarchy gap #1 (QA_Compliance_Consolidated_Report.md) — same shape as WorkGroupService.activate(). */
    @Transactional
    public ExchangeDTO activate(Long id) {
        Exchange e = findOrThrow(id);
        e.setIsActive(true);
        return mapToDTO(exchangeRepo.save(e));
    }

    /**
     * H1b: nearest Exchange to (lat, lon), searched ONLY among Exchanges that have real
     * coordinates (271/377 as of 2026-08-20 — see fieldops/scripts/geocode_master_data.py). The
     * query itself (findByLatitudeIsNotNullAndLongitudeIsNotNull) is what guarantees an
     * un-geocoded Exchange can never be silently treated as present-but-at-(0,0) — it is simply
     * not a candidate. Callers that need to know whether the match is trustworthy should compare
     * the returned distanceKm against {@link GeoUtils#NEAREST_EXCHANGE_LOW_CONFIDENCE_KM} (or use
     * FaultDTO.getNearestExchangeLowConfidence, which already does) — a match far beyond typical
     * real exchange spacing is a signal the true nearest Exchange is one of the un-geocoded ones,
     * not that this location is genuinely remote from every exchange. Only 271 candidates, so a
     * plain linear scan is fine at this scale — no spatial index needed.
     */
    @Transactional(readOnly = true)
    public Optional<NearestExchangeMatch> findNearestGeocoded(double lat, double lon) {
        List<Exchange> candidates = exchangeRepo.findByLatitudeIsNotNullAndLongitudeIsNotNull();
        Exchange best = null;
        double bestDistanceKm = Double.MAX_VALUE;
        for (Exchange e : candidates) {
            double d = GeoUtils.haversineKm(lat, lon, e.getLatitude(), e.getLongitude());
            if (d < bestDistanceKm) {
                bestDistanceKm = d;
                best = e;
            }
        }
        if (best == null) return Optional.empty();
        return Optional.of(new NearestExchangeMatch(best.getId(), bestDistanceKm));
    }

    private Exchange findOrThrow(Long id) {
        return exchangeRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Exchange not found with id: " + id));
    }

    private void mapRequestToEntity(CreateExchangeRequest req, Exchange e) {
        e.setName(req.getName());
        e.setCode(req.getCode());
        Opmc opmc = opmcRepo.findById(req.getOpmcId())
            .orElseThrow(() -> new RuntimeException("OPMC " + req.getOpmcId() + " does not exist."));
        e.setOpmc(opmc);
    }

    private ExchangeDTO mapToDTO(Exchange e) {
        ExchangeDTO dto = new ExchangeDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setCode(e.getCode());
        dto.setOpmcId(e.getOpmc() != null ? e.getOpmc().getId() : null);
        dto.setOpmcName(e.getOpmc() != null ? e.getOpmc().getName() : null);
        dto.setLatitude(e.getLatitude());
        dto.setLongitude(e.getLongitude());
        dto.setIsActive(e.getIsActive());
        return dto;
    }
}
