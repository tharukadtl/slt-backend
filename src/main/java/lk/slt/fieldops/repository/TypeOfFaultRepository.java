package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.TypeOfFault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * TypeOfFaultRepository — top of the Cause/Material hierarchy (13 rows). Read-only reference data,
 * imported once by import_cause_material_data.py; this is the first Java-level access to the table
 * (Stage 2, QA_Compliance_Consolidated_Report.md causeId resolution).
 */
@Repository
public interface TypeOfFaultRepository extends JpaRepository<TypeOfFault, Long> {
    Optional<TypeOfFault> findByTypeCode(String typeCode);
    List<TypeOfFault> findAllByOrderBySortKeyAsc();
}
