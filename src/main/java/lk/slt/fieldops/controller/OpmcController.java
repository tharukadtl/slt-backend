package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.OpmcDTO;
import lk.slt.fieldops.dto.CreateOpmcRequest;
import lk.slt.fieldops.service.OpmcService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * OpmcController — all OPMC REST API endpoints.
 * Formerly BranchController — renamed as part of the OPMC restructure (Stage B).
 * The URL path also moved: /api/branches → /api/opmcs.
 *
 * Base URL: /api/opmcs
 *
 * All endpoints require SUPER_ADMIN role (configured in SecurityConfig).
 * The @PreAuthorize annotations here add a second layer of protection.
 *
 * ── ENDPOINTS ────────────────────────────────────────────────────────────────
 *
 * POST   /api/opmcs                      Create a new OPMC
 * GET    /api/opmcs                      Get all OPMCs
 * GET    /api/opmcs/active               Get only active OPMCs
 * GET    /api/opmcs/{id}                 Get one OPMC by ID
 * PUT    /api/opmcs/{id}                 Update an OPMC
 * PATCH  /api/opmcs/{id}/activate        Activate an OPMC
 * PATCH  /api/opmcs/{id}/deactivate      Deactivate an OPMC
 * GET    /api/opmcs/search?keyword=XXX   Search by name
 *
 * ── HOW TO TEST IN POSTMAN ───────────────────────────────────────────────────
 *
 * 1. First login to get a token:
 *    POST http://localhost:8080/api/auth/login
 *    Body: { "username": "superadmin", "password": "Admin@2024" }
 *    Copy the "accessToken" from the response.
 *
 * 2. For all OPMC requests, add this header:
 *    Authorization: Bearer <paste your token here>
 *
 * 3. Create an OPMC:
 *    POST http://localhost:8080/api/opmcs
 *    Body:
 *    {
 *      "name":       "SLT Colombo North",
 *      "code":       "CMB-02",
 *      "opmcType":   "LOCAL_BRANCH",
 *      "address":    "No. 50, Baseline Road, Colombo 09",
 *      "city":       "Colombo",
 *      "district":   "Colombo",
 *      "province":   "WESTERN",
 *      "phone":      "0112345678",
 *      "email":      "colombonorth@slt.lk",
 *      "latitude":   6.9344,
 *      "longitude":  79.8658
 *    }
 */
@RestController
@RequestMapping("/api/opmcs")
public class OpmcController {

    private final OpmcService opmcService;

    public OpmcController(OpmcService opmcService) {
        this.opmcService = opmcService;
    }

    // ── POST /api/opmcs ───────────────────────────────────────────────────────
    /**
     * Create a new OPMC.
     * Returns 201 CREATED with the new OPMC data.
     * @AuthenticationPrincipal Long userId — gets the logged-in user's ID from JWT
     */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<OpmcDTO> create(
            @Valid @RequestBody CreateOpmcRequest request,
            @AuthenticationPrincipal Long userId) {

        OpmcDTO created = opmcService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── GET /api/opmcs ────────────────────────────────────────────────────────
    /**
     * Get all OPMCs (active + inactive).
     * Supports optional ?status=ACTIVE filter.
     */
    // H1c: TEAM_LEAD read access, same reason as ExchangeController's comment. Note the
    // cascading Circuit picker itself can already use the always-open GET /api/opmcs/active
    // below for its first level -- this widening is for consistency with the other three
    // hierarchy controllers, not a hard dependency of the picker.
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'TEAM_LEAD')")
    public ResponseEntity<List<OpmcDTO>> getAll(
            @RequestParam(required = false) String status) {

        if ("ACTIVE".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(opmcService.getAllActive());
        }
        return ResponseEntity.ok(opmcService.getAll());
    }

    // ── GET /api/opmcs/active ─────────────────────────────────────────────────
    /**
     * Get only ACTIVE OPMCs.
     * Used in dropdowns across the system (assign fault, create user, etc.)
     * All roles can access this.
     */
    @GetMapping("/active")
    public ResponseEntity<List<OpmcDTO>> getActive() {
        return ResponseEntity.ok(opmcService.getAllActive());
    }

    // ── GET /api/opmcs/{id} ───────────────────────────────────────────────────
    /**
     * Get a single OPMC by its ID.
     * Returns 404 if OPMC not found.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'TEAM_LEAD')")
    public ResponseEntity<OpmcDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(opmcService.getById(id));
    }

    // ── PUT /api/opmcs/{id} ───────────────────────────────────────────────────
    /**
     * Update an existing OPMC.
     * Note: OPMC code cannot be changed.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<OpmcDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateOpmcRequest request) {

        return ResponseEntity.ok(opmcService.update(id, request));
    }

    // ── PATCH /api/opmcs/{id}/activate ────────────────────────────────────────
    /**
     * Activate an OPMC (sets status = ACTIVE).
     */
    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<OpmcDTO> activate(@PathVariable Long id) {
        return ResponseEntity.ok(opmcService.activate(id));
    }

    // ── PATCH /api/opmcs/{id}/deactivate ──────────────────────────────────────
    /**
     * Deactivate an OPMC (sets status = INACTIVE).
     */
    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<OpmcDTO> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(opmcService.deactivate(id));
    }

    // ── GET /api/opmcs/search?keyword=colombo ─────────────────────────────────
    /**
     * Search OPMCs by name keyword.
     * Example: GET /api/opmcs/search?keyword=colombo
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<List<OpmcDTO>> search(
            @RequestParam String keyword) {

        return ResponseEntity.ok(opmcService.search(keyword));
    }

    // ── DELETE /api/opmcs/{id} ────────────────────────────────────────────────
    /**
     * OPMCs are never hard-deleted — they are deactivated instead.
     * This endpoint returns a clear message explaining why.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        // Deactivate instead of delete — preserves data integrity
        opmcService.deactivate(id);
        return ResponseEntity.ok(Map.of(
            "message", "OPMC deactivated successfully. OPMCs are not permanently deleted to preserve historical data.",
            "status",  "INACTIVE"
        ));
    }
}
