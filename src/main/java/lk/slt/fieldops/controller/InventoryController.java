package lk.slt.fieldops.inventory.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.inventory.dto.*;
import lk.slt.fieldops.inventory.entity.*;
import lk.slt.fieldops.inventory.service.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * InventoryController — REST API for Inventory management.
 *
 * BASE URL: /api/inventory
 *
 * MATERIAL CRUD
 *   POST   /api/inventory/materials                    Create material
 *   GET    /api/inventory/materials                    List all (filter by branch)
 *   GET    /api/inventory/materials/{id}               Get one material
 *   PUT    /api/inventory/materials/{id}               Update material
 *   DELETE /api/inventory/materials/{id}               Deactivate material
 *   GET    /api/inventory/materials/search?keyword=X   Search by name
 *
 * STOCK
 *   POST   /api/inventory/materials/{id}/stock         Manual stock adjustment
 *   GET    /api/inventory/materials/{id}/history       Stock transaction history
 *   GET    /api/inventory/alerts                       Low stock alerts
 *   GET    /api/inventory/alerts/branch/{branchId}     Low stock for one branch
 *
 * MATERIAL REQUESTS
 *   POST   /api/inventory/requests                     Team Lead submits request
 *   GET    /api/inventory/requests/pending             Admin: all pending requests
 *   GET    /api/inventory/requests/branch/{branchId}   Get requests for a branch
 *   PATCH  /api/inventory/requests/{id}/review         Admin: approve or reject
 *
 * CATEGORIES
 *   GET    /api/inventory/categories                   Get all categories
 *   POST   /api/inventory/categories                   Create category
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    // ── CREATE MATERIAL ───────────────────────────────────────────────────────
    @PostMapping("/materials")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Material> createMaterial(
            @Valid @RequestBody CreateMaterialRequest request,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(inventoryService.createMaterial(request, userId));
    }

    // ── LIST MATERIALS ────────────────────────────────────────────────────────
    @GetMapping("/materials")
    public ResponseEntity<List<Material>> getMaterials(
            @RequestParam(required = false) Long branchId) {
        if (branchId != null) {
            return ResponseEntity.ok(inventoryService.getByBranch(branchId));
        }
        return ResponseEntity.ok(inventoryService.getByBranch(null));
    }

    // ── GET ONE MATERIAL ──────────────────────────────────────────────────────
    @GetMapping("/materials/{id}")
    public ResponseEntity<Material> getMaterialById(@PathVariable Long id) {
        return ResponseEntity.ok(inventoryService.getMaterialById(id));
    }

    // ── UPDATE MATERIAL ───────────────────────────────────────────────────────
    @PutMapping("/materials/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Material> updateMaterial(
            @PathVariable Long id,
            @Valid @RequestBody CreateMaterialRequest request) {
        return ResponseEntity.ok(inventoryService.updateMaterial(id, request));
    }

    // ── DEACTIVATE MATERIAL ───────────────────────────────────────────────────
    @DeleteMapping("/materials/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> deactivateMaterial(@PathVariable Long id) {
        inventoryService.deactivateMaterial(id);
        return ResponseEntity.ok(Map.of("message", "Material deactivated successfully."));
    }

    // ── SEARCH MATERIALS ──────────────────────────────────────────────────────
    @GetMapping("/materials/search")
    public ResponseEntity<List<Material>> searchMaterials(
            @RequestParam String keyword) {
        return ResponseEntity.ok(inventoryService.searchMaterials(keyword));
    }

    // ── MANUAL STOCK ADJUSTMENT ───────────────────────────────────────────────
    @PostMapping("/materials/{id}/stock")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Material> adjustStock(
            @PathVariable Long id,
            @Valid @RequestBody StockAdjustRequest request,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(inventoryService.adjustStock(id, request, userId));
    }

    // ── STOCK TRANSACTION HISTORY ─────────────────────────────────────────────
    @GetMapping("/materials/{id}/history")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<StockTransaction>> getStockHistory(@PathVariable Long id) {
        return ResponseEntity.ok(inventoryService.getStockHistory(id));
    }

    // ── LOW STOCK ALERTS ──────────────────────────────────────────────────────
    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<Material>> getLowStockAlerts() {
        return ResponseEntity.ok(inventoryService.getLowStockAlerts());
    }

    @GetMapping("/alerts/branch/{branchId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<Material>> getLowStockByBranch(@PathVariable Long branchId) {
        return ResponseEntity.ok(inventoryService.getLowStockByBranch(branchId));
    }

    // ── SUBMIT MATERIAL REQUEST (Team Lead) ───────────────────────────────────
    @PostMapping("/requests")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<MaterialRequest> submitRequest(
            @Valid @RequestBody SubmitMaterialRequestDTO request,
            @AuthenticationPrincipal Long userId) {
        String userName = "User #" + userId;  // TODO Phase 3: load from User
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(inventoryService.submitRequest(request, userId, userName));
    }

    // ── GET PENDING REQUESTS (Admin) ──────────────────────────────────────────
    @GetMapping("/requests/pending")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<MaterialRequest>> getPendingRequests() {
        return ResponseEntity.ok(inventoryService.getPendingRequests());
    }

    // ── GET REQUESTS BY BRANCH ────────────────────────────────────────────────
    @GetMapping("/requests/branch/{branchId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<MaterialRequest>> getRequestsByBranch(
            @PathVariable Long branchId) {
        return ResponseEntity.ok(inventoryService.getRequestsByBranch(branchId));
    }

    // ── REVIEW REQUEST (Admin approves or rejects) ────────────────────────────
    @PatchMapping("/requests/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<MaterialRequest> reviewRequest(
            @PathVariable Long id,
            @Valid @RequestBody ReviewMaterialRequestDTO request,
            @AuthenticationPrincipal Long userId) {
        String adminName = "Admin #" + userId;
        return ResponseEntity.ok(inventoryService.reviewRequest(id, request, userId, adminName));
    }

    // ── GET ALL CATEGORIES ────────────────────────────────────────────────────
    @GetMapping("/categories")
    public ResponseEntity<List<MaterialCategory>> getCategories() {
        return ResponseEntity.ok(inventoryService.getAllCategories());
    }

    // ── CREATE CATEGORY ───────────────────────────────────────────────────────
    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<MaterialCategory> createCategory(
            @RequestBody Map<String, Object> body) {
        String name        = (String) body.get("name");
        String description = (String) body.get("description");
        Long   parentId    = body.get("parentId") != null
            ? Long.parseLong(body.get("parentId").toString()) : null;
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(inventoryService.createCategory(name, description, parentId));
    }
}
