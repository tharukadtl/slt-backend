package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.ConfirmedResourcePlanMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConfirmedResourcePlanMaterialRepository extends JpaRepository<ConfirmedResourcePlanMaterial, Long> {

    List<ConfirmedResourcePlanMaterial> findByConfirmedResourcePlanId(Long confirmedResourcePlanId);

    void deleteByConfirmedResourcePlanId(Long confirmedResourcePlanId);

    List<ConfirmedResourcePlanMaterial> findByConfirmedResourcePlanIdIn(List<Long> confirmedResourcePlanIds);
}
