package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.LocationResponseDTO;
import lk.slt.fieldops.dto.LocationUpdateRequest;
import lk.slt.fieldops.entity.TechnicianLocation;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository
        .TechnicianLocationRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.websocket
        .WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation
        .Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final TechnicianLocationRepository
            locationRepository;
    private final UserRepository userRepository;
    private final WebSocketEventPublisher
            webSocketEventPublisher;

    // Active = updated within last 30 minutes
    private static final int ACTIVE_MINUTES = 30;

    // ─── Update Location ──────────────────────────────────

    @Transactional
    public LocationResponseDTO updateLocation(
            Long userId,
            LocationUpdateRequest request) {
        log.debug(
                "Updating location for userId={}: "
                        + "lat={}, lng={}",
                userId,
                request.getLatitude(),
                request.getLongitude());

        User user = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "User not found: "
                                        + userId));

        // Deactivate previous location records
        locationRepository
                .deactivateAllByUserId(userId);

        // Resolve status
        TechnicianLocation.TechnicianStatus status =
                resolveStatus(request.getStatus());

        // Save new location
        TechnicianLocation location =
                TechnicianLocation.builder()
                        .user(user)
                        .latitude(request.getLatitude())
                        .longitude(
                                request.getLongitude())
                        .address(request.getAddress())
                        .speed(request.getSpeed())
                        .heading(request.getHeading())
                        .accuracy(request.getAccuracy())
                        .currentJobId(
                                request.getCurrentJobId())
                        .technicianStatus(status)
                        .isActive(true)
                        .lastUpdated(
                                LocalDateTime.now())
                        .build();

        TechnicianLocation saved =
                locationRepository.save(location);

        // Broadcast via WebSocket to admin portal
        webSocketEventPublisher.publishLocationUpdate(
                userId.toString(),
                request.getLatitude(),
                request.getLongitude(),
                request.getAddress() != null
                        ? request.getAddress()
                        : "");

        log.debug(
                "Location updated and broadcast for "
                        + "userId={}",
                userId);

        return mapToDTO(saved);
    }

    // ─── Get Technician Location ──────────────────────────

    public LocationResponseDTO
    getTechnicianLocation(Long technicianId) {
        log.debug(
                "Getting location for "
                        + "technicianId={}",
                technicianId);

        TechnicianLocation location =
                locationRepository
                        .findActiveByUserId(technicianId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "No active location "
                                                + "for technician: "
                                                + technicianId));

        return mapToDTO(location);
    }

    // ─── Get All Active Locations ─────────────────────────

    public LocationResponseDTO.LocationSummaryDTO
    getAllActiveLocations() {
        log.debug("Getting all active locations");

        LocalDateTime since = LocalDateTime.now()
                .minusMinutes(ACTIVE_MINUTES);

        List<TechnicianLocation> locations =
                locationRepository
                        .findRecentlyActive(since);

        List<LocationResponseDTO> dtos = locations
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());

        // Count by status
        long onJob = locations.stream()
                .filter(l ->
                        TechnicianLocation
                                .TechnicianStatus
                                .ON_JOB
                                .equals(
                                        l.getTechnicianStatus()))
                .count();
        long available = locations.stream()
                .filter(l ->
                        TechnicianLocation
                                .TechnicianStatus
                                .AVAILABLE
                                .equals(
                                        l.getTechnicianStatus()))
                .count();
        long travelling = locations.stream()
                .filter(l ->
                        TechnicianLocation
                                .TechnicianStatus
                                .TRAVELLING
                                .equals(
                                        l.getTechnicianStatus()))
                .count();
        long onBreak = locations.stream()
                .filter(l ->
                        TechnicianLocation
                                .TechnicianStatus
                                .BREAK
                                .equals(
                                        l.getTechnicianStatus()))
                .count();

        return LocationResponseDTO.LocationSummaryDTO
                .builder()
                .totalActive(locations.size())
                .onJob((int) onJob)
                .available((int) available)
                .travelling((int) travelling)
                .onBreak((int) onBreak)
                .offline(0)
                .lastRefreshed(LocalDateTime.now())
                .locations(dtos)
                .build();
    }

    // ─── Get Team Locations ───────────────────────────────

    public LocationResponseDTO.TeamLocationDTO
    getTeamLocations(Long branchId) {
        log.debug(
                "Getting team locations for "
                        + "branchId={}",
                branchId);

        List<TechnicianLocation> locations =
                locationRepository
                        .findActiveByBranchId(branchId);

        List<LocationResponseDTO> dtos = locations
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());

        long activeCount = locations.stream()
                .filter(l ->
                        l.getLastUpdated()
                                .isAfter(
                                        LocalDateTime
                                                .now()
                                                .minusMinutes(
                                                        ACTIVE_MINUTES)))
                .count();

        return LocationResponseDTO.TeamLocationDTO
                .builder()
                .teamId(branchId)
                .teamName("Branch " + branchId)
                .memberCount(locations.size())
                .activeCount((int) activeCount)
                .members(dtos)
                .lastRefreshed(LocalDateTime.now())
                .build();
    }

    // ─── Get Nearby Technicians ───────────────────────────

    public List<LocationResponseDTO
            .NearbyTechnicianDTO>
    getNearbyTechnicians(
            double latitude,
            double longitude,
            double radiusKm) {
        log.debug(
                "Finding nearby technicians: "
                        + "lat={}, lng={}, radius={}km",
                latitude, longitude, radiusKm);

        List<TechnicianLocation> nearby =
                locationRepository.findWithinRadius(
                        latitude, longitude, radiusKm);

        return nearby.stream()
                .map(loc -> {
                    double dist = calculateDistance(
                            latitude, longitude,
                            loc.getLatitude(),
                            loc.getLongitude());

                    int etaMinutes =
                            (int) ((dist / 30.0) * 60);

                    return LocationResponseDTO
                            .NearbyTechnicianDTO
                            .builder()
                            .technicianId(
                                    loc.getUser().getId())
                            .technicianName(
                                    loc.getUser()
                                            .getFullName())
                            .latitude(loc.getLatitude())
                            .longitude(loc.getLongitude())
                            .distanceKm(
                                    Math.round(
                                            dist * 100.0)
                                            / 100.0)
                            .estimatedArrivalMinutes(
                                    etaMinutes + " mins")
                            .status(loc
                                    .getTechnicianStatus()
                                    != null
                                    ? loc.getTechnicianStatus()
                                    .name()
                                    : "UNKNOWN")
                            .currentJobId(
                                    loc.getCurrentJobId())
                            .build();
                })
                .sorted((a, b) ->
                        Double.compare(
                                a.getDistanceKm(),
                                b.getDistanceKm()))
                .collect(Collectors.toList());
    }

    // ─── Mark Offline ─────────────────────────────────────

    @Transactional
    public void markTechnicianOffline(Long userId) {
        log.info(
                "Marking technician offline: "
                        + "userId={}",
                userId);
        locationRepository
                .deactivateAllByUserId(userId);
    }

    // ─── Mapping Helper ───────────────────────────────────

    private LocationResponseDTO mapToDTO(
            TechnicianLocation loc) {
        User user = loc.getUser();
        return LocationResponseDTO.builder()
                .id(loc.getId())
                .userId(user.getId())
                .technicianName(user.getFullName())
                .phone(user.getPhone())
                .role(user.getRole() != null
                        ? user.getRole().name()
                        : null)
                .latitude(loc.getLatitude())
                .longitude(loc.getLongitude())
                .address(loc.getAddress())
                .speed(loc.getSpeed())
                .heading(loc.getHeading())
                .accuracy(loc.getAccuracy())
                .currentJobId(loc.getCurrentJobId())
                .technicianStatus(
                        loc.getTechnicianStatus() != null
                                ? loc.getTechnicianStatus()
                                .name()
                                : null)
                .isActive(loc.getIsActive())
                .lastUpdated(loc.getLastUpdated())
                .createdAt(loc.getCreatedAt())
                .build();
    }

    // ─── Status Resolver ──────────────────────────────────

    private TechnicianLocation.TechnicianStatus
    resolveStatus(String statusStr) {
        if (statusStr == null) {
            return TechnicianLocation
                    .TechnicianStatus.AVAILABLE;
        }
        try {
            return TechnicianLocation.TechnicianStatus
                    .valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TechnicianLocation
                    .TechnicianStatus.AVAILABLE;
        }
    }

    // ─── Haversine Distance ───────────────────────────────

    private double calculateDistance(
            double lat1, double lon1,
            double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a =
                Math.sin(dLat / 2)
                        * Math.sin(dLat / 2)
                        + Math.cos(
                        Math.toRadians(lat1))
                        * Math.cos(
                        Math.toRadians(lat2))
                        * Math.sin(dLon / 2)
                        * Math.sin(dLon / 2);
        double c =
                2 * Math.atan2(
                        Math.sqrt(a),
                        Math.sqrt(1 - a));
        return R * c;
    }
}