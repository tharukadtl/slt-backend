package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.MaterialDTO;
import lk.slt.fieldops.dto.MaterialRequestDTO;
import lk.slt.fieldops.dto.StockDTO;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.MaterialRequestService;
import lk.slt.fieldops.service.StockManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final MaterialRequestService materialRequestService;
    private final StockManagementService  stockManagementService;
    private final UserRepository          userRepo;

    // ── Material Requests ─────────────────────────────────────────────────────

    @PostMapping("/material-request")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<MaterialRequestDTO.RequestResponse> submitRequest(
            @Valid @RequestBody MaterialRequestDTO.SubmitRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/inventory/material-request userId={}", userId);
        return ResponseEntity.ok(materialRequestService.submitRequest(userId, req));
    }

    // SRS 5.5.3 (v1.9) — a Team Lead sees only their own Work Group's pending
    // requests (mobile distribution UI); Admin/SuperAdmin see everything, unchanged.
    @GetMapping("/material-requests/pending")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<MaterialRequestDTO.PendingSummaryDTO> getPendingRequests(
            @AuthenticationPrincipal Long userId) {
        log.info("GET /api/inventory/material-requests/pending");
        boolean isTeamLead = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_TEAM_LEAD"));
        if (isTeamLead) {
            User caller = userRepo.findById(userId).orElse(null);
            if (caller == null || caller.getWorkgroup() == null) {
                return ResponseEntity.ok(MaterialRequestDTO.PendingSummaryDTO.builder()
                        .totalPending(0).urgentCount(0).normalCount(0)
                        .totalEstimatedValue(0).requests(List.of())
                        .generatedAt(java.time.LocalDateTime.now())
                        .build());
            }
            return ResponseEntity.ok(
                    materialRequestService.getPendingRequestsForWorkGroup(caller.getWorkgroup().getId()));
        }
        return ResponseEntity.ok(materialRequestService.getPendingRequests());
    }

    // SRS 5.5.3 (v1.9) — a Team Lead may now approve their own Work Group's
    // requests too (service-layer check enforces the Work Group boundary).
    @PostMapping("/material-requests/{id}/approve")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<MaterialRequestDTO.RequestResponse> approveRequest(
            @PathVariable Long id,
            @Valid @RequestBody MaterialRequestDTO.ApproveRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/inventory/material-requests/{}/approve", id);
        return ResponseEntity.ok(materialRequestService.approveRequest(id, req, userId));
    }

    @PostMapping("/material-requests/{id}/reject")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<MaterialRequestDTO.RequestResponse> rejectRequest(
            @PathVariable Long id,
            @Valid @RequestBody MaterialRequestDTO.RejectRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/inventory/material-requests/{}/reject", id);
        return ResponseEntity.ok(materialRequestService.rejectRequest(id, req, userId));
    }

    @GetMapping("/material-requests/history")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<MaterialRequestDTO.HistorySummaryDTO> getHistory(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long requesterId) {
        log.info("GET /api/inventory/material-requests/history");
        return ResponseEntity.ok(materialRequestService.getHistory(status, requesterId));
    }

    @GetMapping("/material-requests/my")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<MaterialRequestDTO.HistorySummaryDTO> getMyRequests(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(materialRequestService.getHistory(status, userId));
    }

    @PostMapping("/material-requests/{id}/deliver")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<MaterialRequestDTO.RequestResponse> markDelivered(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(materialRequestService.markDelivered(id, userId));
    }

    // ── Stock Management ──────────────────────────────────────────────────────

    @PostMapping("/stock/adjust")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<StockDTO.AdjustResponse> adjustStock(
            @Valid @RequestBody StockDTO.AdjustRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/inventory/stock/adjust materialId={}, change={}",
                req.getMaterialId(), req.getQuantityChange());
        return ResponseEntity.ok(stockManagementService.adjustStock(req, userId));
    }

    @PostMapping("/stock/bulk-adjust")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<StockDTO.BulkAdjustResponse> bulkAdjustStock(
            @Valid @RequestBody StockDTO.BulkAdjustRequest req,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/inventory/stock/bulk-adjust count={}", req.getAdjustments().size());
        return ResponseEntity.ok(stockManagementService.bulkAdjustStock(req, userId));
    }

    @GetMapping("/stock/low-stock-alerts")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<StockDTO.LowStockSummaryDTO> getLowStockAlerts() {
        log.info("GET /api/inventory/stock/low-stock-alerts");
        return ResponseEntity.ok(stockManagementService.getLowStockAlerts());
    }

    @GetMapping("/stock/transactions/{materialId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<StockDTO.TransactionHistoryDTO> getTransactions(
            @PathVariable Long materialId) {
        log.info("GET /api/inventory/stock/transactions/{}", materialId);
        return ResponseEntity.ok(stockManagementService.getTransactionsByMaterial(materialId));
    }

    @GetMapping("/stock/levels")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD','TECHNICIAN')")
    public ResponseEntity<List<StockDTO.StockLevelDTO>> getAllStockLevels() {
        log.info("GET /api/inventory/stock/levels");
        return ResponseEntity.ok(stockManagementService.getAllStockLevels());
    }

    // ── Technician/Team Lead inventory browser: search by name/SKU + category filter ──
    @GetMapping("/materials/search")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<StockDTO.StockLevelDTO>> searchMaterials(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long categoryId) {
        log.info("GET /api/inventory/materials/search search={}, categoryId={}", search, categoryId);
        return ResponseEntity.ok(stockManagementService.searchStockLevels(search, categoryId));
    }

    // ── Material CRUD ─────────────────────────────────────────────────────────

    @PostMapping("/materials")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Material> createMaterial(@Valid @RequestBody MaterialDTO.CreateRequest body) {
        log.info("POST /api/inventory/materials");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(stockManagementService.createMaterial(body));
    }

    @PutMapping("/materials/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Material> updateMaterial(
            @PathVariable Long id,
            @Valid @RequestBody MaterialDTO.UpdateRequest body) {
        log.info("PUT /api/inventory/materials/{}", id);
        return ResponseEntity.ok(stockManagementService.updateMaterial(id, body));
    }
}
