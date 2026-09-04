package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.CircuitCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CircuitCategoryRepository extends JpaRepository<CircuitCategory, Integer> {
    Optional<CircuitCategory> findByCode(String code);
}
