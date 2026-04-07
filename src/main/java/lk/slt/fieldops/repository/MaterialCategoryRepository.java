package lk.slt.fieldops.inventory.repository;

import lk.slt.fieldops.inventory.entity.MaterialCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MaterialCategoryRepository extends JpaRepository<MaterialCategory, Long> {
    List<MaterialCategory> findByIsActiveTrue();
    List<MaterialCategory> findByParentIdIsNull();   // top-level categories
    List<MaterialCategory> findByParentId(Long parentId);
}
