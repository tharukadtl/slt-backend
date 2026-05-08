package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.MaterialRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialRequestRepository
        extends JpaRepository<MaterialRequest, Long> {

    // ─── Find by Status (enum) ────────────────────────────

    @Query("SELECT mr FROM MaterialRequest mr "
            + "WHERE mr.status = :status "
            + "ORDER BY mr.createdAt ASC")
    List<MaterialRequest> findByStatusOrderByCreatedAtAsc(
            @Param("status") MaterialRequest.RequestStatus status);

    // ─── Find by Branch ───────────────────────────────────

    @Query("SELECT mr FROM MaterialRequest mr "
            + "WHERE mr.branchId = :branchId "
            + "ORDER BY mr.createdAt DESC")
    List<MaterialRequest> findByBranchIdOrderByCreatedAtDesc(
            @Param("branchId") Long branchId);

    // ─── Find by Requester ────────────────────────────────

    @Query("SELECT mr FROM MaterialRequest mr "
            + "WHERE mr.requestedBy = :userId "
            + "ORDER BY mr.createdAt DESC")
    List<MaterialRequest> findByRequestedByOrderByCreatedAtDesc(
            @Param("userId") Long userId);

    // ─── Find All History ─────────────────────────────────

    @Query("SELECT mr FROM MaterialRequest mr "
            + "ORDER BY mr.createdAt DESC")
    List<MaterialRequest> findAllHistory();

    // ─── Find by Request Number ───────────────────────────

    Optional<MaterialRequest> findByRequestNumber(String requestNumber);

    // ─── Count by Status ──────────────────────────────────

    @Query("SELECT COUNT(mr) FROM MaterialRequest mr "
            + "WHERE mr.status = :status")
    long countByStatus(
            @Param("status") MaterialRequest.RequestStatus status);
}
