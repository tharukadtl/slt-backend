package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.VehicleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleAssignmentRepository extends JpaRepository<VehicleAssignment, Long> {

    /** Get today's assignment for a Team Lead */
    Optional<VehicleAssignment> findByTeamLeadIdAndAssignmentDate(Long teamLeadId, LocalDate date);

    /** Check if vehicle is already assigned today */
    boolean existsByVehicleIdAndAssignmentDate(Long vehicleId, LocalDate date);

    /** Get all assignments for a vehicle (mileage history) */
    List<VehicleAssignment> findByVehicleIdOrderByAssignmentDateDesc(Long vehicleId);

    /** All assignments by a Team Lead */
    List<VehicleAssignment> findByTeamLeadIdOrderByAssignmentDateDesc(Long teamLeadId);
}
