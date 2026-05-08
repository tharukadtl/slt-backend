package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.StockTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockTransactionRepository
        extends JpaRepository<StockTransaction, Long> {

    @Query("SELECT st FROM StockTransaction st WHERE st.materialId = :materialId ORDER BY st.createdAt DESC")
    List<StockTransaction> findByMaterialId(@Param("materialId") Long materialId);

    @Query("SELECT st FROM StockTransaction st WHERE st.materialId = :materialId ORDER BY st.createdAt DESC")
    List<StockTransaction> findByMaterialIdOrderByCreatedAtDesc(@Param("materialId") Long materialId);

    @Query("SELECT st FROM StockTransaction st WHERE st.transactionType = :type ORDER BY st.createdAt DESC")
    List<StockTransaction> findByType(@Param("type") StockTransaction.TransactionType type);

    @Query("SELECT st FROM StockTransaction st WHERE st.createdAt BETWEEN :startDate AND :endDate ORDER BY st.createdAt DESC")
    List<StockTransaction> findByDateRange(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT st FROM StockTransaction st WHERE st.materialId = :materialId AND st.createdAt BETWEEN :startDate AND :endDate ORDER BY st.createdAt DESC")
    List<StockTransaction> findByMaterialIdAndDateRange(
            @Param("materialId") Long materialId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT st FROM StockTransaction st WHERE st.performedBy = :userId ORDER BY st.createdAt DESC")
    List<StockTransaction> findByPerformedById(@Param("userId") Long userId);

    @Query("SELECT st FROM StockTransaction st ORDER BY st.createdAt DESC")
    List<StockTransaction> findRecent();
}
