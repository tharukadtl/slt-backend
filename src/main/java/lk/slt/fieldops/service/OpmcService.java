package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.OpmcDTO;
import lk.slt.fieldops.dto.CreateOpmcRequest;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OpmcService — all OPMC business logic lives here.
 * Formerly BranchService — renamed as part of the OPMC restructure (Stage B).
 *
 * The controller calls service methods.
 * The service calls repository methods.
 * The service converts entities to DTOs before returning.
 *
 * Methods:
 *   create()         → POST /api/opmcs
 *   update()         → PUT  /api/opmcs/{id}
 *   getById()        → GET  /api/opmcs/{id}
 *   getAll()         → GET  /api/opmcs
 *   getAllActive()    → GET  /api/opmcs?status=ACTIVE
 *   activate()       → PATCH /api/opmcs/{id}/activate
 *   deactivate()     → PATCH /api/opmcs/{id}/deactivate
 *   search()         → GET  /api/opmcs/search?keyword=colombo
 */
@Service
public class OpmcService {

    private final OpmcRepository opmcRepository;

    // Constructor injection — no Lombok needed
    public OpmcService(OpmcRepository opmcRepository) {
        this.opmcRepository = opmcRepository;
    }

    // ── CREATE ────────────────────────────────────────────────────────────────

    /**
     * Create a new OPMC.
     * Only SUPER_ADMIN can do this (enforced in SecurityConfig).
     */
    @Transactional
    public OpmcDTO create(CreateOpmcRequest request, Long createdByUserId) {
        Opmc opmc = new Opmc();
        mapRequestToEntity(request, opmc);

        if (opmcRepository.existsByCode(opmc.getCode())) {
            throw new RuntimeException(
                "OPMC code '" + opmc.getCode() + "' already exists. Please choose a different code.");
        }
        opmc.setCreatedBy(createdByUserId);

        Opmc saved = opmcRepository.save(opmc);
        return mapToDTO(saved);
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    /**
     * Update an existing OPMC.
     * Code (e.g. "CMB-01") cannot be changed once set.
     */
    @Transactional
    public OpmcDTO update(Long id, CreateOpmcRequest request) {
        Opmc opmc = findOrThrow(id);

        // Preserve existing code — don't allow code to change
        String savedCode = opmc.getCode();
        mapRequestToEntity(request, opmc);
        opmc.setCode(savedCode);
        Opmc saved = opmcRepository.save(opmc);
        return mapToDTO(saved);
    }

    // ── READ ──────────────────────────────────────────────────────────────────

    /** Get a single OPMC by ID. Returns 404 if not found. */
    @Transactional(readOnly = true)
    public OpmcDTO getById(Long id) {
        return mapToDTO(findOrThrow(id));
    }

    /** Get all OPMCs (Active + Inactive). Super Admin only. */
    @Transactional(readOnly = true)
    public List<OpmcDTO> getAll() {
        return opmcRepository.findAll()
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /** Get only ACTIVE OPMCs — used in dropdowns (e.g. assign fault to OPMC). */
    @Transactional(readOnly = true)
    public List<OpmcDTO> getAllActive() {
        return opmcRepository.findByStatus(Opmc.OpmcStatus.ACTIVE)
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /** Search by OPMC name keyword */
    @Transactional(readOnly = true)
    public List<OpmcDTO> search(String keyword) {
        return opmcRepository.searchByName(keyword)
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    // ── ACTIVATE / DEACTIVATE ─────────────────────────────────────────────────

    /** Activate an OPMC (set status = ACTIVE) */
    @Transactional
    public OpmcDTO activate(Long id) {
        Opmc opmc = findOrThrow(id);
        opmc.setStatus(Opmc.OpmcStatus.ACTIVE);
        return mapToDTO(opmcRepository.save(opmc));
    }

    /** Deactivate an OPMC (set status = INACTIVE) */
    @Transactional
    public OpmcDTO deactivate(Long id) {
        Opmc opmc = findOrThrow(id);
        opmc.setStatus(Opmc.OpmcStatus.INACTIVE);
        return mapToDTO(opmcRepository.save(opmc));
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    /**
     * Find an OPMC by ID or throw ResourceNotFoundException (404).
     * Used internally — avoids repeating the same check in every method.
     */
    private Opmc findOrThrow(Long id) {
        return opmcRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "OPMC not found with id: " + id));
    }

    /**
     * Copy all fields from the request DTO into the Opmc entity.
     * Used in both create() and update() to avoid duplicate code.
     */
    private void mapRequestToEntity(CreateOpmcRequest req, Opmc opmc) {
        opmc.setName(req.getName());

        String code = req.getCode();
        if (code == null || code.isBlank()) {
            String prefix = req.getName().replaceAll("[^A-Za-z]", "").toUpperCase();
            prefix = prefix.length() >= 3 ? prefix.substring(0, 3) : (prefix + "BRN").substring(0, 3);
            code = prefix + "-" + String.format("%02d", (int)(Math.random() * 90) + 10);
        }
        opmc.setCode(code);

        // Fields below are only overwritten when the request actually carries a value.
        // CreateOpmcRequest is reused for both POST (create) and PUT (update); on update,
        // any field the caller's form doesn't resend arrives here as null and must NOT
        // clobber the existing column — on create, opmc is a fresh entity with every field
        // already null, so "skip when absent" is behaviorally identical to "set null" and
        // changes nothing there. This fixes a real data-loss bug: the Admin edit form only
        // ever sends name/code/address/province/phone/email, so city/district/postalCode/
        // latitude/longitude/coverageDistricts/coverageCities were being silently nulled on
        // every single OPMC edit (see QA_Compliance_Consolidated_Report.md, Critical, 2026-09-02).
        if (req.getAddress() != null && !req.getAddress().isBlank()) {
            opmc.setAddress(req.getAddress());
        } else if (opmc.getAddress() == null) {
            opmc.setAddress("-"); // only default on create — nothing to preserve yet
        }
        if (req.getCity() != null) {
            opmc.setCity(req.getCity());
        }
        if (req.getDistrict() != null) {
            opmc.setDistrict(req.getDistrict());
        }

        String provinceStr = req.getProvince() != null ? req.getProvince() : req.getRegion();
        if (provinceStr != null && !provinceStr.isBlank()) {
            try {
                opmc.setProvince(Opmc.Province.valueOf(provinceStr.trim().toUpperCase().replace(' ', '_')));
            } catch (IllegalArgumentException e) {
                opmc.setProvince(null);
            }
        }

        if (req.getPostalCode() != null) {
            opmc.setPostalCode(req.getPostalCode());
        }
        if (req.getPhone() != null) {
            opmc.setPhone(req.getPhone());
        }
        if (req.getEmail() != null) {
            opmc.setEmail(req.getEmail());
        }
        if (req.getLatitude() != null) {
            opmc.setLatitude(req.getLatitude());
        }
        if (req.getLongitude() != null) {
            opmc.setLongitude(req.getLongitude());
        }
        if (req.getCoverageDistricts() != null) {
            opmc.setCoverageDistricts(req.getCoverageDistricts());
        }
        if (req.getCoverageCities() != null) {
            opmc.setCoverageCities(req.getCoverageCities());
        }
        if (req.getWorkingDays() != null && !req.getWorkingDays().isBlank()) {
            opmc.setWorkingDays(req.getWorkingDays());
        } else if (opmc.getWorkingDays() == null) {
            opmc.setWorkingDays("MON,TUE,WED,THU,FRI"); // only default on create
        }

        // Parse working hours from "08:00" string to LocalTime
        if (req.getWorkingHoursStart() != null) {
            opmc.setWorkingHoursStart(LocalTime.parse(req.getWorkingHoursStart()));
        }
        if (req.getWorkingHoursEnd() != null) {
            opmc.setWorkingHoursEnd(LocalTime.parse(req.getWorkingHoursEnd()));
        }

        // Parse opmcType string to enum — default LOCAL_BRANCH
        if (req.getOpmcType() != null && !req.getOpmcType().isBlank()) {
            try {
                opmc.setOpmcType(Opmc.OpmcType.valueOf(req.getOpmcType()));
            } catch (IllegalArgumentException e) {
                opmc.setOpmcType(Opmc.OpmcType.LOCAL_BRANCH);
            }
        } else if (opmc.getOpmcType() == null) {
            opmc.setOpmcType(Opmc.OpmcType.LOCAL_BRANCH);
        }
    }

    /**
     * Convert Opmc entity → OpmcDTO for the API response.
     * This is called mapToDTO and is used in EVERY method that returns data.
     */
    public OpmcDTO mapToDTO(Opmc o) {
        OpmcDTO dto = new OpmcDTO();
        dto.setId(o.getId());
        dto.setName(o.getName());
        dto.setCode(o.getCode());
        dto.setOpmcType(o.getOpmcType() != null ? o.getOpmcType().name() : null);
        dto.setAddress(o.getAddress());
        dto.setCity(o.getCity());
        dto.setDistrict(o.getDistrict());
        dto.setProvince(o.getProvince() != null ? o.getProvince().name() : null);
        dto.setPostalCode(o.getPostalCode());
        dto.setPhone(o.getPhone());
        dto.setEmail(o.getEmail());
        dto.setLatitude(o.getLatitude());
        dto.setLongitude(o.getLongitude());
        dto.setCoverageDistricts(o.getCoverageDistricts());
        dto.setCoverageCities(o.getCoverageCities());
        dto.setWorkingHoursStart(o.getWorkingHoursStart() != null
                ? o.getWorkingHoursStart().toString() : null);
        dto.setWorkingHoursEnd(o.getWorkingHoursEnd() != null
                ? o.getWorkingHoursEnd().toString() : null);
        dto.setWorkingDays(o.getWorkingDays());
        dto.setStatus(o.getStatus() != null ? o.getStatus().name() : null);
        dto.setCreatedAt(o.getCreatedAt());
        dto.setUpdatedAt(o.getUpdatedAt());
        return dto;
    }
}
