package lk.slt.fieldops.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortestPathRequest {

    @NotNull(message = "Current latitude is required")
    @DecimalMin(value = "-90.0", message = "Current latitude must be >= -90")
    @DecimalMax(value = "90.0", message = "Current latitude must be <= 90")
    private Double currentLat;

    @NotNull(message = "Current longitude is required")
    @DecimalMin(value = "-180.0", message = "Current longitude must be >= -180")
    @DecimalMax(value = "180.0", message = "Current longitude must be <= 180")
    private Double currentLng;

    @NotNull(message = "Fault latitude is required")
    @DecimalMin(value = "-90.0", message = "Fault latitude must be >= -90")
    @DecimalMax(value = "90.0", message = "Fault latitude must be <= 90")
    private Double faultLat;

    @NotNull(message = "Fault longitude is required")
    @DecimalMin(value = "-180.0", message = "Fault longitude must be >= -180")
    @DecimalMax(value = "180.0", message = "Fault longitude must be <= 180")
    private Double faultLng;
}
