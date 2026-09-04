package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.WorkGroupAllocationDTO;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.StockTransaction;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.entity.WorkGroupAllocation;
import lk.slt.fieldops.repository.MaterialRepository;
import lk.slt.fieldops.repository.StockTransactionRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupAllocationRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
import lk.slt.fieldops.shared.RequestContext;
import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ResourceAllocationService — SRS 5.5.3 (v1.9): the middle tier of the
 * three-level material hierarchy (OPMC pool -> Work Group -> Team Lead
 * distribution). The OPMC pool itself is {@code Material.currentStock}
 * (already OPMC-scoped since Stage B); this service is only the allocation
 * step Admin performs from that pool into a Work Group's balance, which
 * {@code MaterialRequestService.approveRequest} then draws down from.
 */
@Service
public class ResourceAllocationService {

    private final WorkGroupAllocationRepository allocationRepo;
    private final WorkGroupRepository           workGroupRepo;
    private final MaterialRepository            materialRepo;
    private final UserRepository                userRepo;
    private final StockTransactionRepository    stockTransactionRepo;

    public ResourceAllocationService(WorkGroupAllocationRepository allocationRepo,
                                      WorkGroupRepository workGroupRepo,
                                      MaterialRepository materialRepo,
                                      UserRepository userRepo,
                                      StockTransactionRepository stockTransactionRepo) {
        this.allocationRepo       = allocationRepo;
        this.workGroupRepo        = workGroupRepo;
        this.materialRepo         = materialRepo;
        this.userRepo             = userRepo;
        this.stockTransactionRepo = stockTransactionRepo;
    }

    @Transactional
    public WorkGroupAllocationDTO.AllocationResponse allocateToWorkGroup(
            WorkGroupAllocationDTO.AllocateRequest req, Long adminId) {

        WorkGroup workGroup = workGroupRepo.findById(req.getWorkGroupId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Work Group not found: " + req.getWorkGroupId()));

        Material material = materialRepo.findById(req.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Material not found: " + req.getMaterialId()));

        if (workGroup.getOpmc() == null || material.getOpmcId() == null
                || !workGroup.getOpmc().getId().equals(material.getOpmcId())) {
            throw new RuntimeException(
                    "Material " + material.getId() + " does not belong to Work Group "
                            + workGroup.getId() + "'s OPMC.");
        }

        BigDecimal currentStock = material.getCurrentStock() != null
                ? material.getCurrentStock() : BigDecimal.ZERO;
        if (req.getQuantity().compareTo(currentStock) > 0) {
            throw new RuntimeException(
                    "Cannot allocate " + req.getQuantity() + " " + material.getUnit()
                            + " of '" + material.getName() + "' — only " + currentStock
                            + " " + material.getUnit() + " in the OPMC pool.");
        }

        User admin = userRepo.findById(adminId)
                .orElseThrow(() -> new RuntimeException("Admin not found: " + adminId));

        // Decrement the OPMC pool
        material.setCurrentStock(currentStock.subtract(req.getQuantity()));
        materialRepo.save(material);

        // Increment (or create) the Work Group's balance
        WorkGroupAllocation allocation = allocationRepo
                .findByWorkGroupIdAndMaterialId(workGroup.getId(), material.getId())
                .orElseGet(() -> WorkGroupAllocation.builder()
                        .workGroupId(workGroup.getId())
                        .materialId(material.getId())
                        .allocatedQuantity(BigDecimal.ZERO)
                        .build());
        allocation.setAllocatedQuantity(allocation.getAllocatedQuantity().add(req.getQuantity()));
        WorkGroupAllocation saved = allocationRepo.save(allocation);

        StockTransaction tx = new StockTransaction();
        tx.setMaterialId(material.getId());
        tx.setMaterialName(material.getName());
        tx.setMaterialSku(material.getSku());
        tx.setMaterialUnit(material.getUnit());
        tx.setTransactionType(StockTransaction.TransactionType.STOCK_OUT);
        tx.setQuantity(req.getQuantity());
        tx.setReason("ALLOCATED_TO_WORK_GROUP");
        tx.setReference("Work Group: " + workGroup.getName());
        tx.setPerformedBy(admin.getId());
        tx.setPerformedByName(admin.getFullName());
        tx.setPerformedByRole(admin.getRole() != null ? admin.getRole().name() : null);
        tx.setIpAddress(RequestContext.getClientIp());
        stockTransactionRepo.save(tx);

        return toResponse(workGroup, material, saved);
    }

    @Transactional(readOnly = true)
    public List<WorkGroupAllocationDTO.AllocationResponse> getAllocationsForWorkGroup(Long workGroupId) {
        WorkGroup workGroup = workGroupRepo.findById(workGroupId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkGroup not found with id: " + workGroupId));
        return allocationRepo.findByWorkGroupId(workGroupId).stream()
                .map(a -> toResponse(workGroup, requireMaterial(a.getMaterialId()), a))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WorkGroupAllocationDTO.AllocationResponse> getAllocationsForOpmc(Long opmcId) {
        List<WorkGroup> workGroups = workGroupRepo.findActiveByOpmcId(opmcId);
        List<Long> workGroupIds = workGroups.stream().map(WorkGroup::getId).collect(Collectors.toList());
        if (workGroupIds.isEmpty()) {
            return List.of();
        }
        return allocationRepo.findByWorkGroupIdIn(workGroupIds).stream()
                .map(a -> {
                    WorkGroup wg = workGroups.stream()
                            .filter(w -> w.getId().equals(a.getWorkGroupId()))
                            .findFirst().orElseThrow();
                    return toResponse(wg, requireMaterial(a.getMaterialId()), a);
                })
                .collect(Collectors.toList());
    }

    private Material requireMaterial(Long materialId) {
        return materialRepo.findById(materialId)
                .orElseThrow(() -> new ResourceNotFoundException("Material not found: " + materialId));
    }

    private WorkGroupAllocationDTO.AllocationResponse toResponse(
            WorkGroup workGroup, Material material, WorkGroupAllocation allocation) {
        return WorkGroupAllocationDTO.AllocationResponse.builder()
                .workGroupId(workGroup.getId())
                .workGroupName(workGroup.getName())
                .materialId(material.getId())
                .materialName(material.getName())
                .allocatedQuantity(allocation.getAllocatedQuantity())
                .opmcRemainingStock(material.getCurrentStock())
                .updatedAt(allocation.getUpdatedAt())
                .build();
    }
}
