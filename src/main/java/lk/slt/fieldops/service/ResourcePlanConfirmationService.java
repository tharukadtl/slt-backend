package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.ConfirmResourcePlanRequest;
import lk.slt.fieldops.dto.ConfirmResourcePlanResponse;
import lk.slt.fieldops.dto.ConfirmedPlanDTO;
import lk.slt.fieldops.entity.ConfirmedResourcePlan;
import lk.slt.fieldops.entity.ConfirmedResourcePlanMaterial;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.ConfirmedResourcePlanMaterialRepository;
import lk.slt.fieldops.repository.ConfirmedResourcePlanRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ResourcePlanConfirmationService — FR-33 Stage 3b.
 *
 * Persists the Admin's confirmed Predictive Resource Planning suggestions
 * (frontend-admin's Resource Planning panel, Stage 3a) per (OPMC, date,
 * shift), and hands them back to the Team Lead's BOD screen as a starting
 * point — never a locked/forced value, per SRS 5.6.8.
 */
@Service
public class ResourcePlanConfirmationService {

    private final ConfirmedResourcePlanRepository planRepository;
    private final ConfirmedResourcePlanMaterialRepository materialRepository;
    private final OpmcRepository opmcRepository;
    private final UserRepository userRepository;

    public ResourcePlanConfirmationService(
            ConfirmedResourcePlanRepository planRepository,
            ConfirmedResourcePlanMaterialRepository materialRepository,
            OpmcRepository opmcRepository,
            UserRepository userRepository) {
        this.planRepository = planRepository;
        this.materialRepository = materialRepository;
        this.opmcRepository = opmcRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ConfirmResourcePlanResponse confirmPlan(ConfirmResourcePlanRequest request, Long adminUserId) {
        Map<String, Long> zoneOpmcMap = request.getZoneOpmcMap() != null
                ? request.getZoneOpmcMap() : Collections.emptyMap();
        List<ConfirmResourcePlanRequest.HotspotItem> hotspots = request.getHotspots() != null
                ? request.getHotspots() : Collections.emptyList();

        LocalDateTime confirmedAt = LocalDateTime.now();

        int confirmed = 0;
        List<String> skipped = new ArrayList<>();

        for (ConfirmResourcePlanRequest.HotspotItem h : hotspots) {
            Long opmcId = h.getZoneId() != null ? zoneOpmcMap.get(String.valueOf(h.getZoneId())) : null;
            if (opmcId == null) {
                skipped.add(describeHotspot(h) + ": no OPMC mapped for this zone");
                continue;
            }
            if (!opmcRepository.existsById(opmcId)) {
                skipped.add(describeHotspot(h) + ": OPMC " + opmcId + " does not exist");
                continue;
            }

            LocalDate planDate;
            ConfirmedResourcePlan.Shift shift;
            try {
                planDate = LocalDate.parse(h.getDate());
                shift = ConfirmedResourcePlan.Shift.valueOf(h.getShift().toUpperCase());
            } catch (Exception e) {
                skipped.add(describeHotspot(h) + ": invalid date/shift");
                continue;
            }

            // Re-confirming the same (OPMC, date, shift) replaces what was there
            // before. Updated IN PLACE (not delete-then-insert): Hibernate's
            // default flush order runs inserts before deletes, so a fresh row
            // sharing the same (opmc_id, plan_date, shift) unique key as one
            // still-pending deletion would violate that constraint.
            Optional<ConfirmedResourcePlan> existing =
                    planRepository.findByOpmcIdAndPlanDateAndShift(opmcId, planDate, shift);
            ConfirmedResourcePlan plan = existing.orElseGet(ConfirmedResourcePlan::new);
            if (existing.isPresent()) {
                materialRepository.deleteByConfirmedResourcePlanId(plan.getId());
            }

            plan.setOpmcId(opmcId);
            plan.setPlanDate(planDate);
            plan.setShift(shift);
            plan.setZoneId(h.getZoneId());
            plan.setZoneName(h.getZoneName());
            plan.setPredictedFaultCount(h.getPredictedFaultCount());
            plan.setSuggestedTechnicians(h.getSuggestedTechnicians());
            plan.setSuggestedVehicles(h.getSuggestedVehicles());
            plan.setConfirmedBy(adminUserId);
            plan.setConfirmedAt(confirmedAt);
            plan = planRepository.save(plan);

            if (h.getMaterials() != null) {
                for (ConfirmResourcePlanRequest.MaterialItem m : h.getMaterials()) {
                    ConfirmedResourcePlanMaterial material = new ConfirmedResourcePlanMaterial();
                    material.setConfirmedResourcePlanId(plan.getId());
                    material.setMaterialId(m.getMaterialId());
                    material.setMaterialName(m.getMaterialName());
                    material.setSuggestedQuantity(m.getSuggestedQuantity());
                    material.setUnit(m.getUnit());
                    materialRepository.save(material);
                }
            }

            confirmed++;
        }

        return new ConfirmResourcePlanResponse(confirmed, skipped.size(), skipped);
    }

    /** Used by GET /api/resource-plans/lookup — resolves the caller's own OPMC. */
    public List<ConfirmedPlanDTO> getConfirmedPlanForTeamLead(Long teamLeadUserId, LocalDate date) {
        Optional<User> user = userRepository.findById(teamLeadUserId);
        Long opmcId = user.map(User::getOpmcId).orElse(null);
        if (opmcId == null) {
            return Collections.emptyList();
        }
        return getConfirmedPlanForOpmc(opmcId, date);
    }

    public List<ConfirmedPlanDTO> getConfirmedPlanForOpmc(Long opmcId, LocalDate date) {
        List<ConfirmedResourcePlan> plans = planRepository.findByOpmcIdAndPlanDate(opmcId, date);
        if (plans.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> planIds = plans.stream().map(ConfirmedResourcePlan::getId).collect(Collectors.toList());
        Map<Long, List<ConfirmedResourcePlanMaterial>> materialsByPlanId =
                materialRepository.findByConfirmedResourcePlanIdIn(planIds).stream()
                        .collect(Collectors.groupingBy(ConfirmedResourcePlanMaterial::getConfirmedResourcePlanId));

        return plans.stream().map(p -> toDto(p, materialsByPlanId.getOrDefault(p.getId(), Collections.emptyList())))
                .collect(Collectors.toList());
    }

    private ConfirmedPlanDTO toDto(ConfirmedResourcePlan p, List<ConfirmedResourcePlanMaterial> materials) {
        ConfirmedPlanDTO dto = new ConfirmedPlanDTO();
        dto.setShift(p.getShift().name());
        dto.setZoneId(p.getZoneId());
        dto.setZoneName(p.getZoneName());
        dto.setPredictedFaultCount(p.getPredictedFaultCount());
        dto.setSuggestedTechnicians(p.getSuggestedTechnicians());
        dto.setSuggestedVehicles(p.getSuggestedVehicles());
        dto.setConfirmedAt(p.getConfirmedAt() != null ? p.getConfirmedAt().toString() : null);
        dto.setMaterials(materials.stream()
                .map(m -> new ConfirmedPlanDTO.MaterialDTO(
                        m.getMaterialId(), m.getMaterialName(), m.getSuggestedQuantity(), m.getUnit()))
                .collect(Collectors.toList()));
        return dto;
    }

    private String describeHotspot(ConfirmResourcePlanRequest.HotspotItem h) {
        return h.getDate() + " " + h.getShift() + " zone " + h.getZoneId() + " (" + h.getZoneName() + ")";
    }
}
