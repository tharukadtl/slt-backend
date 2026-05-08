package lk.slt.fieldops.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationResponseDTO {

    // ─── Single Location ──────────────────────────────────
    private Long id;
    private Long userId;
    private String technicianName;
    private String phone;
    private String role;

    private Double latitude;
    private Double longitude;
    private String address;

    private Double speed;
    private Double heading;
    private Double accuracy;

    private String currentJobId;
    private String technicianStatus;
    private Boolean isActive;

    private LocalDateTime lastUpdated;
    private LocalDateTime createdAt;

    // ─── Distance (calculated on request) ────────────────
    private Double distanceKm;
    private String estimatedArrival;

    // ─── Summary (for all-active endpoint) ───────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationSummaryDTO {
        private int totalActive;
        private int onJob;
        private int available;
        private int travelling;
        private int onBreak;
        private int offline;
        private LocalDateTime lastRefreshed;
        private List<LocationResponseDTO> locations;
    }

    // ─── Team Location ────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamLocationDTO {
        private Long teamId;
        private String teamName;
        private int memberCount;
        private int activeCount;
        private List<LocationResponseDTO> members;
        private LocalDateTime lastRefreshed;
    }

    // ─── Nearby Technician ────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NearbyTechnicianDTO {
        private Long technicianId;
        private String technicianName;
        private Double latitude;
        private Double longitude;
        private Double distanceKm;
        private String estimatedArrivalMinutes;
        private String status;
        private String currentJobId;
    }
}