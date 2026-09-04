package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotNull;
import lk.slt.fieldops.entity.Vehicle;

/**
 * SetVehicleStatusRequest — body for PATCH /api/vehicles/{id}/status
 * {
 *   "status": "IN_USE"   // AVAILABLE | IN_USE | UNDER_REPAIR | INACTIVE
 * }
 */
public class SetVehicleStatusRequest {

    @NotNull(message = "status is required")
    private Vehicle.VehicleStatus status;

    public SetVehicleStatusRequest() {}

    public Vehicle.VehicleStatus getStatus() { return status; }

    public void setStatus(Vehicle.VehicleStatus v) { this.status = v; }
}
