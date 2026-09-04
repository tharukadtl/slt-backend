package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.LocationResponseDTO;
import lk.slt.fieldops.dto.LocationUpdateRequest;
import lk.slt.fieldops.dto.ShortestPathRequest;
import lk.slt.fieldops.dto.ShortestPathResponseDTO;
import lk.slt.fieldops.entity.TechnicianLocation;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository
        .TechnicianLocationRepository;
import lk.slt.fieldops.repository.DaySessionMemberRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.websocket
        .WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation
        .Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final TechnicianLocationRepository
            locationRepository;
    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final DaySessionMemberRepository daySessionMemberRepository;
    private final WebSocketEventPublisher
            webSocketEventPublisher;
    private final RestTemplate restTemplate;

    /** Used to call the Flask AI module for FR-29 shortest-path navigation — same property ReportService uses. */
    @Value("${app.ai.base-url:http://localhost:5000}")
    private String aiBaseUrl;

    // Active = updated within last 30 minutes
    private static final int ACTIVE_MINUTES = 30;

    // Sri Lanka bounding box (FAULT-018) — mirrors slt-ai-module's Config.SL_LAT_MIN/MAX
    // and SL_LNG_MIN/MAX (utils/validators.py), the only other place these bounds are
    // enforced. LocationUpdateRequest's @DecimalMin/@DecimalMax are global -90..90/-180..180
    // and don't reject foreign coordinates, so a technician ping is checked again here.
    private static final double SL_LAT_MIN = 5.9;
    private static final double SL_LAT_MAX = 9.9;
    private static final double SL_LNG_MIN = 79.5;
    private static final double SL_LNG_MAX = 81.9;

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

        validateSriLankaCoords(
                request.getLatitude(), request.getLongitude());

        User user = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "User not found: "
                                        + userId));

        // Deactivate previous location records. Defensive only: from here on there is at
        // most one row per technician, but dev data can still hold duplicates created before
        // this method became an upsert, and every read path (findActiveByUserId) assumes a
        // single active row per technician.
        locationRepository
                .deactivateAllByUserId(userId);

        // Resolve status
        TechnicianLocation.TechnicianStatus status =
                resolveStatus(request.getStatus());

        // Upsert: reuse the technician's existing row if there is one, otherwise create it,
        // so repeated GPS pings update in place instead of growing the table forever.
        TechnicianLocation location = locationRepository
                .findFirstByUser_IdOrderByLastUpdatedDesc(
                        userId)
                .orElseGet(() ->
                        TechnicianLocation.builder()
                                .user(user)
                                .build());

        location.setUser(user);
        location.setLatitude(request.getLatitude());
        location.setLongitude(request.getLongitude());
        location.setAddress(request.getAddress());
        location.setSpeed(request.getSpeed());
        location.setHeading(request.getHeading());
        location.setAccuracy(request.getAccuracy());
        location.setCurrentJobId(
                request.getCurrentJobId());
        location.setTechnicianStatus(status);
        location.setIsActive(true);
        location.setLastUpdated(LocalDateTime.now());

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

    // Rejects missing or out-of-Sri-Lanka coordinates before anything is persisted or
    // broadcast, instead of letting a null unbox into an NPE downstream (the previous
    // behaviour) or storing a foreign position as a valid technician location.
    private void validateSriLankaCoords(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new RuntimeException(
                    "Latitude and longitude are required for a location update.");
        }
        if (latitude < SL_LAT_MIN || latitude > SL_LAT_MAX
                || longitude < SL_LNG_MIN || longitude > SL_LNG_MAX) {
            throw new RuntimeException(
                    "Location (" + latitude + ", " + longitude
                            + ") is outside Sri Lanka bounds (lat "
                            + SL_LAT_MIN + ".." + SL_LAT_MAX + ", lng "
                            + SL_LNG_MIN + ".." + SL_LNG_MAX + ").");
        }
    }

    // ─── Get Technician Location ──────────────────────────

    public LocationResponseDTO
    getTechnicianLocation(Long callerId, Long technicianId) {
        log.debug(
                "Getting location for "
                        + "technicianId={}, requested by callerId={}",
                technicianId, callerId);

        assertCanViewTechnicianLocation(callerId, technicianId);

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

    // Same as getTechnicianLocation, but also fills in distanceKm/estimatedArrival
    // against a target point (e.g. the fault/job site) using the same
    // haversine + 30km/h average-speed formula used for getNearbyTechnicians.
    public LocationResponseDTO getTechnicianLocationWithEta(
            Long callerId, Long technicianId, double targetLat, double targetLng) {
        LocationResponseDTO dto = getTechnicianLocation(callerId, technicianId);
        if (dto.getLatitude() != null && dto.getLongitude() != null) {
            double dist = lk.slt.fieldops.shared.GeoUtils.haversineKm(dto.getLatitude(), dto.getLongitude(), targetLat, targetLng);
            int etaMinutes = (int) ((dist / 30.0) * 60);
            dto.setDistanceKm(Math.round(dist * 100.0) / 100.0);
            dto.setEstimatedArrival(etaMinutes + " mins");
        }
        return dto;
    }

    // Guard: a caller may only view a technician's live location if they're
    // an admin, the technician's own team lead, or a client with an active
    // job assigned to that technician — not any authenticated user by ID.
    private void assertCanViewTechnicianLocation(Long callerId, Long technicianId) {
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new RuntimeException("User not found: " + callerId));
        User.Role role = caller.getRole();

        if (role == User.Role.ADMIN || role == User.Role.SUPER_ADMIN) {
            return;
        }
        if (role == User.Role.TEAM_LEAD) {
            daySessionMemberRepository.findActiveMemberForTeamLeadToday(callerId, technicianId)
                    .orElseThrow(() -> new RuntimeException(
                            "Technician #" + technicianId + " is not on your team's active session today."));
            return;
        }
        if (role == User.Role.CLIENT) {
            if (!jobRepository.existsActiveJobForCustomerAndTechnician(callerId, technicianId)) {
                throw new RuntimeException(
                        "Technician #" + technicianId + " is not assigned to any of your active jobs.");
            }
            return;
        }
        throw new RuntimeException("You are not authorized to view this technician's location.");
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
    getTeamLocations(Long opmcId) {
        log.debug(
                "Getting team locations for "
                        + "opmcId={}",
                opmcId);

        List<TechnicianLocation> locations =
                locationRepository
                        .findActiveByOpmcId(opmcId);

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
                .teamId(opmcId)
                .teamName("OPMC " + opmcId)
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
                    double dist = lk.slt.fieldops.shared.GeoUtils.haversineKm(
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

    // ─── Shortest-Path Navigation (FR-29, SRS 5.6.6) ──────
    //
    // Thin proxy to the Flask AI module's POST /api/ai/shortest-path —
    // same aiBaseUrl + RestTemplate pattern ReportService already uses
    // for /api/ai/predictions and /api/ai/clusters. The mobile apps have
    // no direct network path to the AI module (only to this backend), so
    // this exists purely to make that endpoint reachable from mobile via
    // the connection it already has.

    @SuppressWarnings("unchecked")
    public ShortestPathResponseDTO getShortestPathToFault(ShortestPathRequest request) {
        String url = aiBaseUrl + "/api/ai/shortest-path";
        Map<String, Object> body = Map.of(
                "currentLat", request.getCurrentLat(),
                "currentLng", request.getCurrentLng(),
                "faultLat",   request.getFaultLat(),
                "faultLng",   request.getFaultLng()
        );

        Map<String, Object> resp;
        try {
            resp = restTemplate.postForObject(url, body, Map.class);
        } catch (HttpClientErrorException e) {
            // AI module validated the request and rejected it (e.g. out-of-bounds
            // coords) — surface its message rather than a generic failure.
            throw new RuntimeException(extractAiErrorMessage(e));
        } catch (RestClientException e) {
            log.warn("AI module unavailable for shortest-path: {}", e.getMessage());
            throw new RuntimeException("Shortest-path service is temporarily unavailable.");
        }

        Object data = resp != null ? resp.get("data") : null;
        if (!(data instanceof Map)) {
            throw new RuntimeException("AI module returned an unexpected response.");
        }
        Map<String, Object> d = (Map<String, Object>) data;

        List<Map<String, Object>> rawWaypoints = (List<Map<String, Object>>) d.get("waypoints");
        List<ShortestPathResponseDTO.Waypoint> waypoints = rawWaypoints == null
                ? List.of()
                : rawWaypoints.stream()
                        .map(w -> ShortestPathResponseDTO.Waypoint.builder()
                                .lat(((Number) w.get("lat")).doubleValue())
                                .lng(((Number) w.get("lng")).doubleValue())
                                .build())
                        .collect(Collectors.toList());

        return ShortestPathResponseDTO.builder()
                .waypoints(waypoints)
                .distanceKm(d.get("distanceKm") != null ? ((Number) d.get("distanceKm")).doubleValue() : null)
                .etaMinutes(d.get("etaMinutes") != null ? ((Number) d.get("etaMinutes")).intValue() : null)
                .routed(Boolean.TRUE.equals(d.get("routed")))
                .algorithm(d.get("algorithm") != null ? d.get("algorithm").toString() : null)
                .avgSpeedKmh(d.get("avgSpeedKmh") != null ? ((Number) d.get("avgSpeedKmh")).doubleValue() : null)
                .build();
    }

    @SuppressWarnings("unchecked")
    private String extractAiErrorMessage(HttpClientErrorException e) {
        try {
            Map<String, Object> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("error") != null) {
                return body.get("error").toString();
            }
        } catch (Exception ignored) {
            // Fall through to generic message below.
        }
        return "Invalid location data.";
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

}