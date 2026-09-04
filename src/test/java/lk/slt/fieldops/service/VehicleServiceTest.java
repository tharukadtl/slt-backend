package lk.slt.fieldops.service;

import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.Vehicle;
import lk.slt.fieldops.entity.VehicleAssignment;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.VehicleAssignmentRepository;
import lk.slt.fieldops.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RES-007 and RES-011 (05_RESOURCE_MGMT, FR-14) — the two {@link VehicleService} guards the sheet
 * specifies as unit tests: a vehicle already in someone's hands cannot be handed to a second person,
 * and a vehicle under maintenance cannot be assigned at all.
 *
 * <p><b>Two assignment paths, both exercised.</b> The sheet writes {@code assignVehicle(vId, techId)}.
 * {@link VehicleService} has two distinct assignment entry points and neither has that signature:</p>
 * <ul>
 *   <li>{@code assignVehicle(vehicleId, teamLeadId, teamLeadName, sessionId, bodOdometer)} — the
 *       daily Team-Lead assignment, which creates a {@code vehicle_assignments} row. This is the one
 *       literally named {@code assignVehicle}, so it is the primary subject here.</li>
 *   <li>{@code assignTechnician(vehicleId, technicianId)} — the admin action behind
 *       {@code PATCH /api/vehicles/{id}/assign-technician}, which writes
 *       {@code vehicles.assigned_technician_id} and is what the sheet's "assign to technician"
 *       wording actually describes.</li>
 * </ul>
 * <p>Both are asserted so the verdict localises which path holds and which does not.</p>
 *
 * <p><b>Exception types.</b> The sheet asserts {@code AlreadyAssignedException} and
 * {@code VehicleUnavailableException}. Neither type exists in this codebase — {@link VehicleService}
 * signals both business rules with a plain {@link RuntimeException}, which
 * {@code GlobalExceptionHandler.handleRuntime} maps to HTTP 400, the same contract the sheet's
 * Expected Result describes. These tests assert the exception AND its message. Same convention as
 * {@code JobServiceTest}.</p>
 *
 * <p><b>Status vocabulary.</b> The sheet says {@code UNDER_MAINTENANCE}.
 * {@code Vehicle.VehicleStatus} is {@code AVAILABLE, IN_USE, UNDER_REPAIR, INACTIVE} — the
 * equivalent constant is {@code UNDER_REPAIR}, which is what is asserted.</p>
 *
 * <p>Pure Mockito unit test (no Spring, no MySQL), matching the module convention established by
 * {@code JobServiceTest} / {@code JobServiceBodDuplicateTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VehicleServiceTest {

    @Mock private VehicleRepository           vehicleRepo;
    @Mock private VehicleAssignmentRepository assignmentRepo;
    @Mock private UserRepository              userRepo;

    private VehicleService vehicleService;

    private static final Long VEHICLE_ID   = 3L;
    private static final Long TECH_5       = 5L;
    private static final Long TECH_7       = 7L;
    private static final Long LEAD_A       = 11L;
    private static final Long LEAD_B       = 12L;
    private static final int  BOD_ODOMETER = 15200;

    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        vehicleService = new VehicleService(vehicleRepo, assignmentRepo, userRepo);

        vehicle = new Vehicle();
        vehicle.setId(VEHICLE_ID);
        vehicle.setRegistrationNumber("WP CAE-3456");
        vehicle.setMake("Toyota");
        vehicle.setModel("HiAce");
        vehicle.setStatus(Vehicle.VehicleStatus.AVAILABLE);
        vehicle.setCurrentOdometer(BOD_ODOMETER);

        when(vehicleRepo.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle));
        when(vehicleRepo.save(any(Vehicle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepo.save(any(VehicleAssignment.class))).thenAnswer(inv -> {
            VehicleAssignment a = inv.getArgument(0);
            if (a.getAssignmentDate() == null) a.setAssignmentDate(LocalDate.now());
            return a;
        });

        User tech5 = technician(TECH_5, "Tech Nimal");
        User tech7 = technician(TECH_7, "Tech Sunil");
        when(userRepo.findById(TECH_5)).thenReturn(Optional.of(tech5));
        when(userRepo.findById(TECH_7)).thenReturn(Optional.of(tech7));
    }

    private User technician(Long id, String name) {
        User u = new User();
        u.setId(id);
        u.setFullName(name);
        u.setRole(User.Role.TECHNICIAN);
        return u;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-007 — prevent double vehicle assignment
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void assignVehicle_alreadyAssigned_throws() {
        assertAll("one vehicle cannot be in two people's hands at once",

            // ── Steps 1-3, admin path: vehicle already assigned to tech 5 ─────────────────────
            () -> {
                vehicle.setAssignedTechnicianId(TECH_5);
                vehicle.setAssignedTechnicianName("Tech Nimal");

                RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> vehicleService.assignTechnician(VEHICLE_ID, TECH_7),
                    "Assigning a vehicle that is already assigned to technician " + TECH_5
                        + " must be refused, not silently reassigned");

                assertTrue(thrown.getMessage() != null
                        && thrown.getMessage().toLowerCase().contains("already assigned"),
                    "The refusal must say the vehicle is already assigned; was: "
                        + thrown.getMessage());
            },

            // ── Same rule on the daily Team-Lead path ────────────────────────────────────────
            () -> {
                vehicle.setAssignedTechnicianId(null);
                vehicle.setStatus(Vehicle.VehicleStatus.AVAILABLE);

                // Lead A takes the vehicle for today.
                when(assignmentRepo.findByTeamLeadIdAndAssignmentDate(eq(LEAD_A), any()))
                    .thenReturn(Optional.empty());
                when(assignmentRepo.existsByVehicleIdAndAssignmentDate(eq(VEHICLE_ID), any()))
                    .thenReturn(false);

                vehicleService.assignVehicle(VEHICLE_ID, LEAD_A, "Lead Kamal", 100L, BOD_ODOMETER);

                // Now Lead B asks for the same vehicle, the same day.
                when(assignmentRepo.findByTeamLeadIdAndAssignmentDate(eq(LEAD_B), any()))
                    .thenReturn(Optional.empty());
                when(assignmentRepo.existsByVehicleIdAndAssignmentDate(eq(VEHICLE_ID), any()))
                    .thenReturn(true);

                RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> vehicleService.assignVehicle(
                        VEHICLE_ID, LEAD_B, "Lead Sarath", 101L, BOD_ODOMETER),
                    "A vehicle already assigned to Team Lead " + LEAD_A + " today must not be "
                        + "assignable to Team Lead " + LEAD_B + " as well — "
                        + "VehicleAssignmentRepository.existsByVehicleIdAndAssignmentDate exists "
                        + "for exactly this check but VehicleService never calls it");

                assertNotNull(thrown.getMessage(), "The refusal must carry a message");
            },

            // ── Step 4: after unassigning, the vehicle can be handed to someone else ─────────
            () -> {
                vehicle.setAssignedTechnicianId(TECH_5);
                vehicle.setAssignedTechnicianName("Tech Nimal");

                Vehicle freed = vehicleService.assignTechnician(VEHICLE_ID, null);
                assertNull(freed.getAssignedTechnicianId(),
                    "Unassigning must clear the technician");

                Vehicle reassigned = vehicleService.assignTechnician(VEHICLE_ID, TECH_7);
                assertEquals(TECH_7, reassigned.getAssignedTechnicianId(),
                    "Once free, the vehicle must be assignable to technician " + TECH_7);
            }
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-011 — vehicle maintenance status blocks assignment
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void maintenanceStatus_blocksAssignment() {
        when(assignmentRepo.findByTeamLeadIdAndAssignmentDate(any(), any()))
            .thenReturn(Optional.empty());

        assertAll("a vehicle under maintenance cannot be assigned; an available one can",

            // ── Steps 1-3: UNDER_REPAIR (the sheet's UNDER_MAINTENANCE) is refused ───────────
            () -> {
                vehicle.setStatus(Vehicle.VehicleStatus.UNDER_REPAIR);

                RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> vehicleService.assignVehicle(
                        VEHICLE_ID, LEAD_A, "Lead Kamal", 100L, BOD_ODOMETER),
                    "A vehicle under repair must not be assignable");

                assertTrue(thrown.getMessage().contains("not available"),
                    "The refusal must say the vehicle is unavailable; was: " + thrown.getMessage());
                assertTrue(thrown.getMessage().contains("UNDER_REPAIR"),
                    "The refusal must name the blocking status; was: " + thrown.getMessage());
                assertTrue(thrown.getMessage().contains(vehicle.getRegistrationNumber()),
                    "The refusal must name the vehicle; was: " + thrown.getMessage());
            },

            // ── An INACTIVE vehicle is equally unavailable ───────────────────────────────────
            () -> {
                vehicle.setStatus(Vehicle.VehicleStatus.INACTIVE);
                assertThrows(RuntimeException.class,
                    () -> vehicleService.assignVehicle(
                        VEHICLE_ID, LEAD_A, "Lead Kamal", 100L, BOD_ODOMETER),
                    "An INACTIVE vehicle must not be assignable either");
            },

            // ── Step 4: back to AVAILABLE, the assignment succeeds ──────────────────────────
            () -> {
                vehicle.setStatus(Vehicle.VehicleStatus.AVAILABLE);

                VehicleAssignment assignment = vehicleService.assignVehicle(
                    VEHICLE_ID, LEAD_A, "Lead Kamal", 100L, BOD_ODOMETER);

                assertNotNull(assignment, "An available vehicle must assign successfully");
                assertEquals(VEHICLE_ID, assignment.getVehicleId());
                assertEquals(LEAD_A, assignment.getTeamLeadId());
                assertEquals(BOD_ODOMETER, assignment.getBodOdometer().intValue(),
                    "The BOD odometer reading must be recorded on the assignment");
                assertEquals(BOD_ODOMETER, vehicle.getCurrentOdometer().intValue(),
                    "The vehicle's own odometer must be brought up to the BOD reading");
            }
        );
    }
}
