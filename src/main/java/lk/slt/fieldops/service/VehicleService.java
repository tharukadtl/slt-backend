package lk.slt.fieldops.vehicle.service;

import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import lk.slt.fieldops.vehicle.dto.CreateVehicleRequest;
import lk.slt.fieldops.vehicle.entity.Vehicle;
import lk.slt.fieldops.vehicle.entity.VehicleAssignment;
import lk.slt.fieldops.vehicle.repository.VehicleAssignmentRepository;
import lk.slt.fieldops.vehicle.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * VehicleService — Vehicle CRUD, daily assignment, odometer, expiry alerts.
 *
 * Methods:
 *   createVehicle()          → Admin adds a vehicle
 *   updateVehicle()          → Admin updates vehicle details
 *   getById() / getByBranch()→ Read methods
 *   assignVehicle()          → Called at BOD — one vehicle per TL per day
 *   closeAssignment()        → Called at EOD — calculates distance
 *   getExpiryAlerts()        → Insurance/license expiring within 30 days
 *   getExpiredDocuments()    → Already expired
 */
@Service
public class VehicleService {

    private static final int ALERT_DAYS = 30;

    private final VehicleRepository           vehicleRepo;
    private final VehicleAssignmentRepository assignmentRepo;

    public VehicleService(VehicleRepository vehicleRepo,
                          VehicleAssignmentRepository assignmentRepo) {
        this.vehicleRepo    = vehicleRepo;
        this.assignmentRepo = assignmentRepo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VEHICLE CRUD
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Vehicle createVehicle(CreateVehicleRequest req) {
        if (vehicleRepo.existsByRegistrationNumber(req.getRegistrationNumber())) {
            throw new RuntimeException(
                "Vehicle with registration '" + req.getRegistrationNumber() + "' already exists.");
        }

        Vehicle v = new Vehicle();
        mapRequest(req, v);
        return vehicleRepo.save(v);
    }

    @Transactional
    public Vehicle updateVehicle(Long id, CreateVehicleRequest req) {
        Vehicle v = findOrThrow(id);
        mapRequest(req, v);
        return vehicleRepo.save(v);
    }

    @Transactional(readOnly = true)
    public Vehicle getById(Long id) { return findOrThrow(id); }

    @Transactional(readOnly = true)
    public List<Vehicle> getByBranch(Long branchId) {
        return vehicleRepo.findByBranchId(branchId);
    }

    @Transactional(readOnly = true)
    public List<Vehicle> getActiveByBranch(Long branchId) {
        return vehicleRepo.findByBranchIdAndStatus(branchId, Vehicle.VehicleStatus.ACTIVE);
    }

    @Transactional
    public Vehicle setStatus(Long id, String status) {
        Vehicle v = findOrThrow(id);
        try {
            v.setStatus(Vehicle.VehicleStatus.valueOf(status));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid status: " + status +
                ". Valid: ACTIVE, INACTIVE, UNDER_MAINTENANCE");
        }
        return vehicleRepo.save(v);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY ASSIGNMENT — one vehicle per Team Lead per day
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Assign a vehicle to a Team Lead at BOD.
     * Called by JobService.performBod() automatically.
     * Enforces: one assignment per TL per day.
     */
    @Transactional
    public VehicleAssignment assignVehicle(Long vehicleId, Long teamLeadId,
                                            String teamLeadName, Long sessionId,
                                            Integer bodOdometer) {
        LocalDate today = LocalDate.now();

        // Check if this TL already has a vehicle today
        if (assignmentRepo.findByTeamLeadIdAndAssignmentDate(teamLeadId, today).isPresent()) {
            throw new RuntimeException(
                "Team Lead already has a vehicle assigned for today.");
        }

        Vehicle vehicle = findOrThrow(vehicleId);

        if (vehicle.getStatus() != Vehicle.VehicleStatus.ACTIVE) {
            throw new RuntimeException(
                "Vehicle '" + vehicle.getRegistrationNumber() +
                "' is not active (status: " + vehicle.getStatus() + ").");
        }

        VehicleAssignment assignment = new VehicleAssignment();
        assignment.setVehicleId(vehicleId);
        assignment.setTeamLeadId(teamLeadId);
        assignment.setTeamLeadName(teamLeadName);
        assignment.setSessionId(sessionId);
        assignment.setBodOdometer(bodOdometer);

        // Update vehicle's current odometer from BOD reading
        vehicle.setCurrentOdometer(bodOdometer);
        vehicleRepo.save(vehicle);

        return assignmentRepo.save(assignment);
    }

    /**
     * Close assignment at EOD — calculates distance driven today.
     * Called by JobService.performEod() automatically.
     */
    @Transactional
    public VehicleAssignment closeAssignment(Long teamLeadId, Integer eodOdometer) {
        VehicleAssignment assignment = assignmentRepo
            .findByTeamLeadIdAndAssignmentDate(teamLeadId, LocalDate.now())
            .orElseThrow(() -> new RuntimeException(
                "No vehicle assignment found for today. Cannot close."));

        assignment.setEodOdometer(eodOdometer);

        // Auto-calculate distance driven today
        if (assignment.getBodOdometer() != null) {
            int distance = eodOdometer - assignment.getBodOdometer();
            assignment.setDistanceKm(Math.max(0, distance));
        }

        // Update vehicle's current odometer
        vehicleRepo.findById(assignment.getVehicleId()).ifPresent(v -> {
            v.setCurrentOdometer(eodOdometer);
            vehicleRepo.save(v);
        });

        return assignmentRepo.save(assignment);
    }

    @Transactional(readOnly = true)
    public VehicleAssignment getTodaysAssignment(Long teamLeadId) {
        return assignmentRepo
            .findByTeamLeadIdAndAssignmentDate(teamLeadId, LocalDate.now())
            .orElseThrow(() -> new RuntimeException(
                "No vehicle assignment found for today. Please complete BOD."));
    }

    @Transactional(readOnly = true)
    public List<VehicleAssignment> getAssignmentHistory(Long vehicleId) {
        return assignmentRepo.findByVehicleIdOrderByAssignmentDateDesc(vehicleId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXPIRY ALERTS — FR-42
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns vehicles whose insurance OR revenue license expires
     * within the next 30 days.
     */
    @Transactional(readOnly = true)
    public List<Vehicle> getExpiryAlerts() {
        LocalDate alertDate = LocalDate.now().plusDays(ALERT_DAYS);
        return vehicleRepo.findVehiclesWithExpiringDocuments(alertDate);
    }

    /**
     * Returns vehicles with ALREADY EXPIRED documents.
     */
    @Transactional(readOnly = true)
    public List<Vehicle> getExpiredDocuments() {
        return vehicleRepo.findVehiclesWithExpiredDocuments();
    }

    /**
     * Summary for the Admin dashboard.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAlertSummary() {
        List<Vehicle> expiring = getExpiryAlerts();
        List<Vehicle> expired  = getExpiredDocuments();

        return Map.of(
            "expiringWithin30Days", expiring.size(),
            "alreadyExpired",       expired.size(),
            "expiring",             expiring,
            "expired",              expired
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Vehicle findOrThrow(Long id) {
        return vehicleRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found with id: " + id));
    }

    private void mapRequest(CreateVehicleRequest req, Vehicle v) {
        v.setRegistrationNumber(req.getRegistrationNumber());
        v.setMake(req.getMake());
        v.setModel(req.getModel());
        v.setModelYear(req.getModelYear());
        v.setBranchId(req.getBranchId());
        v.setInsuranceExpiry(req.getInsuranceExpiry());
        v.setRevenueLicenseExpiry(req.getRevenueLicenseExpiry());
        v.setEmissionTestExpiry(req.getEmissionTestExpiry());
        v.setInsuranceCompany(req.getInsuranceCompany());
        v.setInsurancePolicyNumber(req.getInsurancePolicyNumber());
        v.setNotes(req.getNotes());

        if (req.getCurrentOdometer() != null) {
            v.setCurrentOdometer(req.getCurrentOdometer());
        }
        try {
            v.setVehicleType(Vehicle.VehicleType.valueOf(req.getVehicleType()));
        } catch (Exception e) { v.setVehicleType(Vehicle.VehicleType.VAN); }
        try {
            v.setFuelType(Vehicle.FuelType.valueOf(req.getFuelType()));
        } catch (Exception e) { v.setFuelType(Vehicle.FuelType.PETROL); }
    }
}
