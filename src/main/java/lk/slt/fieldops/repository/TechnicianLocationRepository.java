package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.TechnicianLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TechnicianLocationRepository
        extends JpaRepository<TechnicianLocation, Long> {

    @Query("SELECT COUNT(t) FROM TechnicianLocation t WHERE t.isActive = true AND t.lastUpdated >= :since")
    long countRecentlyActive(@Param("since") LocalDateTime since);

    List<TechnicianLocation> findAllByIsActiveTrue();

    @Query("SELECT t FROM TechnicianLocation t WHERE t.user.id = :userId AND t.isActive = true ORDER BY t.lastUpdated DESC")
    Optional<TechnicianLocation> findActiveByUserId(@Param("userId") Long userId);

    @Query("SELECT t FROM TechnicianLocation t WHERE t.isActive = true AND t.lastUpdated >= :since ORDER BY t.lastUpdated DESC")
    List<TechnicianLocation> findRecentlyActive(@Param("since") LocalDateTime since);

    @Query("SELECT t FROM TechnicianLocation t WHERE t.user.branchId = :branchId AND t.isActive = true ORDER BY t.lastUpdated DESC")
    List<TechnicianLocation> findActiveByBranchId(@Param("branchId") Long branchId);

    @Query(value = "SELECT * FROM technician_locations t WHERE t.is_active = true AND " +
            "(6371 * acos(cos(radians(:lat)) * cos(radians(t.latitude)) * " +
            "cos(radians(t.longitude) - radians(:lng)) + " +
            "sin(radians(:lat)) * sin(radians(t.latitude)))) <= :radiusKm",
            nativeQuery = true)
    List<TechnicianLocation> findWithinRadius(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusKm") double radiusKm);

    @Modifying
    @Query("UPDATE TechnicianLocation t SET t.isActive = false WHERE t.user.id = :userId")
    void deactivateAllByUserId(@Param("userId") Long userId);
}
