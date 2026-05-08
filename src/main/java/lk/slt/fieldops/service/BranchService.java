package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.BranchDTO;
import lk.slt.fieldops.dto.CreateBranchRequest;
import lk.slt.fieldops.entity.Branch;
import lk.slt.fieldops.repository.BranchRepository;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * BranchService — all branch business logic lives here.
 *
 * The controller calls service methods.
 * The service calls repository methods.
 * The service converts entities to DTOs before returning.
 *
 * Methods:
 *   create()         → POST /api/branches
 *   update()         → PUT  /api/branches/{id}
 *   getById()        → GET  /api/branches/{id}
 *   getAll()         → GET  /api/branches
 *   getAllActive()    → GET  /api/branches?status=ACTIVE
 *   activate()       → PATCH /api/branches/{id}/activate
 *   deactivate()     → PATCH /api/branches/{id}/deactivate
 *   search()         → GET  /api/branches/search?keyword=colombo
 */
@Service
public class BranchService {

    private final BranchRepository branchRepository;

    // Constructor injection — no Lombok needed
    public BranchService(BranchRepository branchRepository) {
        this.branchRepository = branchRepository;
    }

    // ── CREATE ────────────────────────────────────────────────────────────────

    /**
     * Create a new branch.
     * Only SUPER_ADMIN can do this (enforced in SecurityConfig).
     */
    @Transactional
    public BranchDTO create(CreateBranchRequest request, Long createdByUserId) {
        Branch branch = new Branch();
        mapRequestToEntity(request, branch);

        if (branchRepository.existsByCode(branch.getCode())) {
            throw new RuntimeException(
                "Branch code '" + branch.getCode() + "' already exists. Please choose a different code.");
        }
        branch.setCreatedBy(createdByUserId);

        Branch saved = branchRepository.save(branch);
        return mapToDTO(saved);
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    /**
     * Update an existing branch.
     * Code (e.g. "CMB-01") cannot be changed once set.
     */
    @Transactional
    public BranchDTO update(Long id, CreateBranchRequest request) {
        Branch branch = findOrThrow(id);

        // Preserve existing code — don't allow code to change
        String savedCode = branch.getCode();
        mapRequestToEntity(request, branch);
        branch.setCode(savedCode);
        Branch saved = branchRepository.save(branch);
        return mapToDTO(saved);
    }

    // ── READ ──────────────────────────────────────────────────────────────────

    /** Get a single branch by ID. Returns 404 if not found. */
    @Transactional(readOnly = true)
    public BranchDTO getById(Long id) {
        return mapToDTO(findOrThrow(id));
    }

    /** Get all branches (Active + Inactive). Super Admin only. */
    @Transactional(readOnly = true)
    public List<BranchDTO> getAll() {
        return branchRepository.findAll()
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /** Get only ACTIVE branches — used in dropdowns (e.g. assign fault to branch). */
    @Transactional(readOnly = true)
    public List<BranchDTO> getAllActive() {
        return branchRepository.findByStatus(Branch.BranchStatus.ACTIVE)
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /** Search by branch name keyword */
    @Transactional(readOnly = true)
    public List<BranchDTO> search(String keyword) {
        return branchRepository.searchByName(keyword)
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    // ── ACTIVATE / DEACTIVATE ─────────────────────────────────────────────────

    /** Activate a branch (set status = ACTIVE) */
    @Transactional
    public BranchDTO activate(Long id) {
        Branch branch = findOrThrow(id);
        branch.setStatus(Branch.BranchStatus.ACTIVE);
        return mapToDTO(branchRepository.save(branch));
    }

    /** Deactivate a branch (set status = INACTIVE) */
    @Transactional
    public BranchDTO deactivate(Long id) {
        Branch branch = findOrThrow(id);
        branch.setStatus(Branch.BranchStatus.INACTIVE);
        return mapToDTO(branchRepository.save(branch));
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    /**
     * Find a branch by ID or throw ResourceNotFoundException (404).
     * Used internally — avoids repeating the same check in every method.
     */
    private Branch findOrThrow(Long id) {
        return branchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Branch not found with id: " + id));
    }

    /**
     * Copy all fields from the request DTO into the Branch entity.
     * Used in both create() and update() to avoid duplicate code.
     */
    private void mapRequestToEntity(CreateBranchRequest req, Branch branch) {
        branch.setName(req.getName());

        String code = req.getCode();
        if (code == null || code.isBlank()) {
            String prefix = req.getName().replaceAll("[^A-Za-z]", "").toUpperCase();
            prefix = prefix.length() >= 3 ? prefix.substring(0, 3) : (prefix + "BRN").substring(0, 3);
            code = prefix + "-" + String.format("%02d", (int)(Math.random() * 90) + 10);
        }
        branch.setCode(code);

        branch.setAddress(req.getAddress() != null && !req.getAddress().isBlank()
                ? req.getAddress() : "-");
        branch.setCity(req.getCity());
        branch.setDistrict(req.getDistrict());
        String province = req.getProvince() != null ? req.getProvince() : req.getRegion();
        branch.setProvince(province);
        branch.setPostalCode(req.getPostalCode());
        branch.setPhone(req.getPhone());
        branch.setEmail(req.getEmail());
        branch.setLatitude(req.getLatitude());
        branch.setLongitude(req.getLongitude());
        branch.setCoverageDistricts(req.getCoverageDistricts());
        branch.setCoverageCities(req.getCoverageCities());
        branch.setWorkingDays(req.getWorkingDays() != null
                ? req.getWorkingDays() : "MON,TUE,WED,THU,FRI");

        // Parse working hours from "08:00" string to LocalTime
        if (req.getWorkingHoursStart() != null) {
            branch.setWorkingHoursStart(LocalTime.parse(req.getWorkingHoursStart()));
        }
        if (req.getWorkingHoursEnd() != null) {
            branch.setWorkingHoursEnd(LocalTime.parse(req.getWorkingHoursEnd()));
        }

        // Parse branchType string to enum — default LOCAL_BRANCH
        if (req.getBranchType() != null && !req.getBranchType().isBlank()) {
            try {
                branch.setBranchType(Branch.BranchType.valueOf(req.getBranchType()));
            } catch (IllegalArgumentException e) {
                branch.setBranchType(Branch.BranchType.LOCAL_BRANCH);
            }
        } else if (branch.getBranchType() == null) {
            branch.setBranchType(Branch.BranchType.LOCAL_BRANCH);
        }
    }

    /**
     * Convert Branch entity → BranchDTO for the API response.
     * This is called mapToDTO and is used in EVERY method that returns data.
     */
    public BranchDTO mapToDTO(Branch b) {
        BranchDTO dto = new BranchDTO();
        dto.setId(b.getId());
        dto.setName(b.getName());
        dto.setCode(b.getCode());
        dto.setBranchType(b.getBranchType() != null ? b.getBranchType().name() : null);
        dto.setAddress(b.getAddress());
        dto.setCity(b.getCity());
        dto.setDistrict(b.getDistrict());
        dto.setProvince(b.getProvince());
        dto.setPostalCode(b.getPostalCode());
        dto.setPhone(b.getPhone());
        dto.setEmail(b.getEmail());
        dto.setLatitude(b.getLatitude());
        dto.setLongitude(b.getLongitude());
        dto.setCoverageDistricts(b.getCoverageDistricts());
        dto.setCoverageCities(b.getCoverageCities());
        dto.setWorkingHoursStart(b.getWorkingHoursStart() != null
                ? b.getWorkingHoursStart().toString() : null);
        dto.setWorkingHoursEnd(b.getWorkingHoursEnd() != null
                ? b.getWorkingHoursEnd().toString() : null);
        dto.setWorkingDays(b.getWorkingDays());
        dto.setStatus(b.getStatus() != null ? b.getStatus().name() : null);
        dto.setCreatedAt(b.getCreatedAt());
        dto.setUpdatedAt(b.getUpdatedAt());
        return dto;
    }
}
