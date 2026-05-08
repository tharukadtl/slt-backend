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
public class LocationUpdateRequest {

    @NotNull(message = "Latitude is required")
    @DecimalMin(value = "-90.0",
            message = "Latitude must be >= -90")
    @DecimalMax(value = "90.0",
            message = "Latitude must be <= 90")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    @DecimalMin(value = "-180.0",
            message = "Longitude must be >= -180")
    @DecimalMax(value = "180.0",
            message = "Longitude must be <= 180")
    private Double longitude;

    // Optional fields
    private String address;
    private Double speed;
    private Double heading;
    private Double accuracy;
    private String currentJobId;
    private String status;
}