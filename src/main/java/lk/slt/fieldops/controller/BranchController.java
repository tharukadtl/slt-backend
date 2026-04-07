package lk.slt.fieldops.branch.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.branch.dto.BranchDTO;
import lk.slt.fieldops.branch.dto.CreateBranchRequest;
import lk.slt.fieldops.branch.service.BranchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * BranchController — all branch REST API endpoints.
 *
 * Base URL: /api/branches
 *
 * All endpoints require SUPER_ADMIN role (configured in SecurityConfig).
 * The @PreAuthorize annotations here add a second layer of protection.
 *
 * ── ENDPOINTS ────────────────────────────────────────────────────────────────
 *
 * POST   /api/branches                      Create a new branch
 * GET    /api/branches                      Get all branches
 * GET    /api/branches/active               Get only active branches
 * GET    /api/branches/{id}                 Get one branch by ID
 * PUT    /api/branches/{id}                 Update a branch
 * PATCH  /api/branches/{id}/activate        Activate a branch
 * PATCH  /api/branches/{id}/deactivate      Deactivate a branch
 * GET    /api/branches/search?keyword=XXX   Search by name
 *
 * ── HOW TO TEST IN POSTMAN ───────────────────────────────────────────────────
 *
 * 1. First login to get a token:
 *    POST http://localhost:8080/api/auth/login
 *    Body: { "username": "superadmin", "password": "Admin@2024" }
 *    Copy the "accessToken" from the response.
 *
 * 2. For all branch requests, add this header:
 *    Authorization: Bearer <paste your token here>
 *
 * 3. Create a branch:
 *    POST http://localhost:8080/api/branches
 *    Body:
 *    {
 *      "name":       "SLT Colombo North",
 *      "code":       "CMB-02",
 *      "branchType": "LOCAL_BRANCH",
 *      "address":    "No. 50, Baseline Road, Colombo 09",
 *      "city":       "Colombo",
 *      "district":   "Colombo",
 *      "phone":      "0112345678",
 *      "email":      "colombonorth@slt.lk",
 *      "latitude":   6.9344,
 *      "longitude":  79.8658
 *    }
 */
@RestController
@RequestMapping("/api/branches")
public class BranchController {

    private final BranchService branchService;

    public BranchController(BranchService branchService) {
        this.branchService = branchService;
    }

    // ── POST /api/branches ────────────────────────────────────────────────────
    /**
     * Create a new branch.
     * Returns 201 CREATED with the new branch data.
     * @AuthenticationPrincipal Long userId — gets the logged-in user's ID from JWT
     */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<BranchDTO> create(
            @Valid @RequestBody CreateBranchRequest request,
            @AuthenticationPrincipal Long userId) {

        BranchDTO created = branchService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── GET /api/branches ─────────────────────────────────────────────────────
    /**
     * Get all branches (active + inactive).
     * Supports optional ?status=ACTIVE filter.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<List<BranchDTO>> getAll(
            @RequestParam(required = false) String status) {

        if ("ACTIVE".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(branchService.getAllActive());
        }
        return ResponseEntity.ok(branchService.getAll());
    }

    // ── GET /api/branches/active ──────────────────────────────────────────────
    /**
     * Get only ACTIVE branches.
     * Used in dropdowns across the system (assign fault, create user, etc.)
     * All roles can access this.
     */
    @GetMapping("/active")
    public ResponseEntity<List<BranchDTO>> getActive() {
        return ResponseEntity.ok(branchService.getAllActive());
    }

    // ── GET /api/branches/{id} ────────────────────────────────────────────────
    /**
     * Get a single branch by its ID.
     * Returns 404 if branch not found.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<BranchDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(branchService.getById(id));
    }

    // ── PUT /api/branches/{id} ────────────────────────────────────────────────
    /**
     * Update an existing branch.
     * Note: branch code cannot be changed.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<BranchDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateBranchRequest request) {

        return ResponseEntity.ok(branchService.update(id, request));
    }

    // ── PATCH /api/branches/{id}/activate ─────────────────────────────────────
    /**
     * Activate a branch (sets status = ACTIVE).
     */
    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<BranchDTO> activate(@PathVariable Long id) {
        return ResponseEntity.ok(branchService.activate(id));
    }

    // ── PATCH /api/branches/{id}/deactivate ───────────────────────────────────
    /**
     * Deactivate a branch (sets status = INACTIVE).
     */
    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<BranchDTO> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(branchService.deactivate(id));
    }

    // ── GET /api/branches/search?keyword=colombo ──────────────────────────────
    /**
     * Search branches by name keyword.
     * Example: GET /api/branches/search?keyword=colombo
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<List<BranchDTO>> search(
            @RequestParam String keyword) {

        return ResponseEntity.ok(branchService.search(keyword));
    }

    // ── DELETE /api/branches/{id} ─────────────────────────────────────────────
    /**
     * Branches are never hard-deleted — they are deactivated instead.
     * This endpoint returns a clear message explaining why.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        // Deactivate instead of delete — preserves data integrity
        branchService.deactivate(id);
        return ResponseEntity.ok(Map.of(
            "message", "Branch deactivated successfully. Branches are not permanently deleted to preserve historical data.",
            "status",  "INACTIVE"
        ));
    }
}
