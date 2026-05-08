package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialRepository extends JpaRepository<Material, Long> {

    boolean existsBySku(String sku);

    Optional<Material> findBySku(String sku);

    @Query("SELECT m FROM Material m WHERE m.isActive = true ORDER BY m.name ASC")
    List<Material> findAllActive();

    @Query("SELECT m FROM Material m WHERE m.categoryId = :categoryId AND m.isActive = true ORDER BY m.name ASC")
    List<Material> findByCategoryId(@Param("categoryId") Long categoryId);

    @Query("SELECT m FROM Material m WHERE m.branchId = :branchId AND m.isActive = true ORDER BY m.name ASC")
    List<Material> findByBranchIdAndIsActiveTrue(@Param("branchId") Long branchId);

    @Query("SELECT m FROM Material m WHERE m.isActive = true AND LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY m.name ASC")
    List<Material> searchByName(@Param("keyword") String keyword);

    @Query("SELECT m FROM Material m WHERE m.isActive = true AND m.currentStock <= m.minimumThreshold ORDER BY m.currentStock ASC")
    List<Material> findLowStockMaterials();

    @Query("SELECT m FROM Material m WHERE m.branchId = :branchId AND m.isActive = true AND m.currentStock <= m.minimumThreshold ORDER BY m.currentStock ASC")
    List<Material> findLowStockByBranch(@Param("branchId") Long branchId);

    @Query("SELECT m FROM Material m WHERE m.isActive = true AND m.currentStock = 0 ORDER BY m.name ASC")
    List<Material> findOutOfStock();

    @Query("SELECT m FROM Material m WHERE m.isActive = true AND LOWER(m.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(m.sku) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY m.name ASC")
    List<Material> searchByNameOrSku(@Param("search") String search);

    @Query("SELECT COUNT(m) FROM Material m WHERE m.isActive = true AND m.currentStock <= m.minimumThreshold")
    long countLowStock();

    @Query("SELECT m FROM Material m WHERE m.isActive = true AND m.currentStock <= m.minimumThreshold ORDER BY m.currentStock ASC")
    List<Material> findLowStock();
}
