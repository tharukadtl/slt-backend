package lk.slt.fieldops.inventory.repository;

import lk.slt.fieldops.inventory.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialRepository extends JpaRepository<Material, Long> {

    Optional<Material> findBySku(String sku);

    boolean existsBySku(String sku);

    List<Material> findByBranchId(Long branchId);

    List<Material> findByBranchIdAndIsActiveTrue(Long branchId);

    List<Material> findByCategoryId(Long categoryId);

    List<Material> findByChargeType(Material.ChargeType chargeType);

    /**
     * LOW STOCK ALERT — FR-39
     * Returns any material where current_stock <= minimum_threshold
     */
    @Query("SELECT m FROM Material m WHERE m.currentStock <= m.minimumThreshold AND m.isActive = TRUE")
    List<Material> findLowStockMaterials();

    /**
     * LOW STOCK for a specific branch
     */
    @Query("SELECT m FROM Material m WHERE m.branchId = :branchId AND m.currentStock <= m.minimumThreshold AND m.isActive = TRUE")
    List<Material> findLowStockByBranch(Long branchId);

    /**
     * Search by name keyword
     */
    @Query("SELECT m FROM Material m WHERE LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) AND m.isActive = TRUE")
    List<Material> searchByName(String keyword);
}
