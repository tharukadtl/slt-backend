package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.StockDTO;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.*;
import lk.slt.fieldops.websocket
        .WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation
        .Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockManagementService {

    private final MaterialRepository
            materialRepository;
    private final StockTransactionRepository
            stockTransactionRepository;
    private final UserRepository
            userRepository;
    private final WebSocketEventPublisher
            webSocketEventPublisher;

    // ─── Adjust Stock ─────────────────────────────────────

    @Transactional
    public StockDTO.AdjustResponse
    adjustStock(
            StockDTO.AdjustRequest req,
            Long adminId) {

        log.info(
                "Adjusting stock for "
                        + "materialId={}, "
                        + "change={}",
                req.getMaterialId(),
                req.getQuantityChange());

        Material material =
                materialRepository
                        .findById(req.getMaterialId())
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Material not found: "
                                                + req.getMaterialId()));

        User admin = userRepository
                .findById(adminId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Admin not found: "
                                        + adminId));

        int previousStock =
                material.getCurrentStock() != null
                        ? material.getCurrentStock()
                        .intValue()
                        : 0;
        int newStock = previousStock
                + req.getQuantityChange();

        // Prevent negative stock
        if (newStock < 0) {
            throw new RuntimeException(
                    "Insufficient stock. "
                            + "Current: " + previousStock
                            + ", Requested change: "
                            + req.getQuantityChange());
        }

        // Update material stock
        material.setCurrentStock(
                java.math.BigDecimal.valueOf(newStock));

        // Update last restocked if positive
        if (req.getQuantityChange() > 0) {
            material.setLastRestocked(
                    LocalDateTime.now());
        }

        materialRepository.save(material);

        java.math.BigDecimal unitCostBD =
                req.getUnitCost() != null
                        ? java.math.BigDecimal.valueOf(
                        req.getUnitCost())
                        : material.getUnitPrice();
        java.math.BigDecimal qtyBD =
                java.math.BigDecimal.valueOf(
                        Math.abs(req.getQuantityChange()));
        java.math.BigDecimal totalCostBD =
                unitCostBD != null
                        ? unitCostBD.multiply(qtyBD)
                        : java.math.BigDecimal.ZERO;

        // Save stock transaction
        StockTransaction.TransactionType txType;
        try {
            txType = StockTransaction.TransactionType
                    .valueOf(req.getTransactionType());
        } catch (Exception e) {
            txType = req.getQuantityChange() >= 0
                    ? StockTransaction.TransactionType.STOCK_IN
                    : StockTransaction.TransactionType.STOCK_OUT;
        }

        StockTransaction transaction =
                StockTransaction.builder()
                        .materialId(material.getId())
                        .materialName(material.getName())
                        .materialSku(material.getSku())
                        .materialUnit(material.getUnit())
                        .performedBy(admin.getId())
                        .performedByName(
                                admin.getFullName())
                        .performedByRole(
                                admin.getRole() != null
                                        ? admin.getRole()
                                        .name() : null)
                        .transactionType(txType)
                        .quantity(qtyBD)
                        .stockBefore(java.math.BigDecimal
                                .valueOf(previousStock))
                        .stockAfter(java.math.BigDecimal
                                .valueOf(newStock))
                        .reason(req.getReason())
                        .reference(req.getReference())
                        .notes(req.getNotes())
                        .unitCost(unitCostBD)
                        .totalCost(totalCostBD)
                        .build();

        StockTransaction saved =
                stockTransactionRepository
                        .save(transaction);

        int minThr = material.getMinimumThreshold() != null
                ? material.getMinimumThreshold().intValue()
                : 10;
        boolean lowStockAlert = newStock <= minThr;

        // Send low stock alert
        if (lowStockAlert) {
            String alertMsg =
                    newStock <= 0
                            ? "⚠️ OUT OF STOCK: "
                            + material.getName()
                            + " is out of stock!"
                            : "⚠️ LOW STOCK: "
                            + material.getName()
                            + " has only "
                            + newStock
                            + " "
                            + material.getUnit()
                            + " remaining";

            webSocketEventPublisher.sendToRole(
                    "admin",
                    newStock <= 0
                            ? "Out of Stock Alert"
                            : "Low Stock Alert",
                    alertMsg,
                    "STOCK_ALERT");

            log.warn(alertMsg);
        }

        String stockStatus =
                getStockStatus(newStock, minThr);

        log.info(
                "Stock adjusted: {} {} → {} "
                        + "(change: {})",
                material.getName(),
                previousStock,
                newStock,
                req.getQuantityChange());

        return StockDTO.AdjustResponse.builder()
                .transactionId(saved.getId())
                .materialId(material.getId())
                .materialName(material.getName())
                .sku(material.getSku())
                .previousStock(previousStock)
                .quantityChange(
                        req.getQuantityChange())
                .newStock(newStock)
                .transactionType(
                        req.getTransactionType())
                .reason(req.getReason())
                .reference(req.getReference())
                .performedBy(admin.getFullName())
                .timestamp(saved.getCreatedAt())
                .stockStatus(stockStatus)
                .lowStockAlert(lowStockAlert)
                .message("Stock adjusted from "
                        + previousStock
                        + " to "
                        + newStock
                        + " "
                        + material.getUnit())
                .build();
    }

    // ─── Bulk Adjust Stock ────────────────────────────────

    @Transactional
    public StockDTO.BulkAdjustResponse
    bulkAdjustStock(
            StockDTO.BulkAdjustRequest req,
            Long adminId) {

        log.info(
                "Bulk adjusting {} materials",
                req.getAdjustments().size());

        List<StockDTO.AdjustResponse>
                successes = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (StockDTO.AdjustRequest adjustment
                : req.getAdjustments()) {
            try {
                StockDTO.AdjustResponse response =
                        adjustStock(
                                adjustment, adminId);
                successes.add(response);
            } catch (Exception e) {
                String error = "Material "
                        + adjustment.getMaterialId()
                        + ": " + e.getMessage();
                errors.add(error);
                log.error(
                        "Bulk adjust error: {}",
                        error);
            }
        }

        return StockDTO.BulkAdjustResponse
                .builder()
                .totalRequested(
                        req.getAdjustments().size())
                .successCount(successes.size())
                .failureCount(errors.size())
                .successfulAdjustments(successes)
                .errors(errors)
                .processedAt(LocalDateTime.now())
                .build();
    }

    // ─── Get Low Stock Alerts ─────────────────────────────

    public StockDTO.LowStockSummaryDTO
    getLowStockAlerts() {
        log.info("Getting low stock alerts");

        List<Material> lowStockMaterials =
                materialRepository.findLowStock();

        List<StockDTO.LowStockAlertDTO> alerts =
                new ArrayList<>();
        double totalReorderCost = 0.0;
        long outOfStockCount = 0;
        long criticalCount = 0;
        long warningCount = 0;

        for (Material material
                : lowStockMaterials) {
            int current =
                    material.getCurrentStock()
                            != null
                            ? material.getCurrentStock()
                            .intValue()
                            : 0;
            int minThreshold =
                    material.getMinimumThreshold() != null
                            ? material.getMinimumThreshold()
                            .intValue()
                            : 10;
            int reorderQty =
                    material.getReorderQuantity()
                            != null
                            ? material.getReorderQuantity()
                            : 50;

            String alertLevel;
            String alertColor;
            String alertIcon;
            String message;

            if (current <= 0) {
                alertLevel = "OUT_OF_STOCK";
                alertColor = "#F44336";
                alertIcon = "🚫";
                message = material.getName()
                        + " is completely "
                        + "out of stock!";
                outOfStockCount++;
            } else if (current
                    <= minThreshold / 2) {
                alertLevel = "CRITICAL";
                alertColor = "#FF5722";
                alertIcon = "🔴";
                message = "Only " + current
                        + " " + material.getUnit()
                        + " remaining — "
                        + "critically low!";
                criticalCount++;
            } else {
                alertLevel = "WARNING";
                alertColor = "#FF9800";
                alertIcon = "⚠️";
                message = "Stock below minimum "
                        + "threshold ("
                        + current + "/"
                        + minThreshold + " "
                        + material.getUnit() + ")";
                warningCount++;
            }

            double reorderCost =
                    (material.getUnitPrice() != null
                            ? material.getUnitPrice()
                            .doubleValue()
                            : 0) * reorderQty;
            totalReorderCost += reorderCost;

            alerts.add(
                    StockDTO.LowStockAlertDTO
                            .builder()
                            .materialId(material.getId())
                            .materialName(
                                    material.getName())
                            .sku(material.getSku())
                            .category("General")
                            .unit(material.getUnit())
                            .currentStock(current)
                            .minThreshold(minThreshold)
                            .reorderQuantity(reorderQty)
                            .unitPrice(
                                    material.getUnitPrice()
                                            != null
                                            ? material.getUnitPrice()
                                            .doubleValue()
                                            : null)
                            .reorderCost(reorderCost)
                            .alertLevel(alertLevel)
                            .alertColor(alertColor)
                            .alertIcon(alertIcon)
                            .lastRestocked(
                                    material.getLastRestocked())
                            .message(message)
                            .build());
        }

        // Sort: OUT_OF_STOCK first, then CRITICAL,
        // then WARNING
        alerts.sort((a, b) -> {
            int priorityA =
                    getAlertPriority(
                            a.getAlertLevel());
            int priorityB =
                    getAlertPriority(
                            b.getAlertLevel());
            return Integer.compare(
                    priorityA, priorityB);
        });

        return StockDTO.LowStockSummaryDTO
                .builder()
                .totalAlerts(alerts.size())
                .outOfStockCount((int) outOfStockCount)
                .criticalCount((int) criticalCount)
                .warningCount((int) warningCount)
                .totalReorderCost(
                        Math.round(
                                totalReorderCost * 100.0)
                                / 100.0)
                .alerts(alerts)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ─── Get Transactions by Material ─────────────────────

    public StockDTO.TransactionHistoryDTO
    getTransactionsByMaterial(Long materialId) {
        log.debug(
                "Getting transactions for "
                        + "materialId={}",
                materialId);

        Material material =
                materialRepository
                        .findById(materialId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Material not found: "
                                                + materialId));

        List<StockTransaction> transactions =
                stockTransactionRepository
                        .findByMaterialId(materialId);

        List<StockDTO.TransactionResponse>
                responses = transactions.stream()
                .map(t -> mapTransaction(t))
                .collect(Collectors.toList());

        // Calculate totals
        int totalIn = transactions.stream()
                .filter(t ->
                        t.getQuantity() != null
                                && t.getQuantity().compareTo(
                                java.math.BigDecimal.ZERO) > 0)
                .mapToInt(t -> t.getQuantity().intValue())
                .sum();

        int totalOut = Math.abs(
                transactions.stream()
                        .filter(t ->
                                t.getQuantity() != null
                                        && t.getQuantity()
                                        .compareTo(
                                        java.math.BigDecimal.ZERO)
                                        < 0)
                        .mapToInt(t ->
                                t.getQuantity().intValue())
                        .sum());

        double totalValue = transactions.stream()
                .filter(t -> t.getTotalCost() != null)
                .mapToDouble(t ->
                        t.getTotalCost().doubleValue())
                .sum();

        LocalDateTime lastTransaction =
                transactions.isEmpty()
                        ? null
                        : transactions.get(0)
                        .getCreatedAt();

        return StockDTO.TransactionHistoryDTO
                .builder()
                .materialId(materialId)
                .materialName(material.getName())
                .sku(material.getSku())
                .currentStock(
                        material.getCurrentStock() != null
                                ? material.getCurrentStock()
                                .intValue() : 0)
                .unit(material.getUnit())
                .totalTransactions(
                        transactions.size())
                .totalIn(totalIn)
                .totalOut(totalOut)
                .totalValue(
                        Math.round(
                                totalValue * 100.0)
                                / 100.0)
                .lastTransaction(lastTransaction)
                .transactions(responses)
                .build();
    }

    // ─── Get All Stock Levels ─────────────────────────────

    public List<StockDTO.StockLevelDTO>
    getAllStockLevels() {
        log.debug("Getting all stock levels");

        return materialRepository
                .findAllActive()
                .stream()
                .map(this::mapToStockLevel)
                .sorted((a, b) ->
                        Integer.compare(
                                a.getCurrentStock(),
                                b.getCurrentStock()))
                .collect(Collectors.toList());
    }

    // ─── Private Helpers ──────────────────────────────────

    private StockDTO.StockLevelDTO
    mapToStockLevel(Material material) {
        int current =
                material.getCurrentStock() != null
                        ? material.getCurrentStock()
                        .intValue()
                        : 0;
        int minThreshold =
                material.getMinimumThreshold() != null
                        ? material.getMinimumThreshold()
                        .intValue()
                        : 10;
        int maxThreshold =
                material.getMaxThreshold() != null
                        ? material.getMaxThreshold()
                        : 500;

        String stockStatus =
                getStockStatus(current, minThreshold);
        String statusColor =
                getStatusColor(stockStatus);
        String statusIcon =
                getStatusIcon(stockStatus);

        double percentage = maxThreshold > 0
                ? Math.min(
                (current * 100.0 / maxThreshold),
                100)
                : 0;

        double totalValue =
                (material.getUnitPrice() != null
                        ? material.getUnitPrice()
                        .doubleValue()
                        : 0) * current;

        return StockDTO.StockLevelDTO.builder()
                .materialId(material.getId())
                .materialName(material.getName())
                .sku(material.getSku())
                .category("General")
                .unit(material.getUnit())
                .currentStock(current)
                .minThreshold(minThreshold)
                .maxThreshold(maxThreshold)
                .unitPrice(material.getUnitPrice() != null
                        ? material.getUnitPrice().doubleValue()
                        : null)
                .totalValue(
                        Math.round(
                                totalValue * 100.0)
                                / 100.0)
                .isFOC(material.getChargeType()
                        == Material.ChargeType.FOC)
                .stockStatus(stockStatus)
                .stockStatusColor(statusColor)
                .stockStatusIcon(statusIcon)
                .stockPercentage(
                        Math.round(
                                percentage * 10.0) / 10.0)
                .reorderQuantity(
                        material.getReorderQuantity())
                .lastUpdated(material.getUpdatedAt())
                .lastRestocked(
                        material.getLastRestocked())
                .build();
    }

    private StockDTO.TransactionResponse
    mapTransaction(StockTransaction t) {
        String txTypeName = t.getTransactionType() != null
                ? t.getTransactionType().name() : null;
        String typeLabel =
                getTransactionTypeLabel(txTypeName);
        String typeIcon =
                getTransactionIcon(txTypeName);
        String typeColor =
                getTransactionColor(txTypeName);

        return StockDTO.TransactionResponse
                .builder()
                .id(t.getId())
                .materialId(t.getMaterialId())
                .materialName(t.getMaterialName())
                .sku(t.getMaterialSku())
                .unit(t.getMaterialUnit())
                .quantityChange(t.getQuantity() != null
                        ? t.getQuantity().intValue() : 0)
                .stockBefore(t.getStockBefore() != null
                        ? t.getStockBefore().intValue() : 0)
                .stockAfter(t.getStockAfter() != null
                        ? t.getStockAfter().intValue() : 0)
                .transactionType(txTypeName)
                .transactionTypeLabel(typeLabel)
                .transactionIcon(typeIcon)
                .transactionColor(typeColor)
                .reason(t.getReason())
                .reference(t.getReference())
                .notes(t.getNotes())
                .performedById(t.getPerformedBy())
                .performedByName(
                        t.getPerformedByName() != null
                                ? t.getPerformedByName()
                                : "System")
                .performedByRole(t.getPerformedByRole())
                .unitCost(t.getUnitCost() != null
                        ? t.getUnitCost().doubleValue()
                        : null)
                .totalCost(t.getTotalCost() != null
                        ? t.getTotalCost().doubleValue()
                        : null)
                .createdAt(t.getCreatedAt())
                .timeAgo(getTimeAgo(t.getCreatedAt()))
                .build();
    }

    private String getStockStatus(
            int current, Integer minThreshold) {
        int threshold = minThreshold != null
                ? minThreshold : 10;
        if (current <= 0) return "OUT_OF_STOCK";
        if (current <= threshold / 2)
            return "CRITICAL";
        if (current <= threshold)
            return "LOW_STOCK";
        return "IN_STOCK";
    }

    private String getStatusColor(
            String status) {
        switch (status) {
            case "IN_STOCK": return "#4CAF50";
            case "LOW_STOCK": return "#FF9800";
            case "CRITICAL": return "#FF5722";
            case "OUT_OF_STOCK": return "#F44336";
            default: return "#9E9E9E";
        }
    }

    private String getStatusIcon(
            String status) {
        switch (status) {
            case "IN_STOCK": return "✅";
            case "LOW_STOCK": return "⚠️";
            case "CRITICAL": return "🔴";
            case "OUT_OF_STOCK": return "🚫";
            default: return "📦";
        }
    }

    private int getAlertPriority(
            String alertLevel) {
        switch (alertLevel) {
            case "OUT_OF_STOCK": return 0;
            case "CRITICAL": return 1;
            case "WARNING": return 2;
            default: return 3;
        }
    }

    private String getTransactionTypeLabel(
            String type) {
        if (type == null) return "Unknown";
        switch (type) {
            case "MANUAL_ADJUSTMENT":
                return "Manual Adjustment";
            case "RESTOCK":
                return "Restocked";
            case "USAGE":
                return "Used in Job";
            case "DAMAGE":
                return "Damaged/Lost";
            case "RETURN":
                return "Returned";
            case "MATERIAL_REQUEST_APPROVED":
                return "Request Approved";
            case "INITIAL_STOCK":
                return "Initial Stock";
            default:
                return type.replace("_", " ");
        }
    }

    private String getTransactionIcon(
            String type) {
        if (type == null) return "📦";
        switch (type) {
            case "RESTOCK":
            case "INITIAL_STOCK":
            case "RETURN":
                return "📥";
            case "USAGE":
            case "MATERIAL_REQUEST_APPROVED":
                return "📤";
            case "DAMAGE":
                return "❌";
            case "MANUAL_ADJUSTMENT":
                return "✏️";
            default:
                return "📦";
        }
    }

    private String getTransactionColor(
            String type) {
        if (type == null) return "#9E9E9E";
        switch (type) {
            case "RESTOCK":
            case "INITIAL_STOCK":
            case "RETURN":
                return "#4CAF50";
            case "USAGE":
            case "MATERIAL_REQUEST_APPROVED":
                return "#2196F3";
            case "DAMAGE":
                return "#F44336";
            case "MANUAL_ADJUSTMENT":
                return "#FF9800";
            default:
                return "#9E9E9E";
        }
    }

    private String getTimeAgo(
            LocalDateTime dateTime) {
        if (dateTime == null) return "Unknown";
        long seconds = ChronoUnit.SECONDS.between(
                dateTime, LocalDateTime.now());
        if (seconds < 60)
            return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60)
            return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24)
            return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }

    // ─── Material CRUD ────────────────────────────────────

    @Transactional
    public Material createMaterial(java.util.Map<String, Object> body) {
        Material m = new Material();
        applyMaterialBody(m, body);
        if (m.getCurrentStock() != null && m.getCurrentStock().compareTo(java.math.BigDecimal.ZERO) > 0) {
            m.setStockStatus(Material.StockStatus.IN_STOCK);
        }
        return materialRepository.save(m);
    }

    @Transactional
    public Material updateMaterial(Long id, java.util.Map<String, Object> body) {
        Material m = materialRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Material not found: " + id));
        applyMaterialBody(m, body);
        return materialRepository.save(m);
    }

    private void applyMaterialBody(Material m, java.util.Map<String, Object> body) {
        if (body.get("name") instanceof String s)   m.setName(s);
        if (body.get("sku")  instanceof String s && !s.isBlank()) m.setSku(s);
        if (body.get("unit") instanceof String s)   m.setUnit(s);

        Object up = body.get("unitPrice");
        if (up != null) m.setUnitPrice(new java.math.BigDecimal(up.toString()));

        Object sq = body.get("stockQuantity");
        if (sq != null) m.setCurrentStock(new java.math.BigDecimal(sq.toString()));

        Object mt = body.get("minThreshold");
        if (mt != null) m.setMinimumThreshold(new java.math.BigDecimal(mt.toString()));

        Object mxt = body.get("maxThreshold");
        if (mxt != null) m.setMaxThreshold(Integer.valueOf(mxt.toString()));

        Object rq = body.get("reorderQuantity");
        if (rq != null) m.setReorderQuantity(Integer.valueOf(rq.toString()));

        Object isFoc = body.get("isFoc");
        if (isFoc instanceof Boolean b) m.setChargeType(b ? Material.ChargeType.FOC : Material.ChargeType.CHARGEABLE);

        Object active = body.get("isActive");
        if (active instanceof Boolean b) m.setIsActive(b);
    }
}