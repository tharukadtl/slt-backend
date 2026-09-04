package lk.slt.fieldops.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Mirrors the Flask AI module's POST /api/ai/shortest-path response shape
 * (FR-29, SRS 5.6.6) — this service is a thin proxy, not a re-implementation.
 * {@code routed=false} means the AI module fell back to a Haversine
 * straight-line estimate (no road-network graph data source configured
 * there yet); mobile clients must not present that as a real routed path.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortestPathResponseDTO {

    private List<Waypoint> waypoints;
    private Double distanceKm;
    private Integer etaMinutes;
    private Boolean routed;
    private String algorithm;
    private Double avgSpeedKmh;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Waypoint {
        private Double lat;
        private Double lng;
    }
}
