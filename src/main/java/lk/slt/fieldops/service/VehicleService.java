package lk.slt.fieldops.service;

import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import lk.slt.fieldops.dto.CreateVehicleRequest;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.Vehicle;
import lk.slt.fieldops.entity.VehicleAssignment;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.VehicleAssignmentRepository;
import lk.slt.fieldops.repository.VehicleRepository;
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
 *   getById() / getByOpmc()  → Read methods
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
    private final UserRepository              userRepo;

    public VehicleService(VehicleRepository vehicleRepo,
                          VehicleAssignmentRepository assignmentRepo,
                          UserRepository userRepo) {
        this.vehicleRepo    = vehicleRepo;
        this.assignmentRepo = assignmentRepo;
        this.userRepo       = userRepo;
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
    public List<Vehicle> getAll() {
        return vehicleRepo.findAll();
    }

    @Transactional(readOnly = true)
    public List<Vehicle> getByOpmc(Long opmcId) {
        return vehicleRepo.findByOpmcId(opmcId);
    }

    @Transactional(readOnly = true)
    public List<Vehicle> getActiveByOpmc(Long opmcId) {
        return vehicleRepo.findByOpmcIdAndStatus(opmcId, Vehicle.VehicleStatus.AVAILABLE);
    }

    /**
     * RES-001/RES-014 — GET /api/vehicles's own ?status= filter, previously undeclared and
     * silently ignored (the whole fleet came back regardless of what was asked for).
     */
    @Transactional(readOnly = true)
    public List<Vehicle> getByStatus(Long opmcId, String status) {
        Vehicle.VehicleStatus statusFilter;
        try {
            statusFilter = Vehicle.VehicleStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid status: " + status +
                ". Valid: AVAILABLE, IN_USE, UNDER_REPAIR, INACTIVE");
        }
        return opmcId != null
            ? vehicleRepo.findByOpmcIdAndStatus(opmcId, statusFilter)
            : vehicleRepo.findByStatus(statusFilter);
    }

    @Transactional
    public Vehicle setStatus(Long id, String status) {
        Vehicle v = findOrThrow(id);
        try {
            v.setStatus(Vehicle.VehicleStatus.valueOf(status));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid status: " + status +
                ". Valid: AVAILABLE, IN_USE, UNDER_REPAIR, INACTIVE");
        }
        return vehicleRepo.save(v);
    }

    /**
     * Admin-initiated assignment of a vehicle to a technician (distinct from the
     * automatic daily BOD/EOD Team-Lead rotation in VehicleAssignment below).
     * Pass technicianId=null to unassign.
     */
    @Transactional
    public Vehicle assignTechnician(Long vehicleId, Long technicianId) {
        Vehicle v = findOrThrow(vehicleId);
        if (technicianId == null) {
            v.setAssignedTechnicianId(null);
            v.setAssignedTechnicianName(null);
            // RES-007 — a vehicle handed back must leave IN_USE and re-enter the pool
            // ?status=AVAILABLE and every other "who's free" query relies on.
            v.setStatus(Vehicle.VehicleStatus.AVAILABLE);
        } else {
            // RES-007 — refuse a double-issue rather than silently reassigning out from
            // under whoever already holds it.
            if (v.getAssignedTechnicianId() != null) {
                throw new RuntimeException(
                    "Vehicle '" + v.getRegistrationNumber() + "' is already assigned to "
                        + v.getAssignedTechnicianName() + ".");
            }
            User technician = userRepo.findById(technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + technicianId));
            v.setAssignedTechnicianId(technician.getId());
            v.setAssignedTechnicianName(technician.getFullName());
            // RES-006 — a vehicle handed to a technician must leave the AVAILABLE pool so
            // it can't be handed to a second person via a status-blind path.
            v.setStatus(Vehicle.VehicleStatus.IN_USE);

            // RES-006/014 — custody/mileage audit trail. Reuses the same vehicle_assignments
            // table the daily Team-Lead BOD/EOD rotation writes to (below) — this ad-hoc admin
            // assignment has no day-session to key against, so it's recorded keyed by the
            // technician instead of a Team Lead.
            VehicleAssignment assignment = new VehicleAssignment();
            assignment.setVehicleId(vehicleId);
            assignment.setTeamLeadId(technician.getId());
            assignment.setTeamLeadName(technician.getFullName());
            assignment.setBodOdometer(v.getCurrentOdometer());
            assignmentRepo.save(assignment);
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

        if (vehicle.getStatus() != Vehicle.VehicleStatus.AVAILABLE) {
            throw new RuntimeException(
                "Vehicle '" + vehicle.getRegistrationNumber() +
                "' is not available (status: " + vehicle.getStatus() + ").");
        }

        // RES-007 — a vehicle already handed to another Team Lead today must not be
        // handed to a second one as well, even though its status stays AVAILABLE (this
        // daily rotation tracks custody by date/team-lead in vehicle_assignments, separately
        // from vehicles.status, which assignTechnician's ad-hoc admin flow owns).
        if (assignmentRepo.existsByVehicleIdAndAssignmentDate(vehicleId, today)) {
            throw new RuntimeException(
                "Vehicle '" + vehicle.getRegistrationNumber() +
                "' is already assigned to another Team Lead today.");
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

        // Auto-calculate distance driven today. An EOD reading below the BOD reading is
        // impossible (a real drive never subtracts kilometres) — rejected as invalid input
        // rather than clamped to 0, which would erase the evidence of a mistyped or
        // fraudulent reading.
        if (assignment.getBodOdometer() != null) {
            int distance = eodOdometer - assignment.getBodOdometer();
            if (distance < 0) {
                throw new RuntimeException(
                        "Ending odometer reading (" + eodOdometer
                                + ") cannot be less than the starting reading ("
                                + assignment.getBodOdometer() + ").");
            }
            assignment.setDistanceKm(distance);
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

    /**
     * RES-008 — the day's mileage for a vehicle, from that day's vehicle_assignments row
     * (distance_km, already computed by closeAssignment). Null when no assignment exists for
     * that date, or the day hasn't been closed (EOD) yet — not a false "0 km".
     */
    @Transactional(readOnly = true)
    public Integer getDailyMileage(Long vehicleId, LocalDate date) {
        findOrThrow(vehicleId);
        return assignmentRepo.findByVehicleIdAndAssignmentDate(vehicleId, date)
            .map(VehicleAssignment::getDistanceKm)
            .orElse(null);
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
        v.setOpmcId(req.getOpmcId());
        v.setInsuranceExpiry(req.getInsuranceExpiry());
        v.setRevenueLicenseExpiry(req.getRevenueLicenseExpiry());
        v.setEmissionTestExpiry(req.getEmissionTestExpiry());
        v.setInsuranceCompany(req.getInsuranceCompany());
        v.setInsurancePolicyNumber(req.getInsurancePolicyNumber());
        v.setNotes(req.getNotes());
        v.setNextServiceDate(req.getNextServiceDate());

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
