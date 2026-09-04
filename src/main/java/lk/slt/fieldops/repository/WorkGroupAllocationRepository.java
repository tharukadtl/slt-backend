package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.WorkGroupAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkGroupAllocationRepository extends JpaRepository<WorkGroupAllocation, Long> {

    Optional<WorkGroupAllocation> findByWorkGroupIdAndMaterialId(Long workGroupId, Long materialId);

    List<WorkGroupAllocation> findByWorkGroupId(Long workGroupId);

    @Query("SELECT a FROM WorkGroupAllocation a WHERE a.workGroupId IN :workGroupIds")
    List<WorkGroupAllocation> findByWorkGroupIdIn(@Param("workGroupIds") List<Long> workGroupIds);
}
