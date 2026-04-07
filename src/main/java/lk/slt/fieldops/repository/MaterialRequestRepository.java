package lk.slt.fieldops.inventory.repository;

import lk.slt.fieldops.inventory.entity.MaterialRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MaterialRequestRepository extends JpaRepository<MaterialRequest, Long> {

    List<MaterialRequest> findByBranchIdOrderByCreatedAtDesc(Long branchId);

    List<MaterialRequest> findByStatusOrderByCreatedAtAsc(MaterialRequest.RequestStatus status);

    List<MaterialRequest> findByRequestedByOrderByCreatedAtDesc(Long userId);

    List<MaterialRequest> findByBranchIdAndStatus(Long branchId, MaterialRequest.RequestStatus status);
}
