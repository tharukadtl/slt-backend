package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.CauseCategoryDTO;
import lk.slt.fieldops.dto.CauseOfFaultDTO;
import lk.slt.fieldops.dto.TypeOfFaultDTO;
import lk.slt.fieldops.entity.CauseCategory;
import lk.slt.fieldops.entity.CauseOfFault;
import lk.slt.fieldops.entity.TypeOfFault;
import lk.slt.fieldops.repository.CauseCategoryRepository;
import lk.slt.fieldops.repository.CauseOfFaultRepository;
import lk.slt.fieldops.repository.TypeOfFaultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CauseHierarchyService — read-only access to the Cause/Material hierarchy (13 TypeOfFault -> 85
 * CauseCategory -> 869 CauseOfFault), imported 2026-08-21 but never given Java-level access until
 * Stage 2 (causeId resolution, QA_Compliance_Consolidated_Report.md). No create/update/delete — the
 * three entities are read-only reference data, same as their own class javadoc states.
 */
@Service
public class CauseHierarchyService {

    private final TypeOfFaultRepository    typeOfFaultRepo;
    private final CauseCategoryRepository  causeCategoryRepo;
    private final CauseOfFaultRepository   causeOfFaultRepo;

    public CauseHierarchyService(TypeOfFaultRepository typeOfFaultRepo,
                                  CauseCategoryRepository causeCategoryRepo,
                                  CauseOfFaultRepository causeOfFaultRepo) {
        this.typeOfFaultRepo   = typeOfFaultRepo;
        this.causeCategoryRepo = causeCategoryRepo;
        this.causeOfFaultRepo  = causeOfFaultRepo;
    }

    @Transactional(readOnly = true)
    public List<TypeOfFaultDTO> getAllTypesOfFault() {
        return typeOfFaultRepo.findAllByOrderBySortKeyAsc().stream()
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CauseCategoryDTO> getCauseCategories(Long typeOfFaultId) {
        List<CauseCategory> base = typeOfFaultId != null
            ? causeCategoryRepo.findByTypeOfFaultIdOrderBySortKeyAsc(typeOfFaultId)
            : causeCategoryRepo.findAllByOrderBySortKeyAsc();
        return base.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * causeCategoryId is required — unlike the Exchange/Cab/Dp/Circuit cascade's optional
     * parent-scoping, an unscoped "all 869" fetch has no legitimate use case here (the picker
     * always has a selected category before reaching this level), so this mirrors
     * CauseOfFaultRepository's own scoped-only finder rather than adding an unused unscoped path.
     */
    @Transactional(readOnly = true)
    public List<CauseOfFaultDTO> getCausesOfFault(Long causeCategoryId) {
        return causeOfFaultRepo.findByCauseCategoryIdOrderBySortKeyAsc(causeCategoryId).stream()
            .map(this::mapToDTO).collect(Collectors.toList());
    }

    private TypeOfFaultDTO mapToDTO(TypeOfFault t) {
        TypeOfFaultDTO dto = new TypeOfFaultDTO();
        dto.setId(t.getId());
        dto.setTypeCode(t.getTypeCode());
        dto.setDescription(t.getDescription());
        dto.setSortKey(t.getSortKey());
        return dto;
    }

    private CauseCategoryDTO mapToDTO(CauseCategory c) {
        CauseCategoryDTO dto = new CauseCategoryDTO();
        dto.setId(c.getId());
        dto.setCauseCategoryCode(c.getCauseCategoryCode());
        dto.setDescription(c.getDescription());
        dto.setTypeOfFaultId(c.getTypeOfFaultId());
        dto.setSortKey(c.getSortKey());
        return dto;
    }

    private CauseOfFaultDTO mapToDTO(CauseOfFault c) {
        CauseOfFaultDTO dto = new CauseOfFaultDTO();
        dto.setId(c.getId());
        dto.setCauseCode(c.getCauseCode());
        dto.setDescription(c.getDescription());
        dto.setCauseCategoryId(c.getCauseCategoryId());
        dto.setClarityDescription(c.getClarityDescription());
        dto.setAppliesCopper(c.getAppliesCopper());
        dto.setAppliesFtth(c.getAppliesFtth());
        dto.setAppliesLte(c.getAppliesLte());
        dto.setSortKey(c.getSortKey());
        return dto;
    }
}
