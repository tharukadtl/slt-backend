package lk.slt.fieldops.inventory.service;

import lk.slt.fieldops.inventory.dto.*;
import lk.slt.fieldops.inventory.entity.*;
import lk.slt.fieldops.inventory.repository.*;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * InventoryService — handles all inventory operations.
 *
 * KEY methods:
 *   deductStockForJob()    → Called by JobService when tech logs material
 *   approveMaterialRequest() → Admin approves → stock auto-deducted
 *   getLowStockAlerts()    → Any material at/below minimum threshold
 */
@Service
public class InventoryService {

    private final MaterialRepository         materialRepo;
    private final MaterialCategoryRepository categoryRepo;
    private final StockTransactionRepository txRepo;
    private final MaterialRequestRepository  requestRepo;

    public InventoryService(MaterialRepository materialRepo,
                            MaterialCategoryRepository categoryRepo,
                            StockTransactionRepository txRepo,
                            MaterialRequestRepository requestRepo) {
        this.materialRepo = materialRepo;
        this.categoryRepo = categoryRepo;
        this.txRepo       = txRepo;
        this.requestRepo  = requestRepo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MATERIAL CRUD
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Material createMaterial(CreateMaterialRequest req, Long createdBy) {
        if (materialRepo.existsBySku(req.getSku())) {
            throw new RuntimeException("SKU '" + req.getSku() + "' already exists.");
        }

        Material m = new Material();
        m.setName(req.getName());
        m.setSku(req.getSku());
        m.setDescription(req.getDescription());
        m.setCategoryId(req.getCategoryId());
        m.setBranchId(req.getBranchId());
        m.setUnit(req.getUnit());
        m.setUnitPrice(req.getUnitPrice() != null ? req.getUnitPrice() : BigDecimal.ZERO);
        m.setCurrentStock(req.getCurrentStock() != null ? req.getCurrentStock() : BigDecimal.ZERO);
        m.setMinimumThreshold(req.getMinimumThreshold() != null ? req.getMinimumThreshold() : BigDecimal.ZERO);

        try {
            m.setChargeType(Material.ChargeType.valueOf(req.getChargeType()));
        } catch (Exception e) {
            m.setChargeType(Material.ChargeType.FOC);
        }

        updateStockStatus(m);
        Material saved = materialRepo.save(m);

        // Log initial stock as STOCK_IN transaction if stock > 0
        if (saved.getCurrentStock().compareTo(BigDecimal.ZERO) > 0) {
            logTransaction(saved.getId(), saved.getName(),
                    StockTransaction.TransactionType.STOCK_IN,
                    BigDecimal.ZERO, saved.getCurrentStock(), saved.getCurrentStock(),
                    StockTransaction.ReferenceType.INITIAL_STOCK, null,
                    "Initial stock on material creation", createdBy);
        }

        return saved;
    }

    @Transactional
    public Material updateMaterial(Long id, CreateMaterialRequest req) {
        Material m = findMaterialOrThrow(id);
        m.setName(req.getName());
        m.setDescription(req.getDescription());
        m.setCategoryId(req.getCategoryId());
        m.setUnit(req.getUnit());
        m.setUnitPrice(req.getUnitPrice() != null ? req.getUnitPrice() : BigDecimal.ZERO);
        m.setMinimumThreshold(req.getMinimumThreshold() != null ? req.getMinimumThreshold() : BigDecimal.ZERO);
        try {
            m.setChargeType(Material.ChargeType.valueOf(req.getChargeType()));
        } catch (Exception ignored) {}
        updateStockStatus(m);
        return materialRepo.save(m);
    }

    @Transactional(readOnly = true)
    public Material getMaterialById(Long id) { return findMaterialOrThrow(id); }

    @Transactional(readOnly = true)
    public List<Material> getByBranch(Long branchId) {
        return materialRepo.findByBranchIdAndIsActiveTrue(branchId);
    }

    @Transactional(readOnly = true)
    public List<Material> searchMaterials(String keyword) {
        return materialRepo.searchByName(keyword);
    }

    @Transactional
    public Material deactivateMaterial(Long id) {
        Material m = findMaterialOrThrow(id);
        m.setIsActive(false);
        return materialRepo.save(m);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STOCK OPERATIONS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * KEY METHOD — Called by JobService when a Technician logs material usage.
     * Deducts stock and writes a STOCK_OUT transaction automatically.
     *
     * @param jobId      the job this material was used for
     * @param materialId the material being deducted
     * @param qty        how much to deduct
     * @param userId     who triggered this (the Technician)
     */
    @Transactional
    public void deductStockForJob(Long jobId, Long materialId, BigDecimal qty, Long userId) {
        Material material = findMaterialOrThrow(materialId);

        if (material.getCurrentStock().compareTo(qty) < 0) {
            throw new RuntimeException(
                "Insufficient stock for '" + material.getName() +
                "'. Available: " + material.getCurrentStock() + " " + material.getUnit() +
                ", Requested: " + qty + " " + material.getUnit());
        }

        BigDecimal before = material.getCurrentStock();
        BigDecimal after  = before.subtract(qty);

        material.setCurrentStock(after);
        updateStockStatus(material);
        materialRepo.save(material);

        // Log the stock deduction
        logTransaction(materialId, material.getName(),
                StockTransaction.TransactionType.STOCK_OUT,
                qty, before, after,
                StockTransaction.ReferenceType.JOB, jobId,
                "Stock deducted for Job #" + jobId, userId);
    }

    /**
     * Manual stock adjustment — Admin restocks or corrects inventory.
     * POST /api/inventory/materials/{id}/stock
     */
    @Transactional
    public Material adjustStock(Long materialId, StockAdjustRequest req, Long userId) {
        Material material = findMaterialOrThrow(materialId);

        StockTransaction.TransactionType type;
        try {
            type = StockTransaction.TransactionType.valueOf(req.getTransactionType());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid transaction type: " + req.getTransactionType() +
                ". Valid: STOCK_IN, STOCK_OUT, ADJUSTMENT");
        }

        BigDecimal before = material.getCurrentStock();
        BigDecimal after;

        if (type == StockTransaction.TransactionType.STOCK_IN) {
            after = before.add(req.getQuantity());
        } else if (type == StockTransaction.TransactionType.STOCK_OUT) {
            if (before.compareTo(req.getQuantity()) < 0) {
                throw new RuntimeException("Insufficient stock. Available: " + before);
            }
            after = before.subtract(req.getQuantity());
        } else {
            // ADJUSTMENT — set directly
            after = req.getQuantity();
        }

        material.setCurrentStock(after);
        updateStockStatus(material);
        Material saved = materialRepo.save(material);

        logTransaction(materialId, material.getName(), type,
                req.getQuantity(), before, after,
                StockTransaction.ReferenceType.MANUAL_ADJUSTMENT, null,
                req.getNotes(), userId);

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MATERIAL REQUEST WORKFLOW
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Team Lead submits a stock request.
     * POST /api/inventory/requests
     */
    @Transactional
    public MaterialRequest submitRequest(SubmitMaterialRequestDTO req,
                                         Long userId, String userName) {
        Material material = findMaterialOrThrow(req.getMaterialId());

        MaterialRequest request = new MaterialRequest();
        request.setMaterialId(req.getMaterialId());
        request.setMaterialName(material.getName());
        request.setBranchId(req.getBranchId());
        request.setRequestedBy(userId);
        request.setRequestedByName(userName);
        request.setQuantityRequested(req.getQuantityRequested());
        request.setReason(req.getReason());
        request.setJobId(req.getJobId());
        request.setStatus(MaterialRequest.RequestStatus.PENDING);

        return requestRepo.save(request);
    }

    /**
     * Admin reviews a material request — APPROVED or REJECTED.
     * PATCH /api/inventory/requests/{id}/review
     *
     * On APPROVAL: stock is automatically deducted.
     */
    @Transactional
    public MaterialRequest reviewRequest(Long requestId, ReviewMaterialRequestDTO req,
                                          Long adminId, String adminName) {
        MaterialRequest request = requestRepo.findById(requestId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Material request not found: " + requestId));

        if (request.getStatus() != MaterialRequest.RequestStatus.PENDING) {
            throw new RuntimeException(
                "Request already reviewed. Current status: " + request.getStatus());
        }

        if ("REJECTED".equalsIgnoreCase(req.getDecision())) {
            if (req.getRejectionReason() == null || req.getRejectionReason().isBlank()) {
                throw new RuntimeException("A rejection reason is required.");
            }
            request.setStatus(MaterialRequest.RequestStatus.REJECTED);
            request.setRejectionReason(req.getRejectionReason());

        } else if ("APPROVED".equalsIgnoreCase(req.getDecision())) {
            // Auto-deduct stock on approval
            deductStockForJob(
                request.getJobId() != null ? request.getJobId() : requestId,
                request.getMaterialId(),
                request.getQuantityRequested(),
                adminId
            );
            request.setStatus(MaterialRequest.RequestStatus.FULFILLED);

        } else {
            throw new RuntimeException("Invalid decision: " + req.getDecision() +
                ". Valid: APPROVED or REJECTED");
        }

        request.setReviewedBy(adminId);
        request.setReviewedByName(adminName);
        request.setReviewedAt(LocalDateTime.now());

        return requestRepo.save(request);
    }

    @Transactional(readOnly = true)
    public List<MaterialRequest> getPendingRequests() {
        return requestRepo.findByStatusOrderByCreatedAtAsc(MaterialRequest.RequestStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<MaterialRequest> getRequestsByBranch(Long branchId) {
        return requestRepo.findByBranchIdOrderByCreatedAtDesc(branchId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LOW STOCK ALERTS — FR-39
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Material> getLowStockAlerts() {
        return materialRepo.findLowStockMaterials();
    }

    @Transactional(readOnly = true)
    public List<Material> getLowStockByBranch(Long branchId) {
        return materialRepo.findLowStockByBranch(branchId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STOCK HISTORY
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<StockTransaction> getStockHistory(Long materialId) {
        findMaterialOrThrow(materialId);
        return txRepo.findByMaterialIdOrderByCreatedAtDesc(materialId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CATEGORIES
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<MaterialCategory> getAllCategories() {
        return categoryRepo.findByIsActiveTrue();
    }

    @Transactional
    public MaterialCategory createCategory(String name, String description, Long parentId) {
        MaterialCategory cat = new MaterialCategory();
        cat.setName(name);
        cat.setDescription(description);
        cat.setParentId(parentId);
        return categoryRepo.save(cat);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Material findMaterialOrThrow(Long id) {
        return materialRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + id));
    }

    /**
     * Automatically set stock status based on current vs threshold.
     * Called after every stock change.
     */
    private void updateStockStatus(Material m) {
        if (m.getCurrentStock().compareTo(BigDecimal.ZERO) == 0) {
            m.setStockStatus(Material.StockStatus.OUT_OF_STOCK);
        } else if (m.getCurrentStock().compareTo(m.getMinimumThreshold()) <= 0) {
            m.setStockStatus(Material.StockStatus.LOW_STOCK);
        } else {
            m.setStockStatus(Material.StockStatus.IN_STOCK);
        }
    }

    /** Write one row to stock_transactions — called after every stock change */
    private void logTransaction(Long materialId, String materialName,
                                 StockTransaction.TransactionType type,
                                 BigDecimal qty, BigDecimal before, BigDecimal after,
                                 StockTransaction.ReferenceType refType, Long refId,
                                 String notes, Long performedBy) {
        StockTransaction tx = new StockTransaction();
        tx.setMaterialId(materialId);
        tx.setMaterialName(materialName);
        tx.setTransactionType(type);
        tx.setQuantity(qty);
        tx.setStockBefore(before);
        tx.setStockAfter(after);
        tx.setReferenceType(refType);
        tx.setReferenceId(refId);
        tx.setNotes(notes);
        tx.setPerformedBy(performedBy);
        txRepo.save(tx);
    }
}
