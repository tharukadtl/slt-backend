package lk.slt.fieldops.inventory.repository;

import lk.slt.fieldops.inventory.entity.StockTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StockTransactionRepository extends JpaRepository<StockTransaction, Long> {

    List<StockTransaction> findByMaterialIdOrderByCreatedAtDesc(Long materialId);

    List<StockTransaction> findByReferenceTypeAndReferenceId(
            StockTransaction.ReferenceType refType, Long refId);

    List<StockTransaction> findByTransactionTypeOrderByCreatedAtDesc(
            StockTransaction.TransactionType type);
}
