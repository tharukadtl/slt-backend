package lk.slt.fieldops.vehicle.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.vehicle.dto.CreateVehicleRequest;
import lk.slt.fieldops.vehicle.entity.Vehicle;
import lk.slt.fieldops.vehicle.entity.VehicleAssignment;
import lk.slt.fieldops.vehicle.service.VehicleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * VehicleController — REST API for Vehicle management.
 *
 * BASE URL: /api/vehicles
 *
 * VEHICLE CRUD
 *   POST   /api/vehicles                   Create vehicle
 *   GET    /api/vehicles                   List (filter by branchId)
 *   GET    /api/vehicles/{id}              Get one
 *   PUT    /api/vehicles/{id}              Update vehicle
 *   PATCH  /api/vehicles/{id}/status       Set status (ACTIVE/INACTIVE/UNDER_MAINTENANCE)
 *
 * ASSIGNMENT
 *   GET    /api/vehicles/assignment/today  TL: get today's vehicle assignment
 *   GET    /api/vehicles/{id}/assignments  Get assignment history for a vehicle
 *
 * ALERTS
 *   GET    /api/vehicles/alerts            Expiring within 30 days
 *   GET    /api/vehicles/alerts/expired    Already expired
 *   GET    /api/vehicles/alerts/summary    Dashboard summary
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    // ── CREATE VEHICLE ────────────────────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Vehicle> createVehicle(
            @Valid @RequestBody CreateVehicleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(vehicleService.createVehicle(request));
    }

    // ── LIST VEHICLES ─────────────────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<Vehicle>> getVehicles(
            @RequestParam(required = false) Long    branchId,
            @RequestParam(required = false) Boolean activeOnly) {

        if (branchId != null && Boolean.TRUE.equals(activeOnly)) {
            return ResponseEntity.ok(vehicleService.getActiveByBranch(branchId));
        }
        if (branchId != null) {
            return ResponseEntity.ok(vehicleService.getByBranch(branchId));
        }
        // Return all — super admin only
        return ResponseEntity.ok(vehicleService.getByBranch(null));
    }

    // ── GET ONE VEHICLE ───────────────────────────────────────────────────────
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','TEAM_LEAD')")
    public ResponseEntity<Vehicle> getById(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.getById(id));
    }

    // ── UPDATE VEHICLE ────────────────────────────────────────────────────────
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Vehicle> updateVehicle(
            @PathVariable Long id,
            @Valid @RequestBody CreateVehicleRequest request) {
        return ResponseEntity.ok(vehicleService.updateVehicle(id, request));
    }

    // ── SET STATUS ────────────────────────────────────────────────────────────
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Vehicle> setStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        return ResponseEntity.ok(vehicleService.setStatus(id, status));
    }

    // ── TODAY'S ASSIGNMENT (Team Lead) ────────────────────────────────────────
    @GetMapping("/assignment/today")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<VehicleAssignment> getTodaysAssignment(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(vehicleService.getTodaysAssignment(userId));
    }

    // ── ASSIGNMENT HISTORY for a Vehicle ──────────────────────────────────────
    @GetMapping("/{id}/assignments")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<VehicleAssignment>> getAssignmentHistory(
            @PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.getAssignmentHistory(id));
    }

    // ── EXPIRY ALERTS (within 30 days) ───────────────────────────────────────
    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<Vehicle>> getExpiryAlerts() {
        return ResponseEntity.ok(vehicleService.getExpiryAlerts());
    }

    // ── ALREADY EXPIRED ───────────────────────────────────────────────────────
    @GetMapping("/alerts/expired")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<Vehicle>> getExpiredDocuments() {
        return ResponseEntity.ok(vehicleService.getExpiredDocuments());
    }

    // ── ALERT SUMMARY (dashboard widget) ─────────────────────────────────────
    @GetMapping("/alerts/summary")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> getAlertSummary() {
        return ResponseEntity.ok(vehicleService.getAlertSummary());
    }
}
