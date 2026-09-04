package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.MaterialRequestDTO;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.*;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialRequestService {

    private final MaterialRequestRepository materialRequestRepository;
    private final MaterialRepository        materialRepository;
    private final MaterialCategoryRepository materialCategoryRepository;
    private final UserRepository            userRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final WebSocketEventPublisher   webSocketEventPublisher;
    private final WorkGroupAllocationRepository workGroupAllocationRepository;
    private final NotificationService       notificationService;

    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) return "Uncategorized";
        return materialCategoryRepository.findById(categoryId)
                .map(MaterialCategory::getName)
                .orElse("Uncategorized");
    }

    // ─── Submit Request ───────────────────────────────────

    @Transactional
    public MaterialRequestDTO.RequestResponse submitRequest(
            Long requesterId,
            MaterialRequestDTO.SubmitRequest req) {

        log.info("Submitting material request by userId={}", requesterId);

        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new RuntimeException("User not found: " + requesterId));

        List<MaterialRequestDTO.MaterialItemResponse> items = new ArrayList<>();
        double totalCost = 0.0;
        StringBuilder itemsData = new StringBuilder();

        for (MaterialRequestDTO.RequestItemDTO item : req.getItems()) {

            Material material = materialRepository.findById(item.getMaterialId())
                    .orElseThrow(() -> new RuntimeException("Material not found: " + item.getMaterialId()));

            int available = material.getCurrentStock() != null
                    ? material.getCurrentStock().intValue() : 0;
            double unitPrice = material.getUnitPrice() != null
                    ? material.getUnitPrice().doubleValue() : 0.0;
            double subtotal = unitPrice * item.getQuantity();
            totalCost += subtotal;

            // Use the same IN_STOCK/LOW_STOCK/OUT_OF_STOCK vocabulary as the rest
            // of the system (Material.stockStatus), rather than a one-off label.
            String stockStatus = material.getStockStatus() != null
                    ? material.getStockStatus().name()
                    : Material.computeStockStatus(material.getCurrentStock(), material.getMinimumThreshold()).name();

            items.add(MaterialRequestDTO.MaterialItemResponse.builder()
                    .materialId(material.getId())
                    .materialName(material.getName())
                    .sku(material.getSku())
                    .category(resolveCategoryName(material.getCategoryId()))
                    .requestedQuantity(item.getQuantity())
                    .unit(material.getUnit())
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .isFOC(material.getChargeType() == Material.ChargeType.FOC)
                    .availableStock(available)
                    .stockStatus(stockStatus)
                    .build());

            if (itemsData.length() > 0) itemsData.append("|");
            itemsData.append(material.getId()).append(":").append(item.getQuantity()).append(":0");
        }

        MaterialRequest request = MaterialRequest.builder()
                .requestedBy(requester.getId())
                .requestedByName(requester.getFullName())
                .opmcId(requester.getOpmcId())
                .workGroupId(requester.getWorkgroup() != null ? requester.getWorkgroup().getId() : null)
                .taskId(req.getTaskId())
                .faultId(req.getFaultId())
                .status(MaterialRequest.RequestStatus.PENDING)
                .urgency(req.getUrgency() != null ? req.getUrgency() : "NORMAL")
                .requesterNotes(req.getNotes())
                .totalEstimatedCost(totalCost)
                .totalApprovedCost(0.0)
                .itemsData(itemsData.toString())
                .build();

        MaterialRequest saved = materialRequestRepository.save(request);

        webSocketEventPublisher.sendToRole("admin",
                "New Material Request",
                requester.getFullName() + " submitted request "
                        + saved.getRequestNumber() + " — " + items.size() + " items",
                "MATERIAL_REQUEST");

        // QA_Compliance_Consolidated_Report.md — Stage G FCM Major: the sendToRole broadcast
        // above is WebSocket-only. Mirrors FaultService.notifyAdminsOfNewFault's
        // loop-over-admins shape for the same "broadcast to whichever admins are on shift"
        // requirement.
        List<User> admins = new ArrayList<>();
        admins.addAll(userRepository.findByRoleAndIsActiveTrue(User.Role.ADMIN));
        admins.addAll(userRepository.findByRoleAndIsActiveTrue(User.Role.SUPER_ADMIN));
        admins.forEach(admin -> notificationService.notifyMaterialRequestSubmitted(
                admin.getId(), admin.getFcmToken(), saved.getRequestNumber(), saved.getId()));

        log.info("Material request {} submitted", saved.getRequestNumber());
        return buildResponse(saved, items);
    }

    // ─── Get Pending Requests ─────────────────────────────

    public MaterialRequestDTO.PendingSummaryDTO getPendingRequests() {
        log.info("Getting pending material requests");
        return buildPendingSummary(materialRequestRepository
                .findByStatusOrderByCreatedAtAsc(MaterialRequest.RequestStatus.PENDING));
    }

    /**
     * SRS 5.5.3 (v1.9) — a Team Lead's own Work Group's pending queue, for the
     * mobile distribution UI. {@code workGroupId} must be derived server-side
     * from the caller's own record (see InventoryController), never accepted as
     * a client-supplied parameter.
     */
    public MaterialRequestDTO.PendingSummaryDTO getPendingRequestsForWorkGroup(Long workGroupId) {
        log.info("Getting pending material requests for work group {}", workGroupId);
        return buildPendingSummary(materialRequestRepository
                .findByStatusAndWorkGroupIdOrderByCreatedAtAsc(
                        MaterialRequest.RequestStatus.PENDING, workGroupId));
    }

    private MaterialRequestDTO.PendingSummaryDTO buildPendingSummary(List<MaterialRequest> pending) {
        // Urgent requests first; within the same urgency, oldest first (FIFO).
        pending.sort(Comparator
                .comparing((MaterialRequest r) -> "URGENT".equals(r.getUrgency()) ? 0 : 1)
                .thenComparing(MaterialRequest::getCreatedAt));

        List<MaterialRequestDTO.RequestResponse> responses = pending.stream()
                .map(this::buildResponseFromEntity)
                .collect(Collectors.toList());

        long urgentCount = pending.stream()
                .filter(r -> "URGENT".equals(r.getUrgency())).count();

        double totalValue = pending.stream()
                .mapToDouble(r -> r.getTotalEstimatedCost() != null ? r.getTotalEstimatedCost() : 0)
                .sum();

        return MaterialRequestDTO.PendingSummaryDTO.builder()
                .totalPending(pending.size())
                .urgentCount((int) urgentCount)
                .normalCount(pending.size() - (int) urgentCount)
                .totalEstimatedValue(totalValue)
                .requests(responses)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ─── Approve Request ──────────────────────────────────

    @Transactional
    public MaterialRequestDTO.RequestResponse approveRequest(
            Long requestId,
            MaterialRequestDTO.ApproveRequest req,
            Long adminId) {

        log.info("Approving material request {}", requestId);

        MaterialRequest request = materialRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found: " + requestId));

        if (request.getStatus() != MaterialRequest.RequestStatus.PENDING) {
            throw new RuntimeException("Request is already "
                    + request.getStatus().name().toLowerCase());
        }

        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new RuntimeException("Admin not found: " + adminId));

        // SRS 5.5.3 (v1.9) — a Team Lead may approve/distribute only their own
        // Work Group's requests, drawing from that Work Group's allocation
        // rather than the OPMC pool directly. Admin/SuperAdmin are unrestricted,
        // same as before Stage D.
        if (admin.getRole() == User.Role.TEAM_LEAD) {
            Long callerWorkGroupId = admin.getWorkgroup() != null ? admin.getWorkgroup().getId() : null;
            if (callerWorkGroupId == null || !callerWorkGroupId.equals(request.getWorkGroupId())) {
                throw new AccessDeniedException(
                        "You may only approve material requests from your own Work Group.");
            }
        }

        double approvedCost = 0.0;
        List<MaterialRequestDTO.MaterialItemResponse> items = parseItemsFromData(request.getItemsData());

        if (req.getApprovedItems() != null && !req.getApprovedItems().isEmpty()) {
            for (MaterialRequestDTO.ApprovedItemDTO approvedItem : req.getApprovedItems()) {
                Optional<Material> matOpt = materialRepository.findById(approvedItem.getMaterialId());
                if (matOpt.isEmpty()) continue;
                Material material = matOpt.get();
                int deduct = approvedItem.getApprovedQuantity();

                double itemCost = deductForApproval(request, material, deduct, admin);
                approvedCost += itemCost;

                items.stream()
                        .filter(i -> i.getMaterialId().equals(material.getId()))
                        .findFirst()
                        .ifPresent(i -> {
                            i.setApprovedQuantity(deduct);
                            i.setSubtotal(itemCost);
                        });
            }
        } else {
            for (MaterialRequestDTO.MaterialItemResponse item : items) {
                Optional<Material> matOpt = materialRepository.findById(item.getMaterialId());
                if (matOpt.isEmpty()) continue;
                Material material = matOpt.get();
                int deduct = item.getRequestedQuantity();

                double itemCost = deductForApproval(request, material, deduct, admin);
                approvedCost += itemCost;
                item.setApprovedQuantity(deduct);
                item.setSubtotal(itemCost);
            }
        }

        request.setStatus(MaterialRequest.RequestStatus.APPROVED);
        request.setReviewedBy(admin.getId());
        request.setReviewedByName(admin.getFullName());
        request.setReviewerNotes(req.getNotes());
        request.setReviewedAt(LocalDateTime.now());
        request.setTotalApprovedCost(approvedCost);

        MaterialRequest saved = materialRequestRepository.save(request);

        if (req.isNotifyRequester() && request.getRequestedBy() != null) {
            webSocketEventPublisher.sendToUser(
                    request.getRequestedBy().toString(),
                    "Material Request Approved",
                    "Your request " + request.getRequestNumber() + " has been approved",
                    "MATERIAL_REQUEST_APPROVED");

            // QA_Compliance_Consolidated_Report.md — RES-015's durable-channel half: the
            // sendToUser above is WebSocket-only. notifyMaterialRequestApproved names the
            // approved materials/quantities so the requester can act on it off-socket too.
            String itemsSummary = items.stream()
                    .filter(i -> i.getApprovedQuantity() != null && i.getApprovedQuantity() > 0)
                    .map(i -> i.getMaterialName() + " x" + i.getApprovedQuantity())
                    .collect(Collectors.joining(", "));
            userRepository.findById(request.getRequestedBy()).ifPresent(requester ->
                    notificationService.notifyMaterialRequestApproved(
                            requester.getId(), requester.getFcmToken(),
                            request.getRequestNumber(), request.getId(), itemsSummary));
        }

        log.info("Material request {} approved by {}", request.getRequestNumber(), admin.getFullName());
        return buildResponse(saved, items);
    }

    // ─── Reject Request ───────────────────────────────────

    @Transactional
    public MaterialRequestDTO.RequestResponse rejectRequest(
            Long requestId,
            MaterialRequestDTO.RejectRequest req,
            Long adminId) {

        log.info("Rejecting material request {}", requestId);

        MaterialRequest request = materialRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found: " + requestId));

        if (request.getStatus() != MaterialRequest.RequestStatus.PENDING) {
            throw new RuntimeException("Request is already "
                    + request.getStatus().name().toLowerCase());
        }

        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new RuntimeException("Admin not found: " + adminId));

        // Same Work Group boundary as approveRequest above — a Team Lead may only
        // reject their own Work Group's requests. Admin/SuperAdmin unrestricted.
        // Added alongside the 2026-08-21 fix that widened this endpoint's role
        // gate to TEAM_LEAD (previously ADMIN/SUPER_ADMIN-only, so this check
        // was never reachable before) — without it, the widened gate alone would
        // let any Team Lead reject any OPMC's request, not just their own.
        if (admin.getRole() == User.Role.TEAM_LEAD) {
            Long callerWorkGroupId = admin.getWorkgroup() != null ? admin.getWorkgroup().getId() : null;
            if (callerWorkGroupId == null || !callerWorkGroupId.equals(request.getWorkGroupId())) {
                throw new AccessDeniedException(
                        "You may only reject material requests from your own Work Group.");
            }
        }

        request.setStatus(MaterialRequest.RequestStatus.REJECTED);
        request.setReviewedBy(admin.getId());
        request.setReviewedByName(admin.getFullName());
        request.setRejectionReason(req.getReason());
        request.setReviewerNotes(req.getNotes());
        request.setReviewedAt(LocalDateTime.now());

        MaterialRequest saved = materialRequestRepository.save(request);

        if (req.isNotifyRequester() && request.getRequestedBy() != null) {
            webSocketEventPublisher.sendToUser(
                    request.getRequestedBy().toString(),
                    "Material Request Rejected",
                    "Your request " + request.getRequestNumber()
                            + " was rejected. Reason: " + req.getReason(),
                    "MATERIAL_REQUEST_REJECTED");

            // QA_Compliance_Consolidated_Report.md — same durable-channel gap as approveRequest
            // above, on the rejection side. Not one of the 8 events this fix was scoped to, but
            // fixed alongside it per explicit instruction, since it is the identical code shape.
            userRepository.findById(request.getRequestedBy()).ifPresent(requester ->
                    notificationService.notifyMaterialRequestRejected(
                            requester.getId(), requester.getFcmToken(),
                            request.getRequestNumber(), request.getId(), req.getReason()));
        }

        log.info("Material request {} rejected by {}", request.getRequestNumber(), admin.getFullName());
        return buildResponseFromEntity(saved);
    }

    // ─── Get History ──────────────────────────────────────

    public MaterialRequestDTO.HistorySummaryDTO getHistory(String status, Long requesterId) {
        log.info("Getting material request history");

        List<MaterialRequest> requests;

        if (requesterId != null) {
            requests = materialRequestRepository.findByRequestedByOrderByCreatedAtDesc(requesterId);
        } else if (status != null && !status.isEmpty() && !"ALL".equals(status)) {
            try {
                MaterialRequest.RequestStatus enumStatus = MaterialRequest.RequestStatus.valueOf(status);
                requests = materialRequestRepository.findByStatusOrderByCreatedAtAsc(enumStatus);
            } catch (IllegalArgumentException e) {
                requests = materialRequestRepository.findAllHistory();
            }
        } else {
            requests = materialRequestRepository.findAllHistory();
        }

        List<MaterialRequestDTO.RequestResponse> responses = requests.stream()
                .map(this::buildResponseFromEntity)
                .collect(Collectors.toList());

        long approvedCount  = requests.stream().filter(r -> r.getStatus() == MaterialRequest.RequestStatus.APPROVED).count();
        long rejectedCount  = requests.stream().filter(r -> r.getStatus() == MaterialRequest.RequestStatus.REJECTED).count();
        long pendingCount   = requests.stream().filter(r -> r.getStatus() == MaterialRequest.RequestStatus.PENDING).count();
        long deliveredCount = requests.stream().filter(r -> r.getStatus() == MaterialRequest.RequestStatus.DELIVERED).count();

        double totalApprovedValue = requests.stream()
                .filter(r -> r.getStatus() == MaterialRequest.RequestStatus.APPROVED)
                .mapToDouble(r -> r.getTotalApprovedCost() != null ? r.getTotalApprovedCost() : 0)
                .sum();

        double approvalRate = !requests.isEmpty()
                ? (approvedCount * 100.0 / requests.size()) : 0;

        return MaterialRequestDTO.HistorySummaryDTO.builder()
                .totalRequests(requests.size())
                .approvedCount((int) approvedCount)
                .rejectedCount((int) rejectedCount)
                .pendingCount((int) pendingCount)
                .deliveredCount((int) deliveredCount)
                .totalApprovedValue(totalApprovedValue)
                .approvalRate(Math.round(approvalRate * 10.0) / 10.0)
                .requests(responses)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ─── Mark as Delivered ────────────────────────────────

    @Transactional
    public MaterialRequestDTO.RequestResponse markDelivered(Long requestId, Long adminId) {
        log.info("Marking request {} as delivered", requestId);

        MaterialRequest request = materialRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found: " + requestId));

        if (request.getStatus() != MaterialRequest.RequestStatus.APPROVED) {
            throw new RuntimeException("Only approved requests can be marked as delivered");
        }

        request.setStatus(MaterialRequest.RequestStatus.DELIVERED);
        request.setDeliveredAt(LocalDateTime.now());

        MaterialRequest saved = materialRequestRepository.save(request);

        if (request.getRequestedBy() != null) {
            webSocketEventPublisher.sendToUser(
                    request.getRequestedBy().toString(),
                    "Materials Delivered",
                    "Your request " + request.getRequestNumber() + " materials have been delivered",
                    "MATERIAL_DELIVERED");
        }

        return buildResponseFromEntity(saved);
    }

    // ─── Private Helpers ──────────────────────────────────

    /**
     * SRS 5.5.3 (v1.9) — draws down from the requester's Work Group allocation
     * when the request has one (Technician/Team Lead requests), falling back to
     * the pre-Stage-D direct-from-OPMC-pool deduction otherwise (a requester
     * with no Work Group, or a legacy request predating this column). Returns
     * the item's cost for the caller to accumulate into approvedCost.
     */
    private double deductForApproval(MaterialRequest request, Material material, int deduct, User admin) {
        double unitPrice = material.getUnitPrice() != null ? material.getUnitPrice().doubleValue() : 0.0;

        if (request.getWorkGroupId() != null) {
            WorkGroupAllocation allocation = workGroupAllocationRepository
                    .findByWorkGroupIdAndMaterialId(request.getWorkGroupId(), material.getId())
                    .orElseThrow(() -> new RuntimeException(
                            "Work Group " + request.getWorkGroupId() + " has no allocation of '"
                                    + material.getName() + "' — cannot approve " + deduct + " " + material.getUnit() + "."));

            BigDecimal available = allocation.getAllocatedQuantity() != null
                    ? allocation.getAllocatedQuantity() : BigDecimal.ZERO;
            if (BigDecimal.valueOf(deduct).compareTo(available) > 0) {
                throw new RuntimeException(
                        "Cannot approve " + deduct + " " + material.getUnit() + " of '" + material.getName()
                                + "' — Work Group only has " + available + " " + material.getUnit() + " allocated.");
            }
            allocation.setAllocatedQuantity(available.subtract(BigDecimal.valueOf(deduct)));
            workGroupAllocationRepository.save(allocation);
            saveStockTransaction(material, -deduct,
                    "MATERIAL_REQUEST_APPROVED_FROM_WORK_GROUP_ALLOCATION",
                    "Request #" + request.getRequestNumber(), admin);
            return unitPrice * deduct;
        }

        int currentStock = material.getCurrentStock() != null
                ? material.getCurrentStock().intValue() : 0;
        if (deduct > currentStock) {
            throw new RuntimeException(
                    "Cannot approve " + deduct + " " + material.getUnit() + " of '" + material.getName()
                            + "' — only " + currentStock + " " + material.getUnit() + " in stock.");
        }
        material.setCurrentStock(BigDecimal.valueOf(currentStock - deduct));
        materialRepository.save(material);
        saveStockTransaction(material, -deduct,
                "MATERIAL_REQUEST_APPROVED",
                "Request #" + request.getRequestNumber(), admin);
        return unitPrice * deduct;
    }

    private void saveStockTransaction(
            Material material, int quantity, String reason, String reference, User performedBy) {
        try {
            StockTransaction tx = new StockTransaction();
            tx.setMaterialId(material.getId());
            tx.setMaterialName(material.getName());
            tx.setMaterialSku(material.getSku());
            tx.setMaterialUnit(material.getUnit());
            tx.setTransactionType(StockTransaction.TransactionType.STOCK_OUT);
            tx.setQuantity(BigDecimal.valueOf(Math.abs(quantity)));
            tx.setReason(reason);
            tx.setReference(reference);
            tx.setPerformedBy(performedBy.getId());
            tx.setPerformedByName(performedBy.getFullName());
            tx.setPerformedByRole(performedBy.getRole() != null ? performedBy.getRole().name() : null);
            tx.setIpAddress(lk.slt.fieldops.shared.RequestContext.getClientIp());
            stockTransactionRepository.save(tx);
        } catch (Exception e) {
            log.error("Error saving stock transaction: {}", e.getMessage());
        }
    }

    private List<MaterialRequestDTO.MaterialItemResponse> parseItemsFromData(String itemsData) {
        List<MaterialRequestDTO.MaterialItemResponse> items = new ArrayList<>();

        if (itemsData == null || itemsData.isEmpty()) return items;

        for (String part : itemsData.split("\\|")) {
            String[] fields = part.split(":");
            if (fields.length >= 2) {
                try {
                    Long materialId  = Long.parseLong(fields[0]);
                    int  reqQty      = Integer.parseInt(fields[1]);
                    int  approvedQty = fields.length > 2 ? Integer.parseInt(fields[2]) : 0;

                    Optional<Material> matOpt = materialRepository.findById(materialId);
                    if (matOpt.isPresent()) {
                        Material mat = matOpt.get();
                        double unitPrice = mat.getUnitPrice() != null
                                ? mat.getUnitPrice().doubleValue() : 0.0;
                        double subtotal = unitPrice * reqQty;
                        int available = mat.getCurrentStock() != null
                                ? mat.getCurrentStock().intValue() : 0;

                        items.add(MaterialRequestDTO.MaterialItemResponse.builder()
                                .materialId(materialId)
                                .materialName(mat.getName())
                                .sku(mat.getSku())
                                .category(resolveCategoryName(mat.getCategoryId()))
                                .requestedQuantity(reqQty)
                                .approvedQuantity(approvedQty)
                                .unit(mat.getUnit())
                                .unitPrice(unitPrice)
                                .subtotal(subtotal)
                                .isFOC(mat.getChargeType() == Material.ChargeType.FOC)
                                .availableStock(available)
                                .build());
                    }
                } catch (Exception e) {
                    log.error("Error parsing item {}: {}", part, e.getMessage());
                }
            }
        }
        return items;
    }

    private MaterialRequestDTO.RequestResponse buildResponseFromEntity(MaterialRequest request) {
        List<MaterialRequestDTO.MaterialItemResponse> items = parseItemsFromData(request.getItemsData());
        return buildResponse(request, items);
    }

    private MaterialRequestDTO.RequestResponse buildResponse(
            MaterialRequest request,
            List<MaterialRequestDTO.MaterialItemResponse> items) {

        String statusName  = request.getStatus() != null ? request.getStatus().name() : null;
        String statusIcon  = getStatusIcon(statusName);
        String statusColor = getStatusColor(statusName);

        int totalQuantity = items.stream()
                .mapToInt(i -> i.getRequestedQuantity() != null ? i.getRequestedQuantity() : 0)
                .sum();

        return MaterialRequestDTO.RequestResponse.builder()
                .id(request.getId())
                .requestNumber(request.getRequestNumber())
                .requesterId(request.getRequestedBy())
                .requesterName(request.getRequestedByName())
                .requesterRole(null)
                .requesterPhone(null)
                .reviewerId(request.getReviewedBy())
                .reviewerName(request.getReviewedByName())
                .taskId(request.getTaskId())
                .faultId(request.getFaultId())
                .items(items)
                .totalItems(items.size())
                .totalQuantity(totalQuantity)
                .totalEstimatedCost(request.getTotalEstimatedCost() != null ? request.getTotalEstimatedCost() : 0)
                .totalApprovedCost(request.getTotalApprovedCost() != null ? request.getTotalApprovedCost() : 0)
                .status(statusName)
                .statusIcon(statusIcon)
                .statusColor(statusColor)
                .urgency(request.getUrgency())
                .requesterNotes(request.getRequesterNotes())
                .reviewerNotes(request.getReviewerNotes())
                .rejectionReason(request.getRejectionReason())
                .submittedAt(request.getCreatedAt())
                .reviewedAt(request.getReviewedAt())
                .deliveredAt(request.getDeliveredAt())
                .submittedTimeAgo(getTimeAgo(request.getCreatedAt()))
                .reviewedTimeAgo(getTimeAgo(request.getReviewedAt()))
                .build();
    }

    private String getStatusIcon(String status) {
        if (status == null) return "⏳";
        switch (status) {
            case "PENDING":   return "⏳";
            case "APPROVED":  return "✅";
            case "REJECTED":  return "❌";
            case "DELIVERED": return "📦";
            case "CANCELLED": return "🚫";
            default:          return "📋";
        }
    }

    private String getStatusColor(String status) {
        if (status == null) return "#FF9800";
        switch (status) {
            case "PENDING":   return "#FF9800";
            case "APPROVED":  return "#4CAF50";
            case "REJECTED":  return "#F44336";
            case "DELIVERED": return "#2196F3";
            case "CANCELLED": return "#9E9E9E";
            default:          return "#9E9E9E";
        }
    }

    private String getTimeAgo(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        long seconds = ChronoUnit.SECONDS.between(dateTime, LocalDateTime.now());
        if (seconds < 60)  return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60)  return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24)    return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }
}
