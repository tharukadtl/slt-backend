package lk.slt.fieldops.vehicle.repository;

import lk.slt.fieldops.vehicle.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByRegistrationNumber(String registrationNumber);

    boolean existsByRegistrationNumber(String registrationNumber);

    List<Vehicle> findByBranchId(Long branchId);

    List<Vehicle> findByBranchIdAndStatus(Long branchId, Vehicle.VehicleStatus status);

    /**
     * EXPIRY ALERT — FR-42
     * Vehicles where insurance_expiry OR revenue_license_expiry
     * is within the next 30 days.
     */
    @Query("SELECT v FROM Vehicle v WHERE v.status = 'ACTIVE' AND " +
           "(v.insuranceExpiry <= :alertDate OR v.revenueLicenseExpiry <= :alertDate)")
    List<Vehicle> findVehiclesWithExpiringDocuments(LocalDate alertDate);

    /** Vehicles with expired documents (past today) */
    @Query("SELECT v FROM Vehicle v WHERE v.status = 'ACTIVE' AND " +
           "(v.insuranceExpiry < CURRENT_DATE OR v.revenueLicenseExpiry < CURRENT_DATE)")
    List<Vehicle> findVehiclesWithExpiredDocuments();
}
