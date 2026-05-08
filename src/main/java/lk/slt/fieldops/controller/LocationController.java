package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.LocationResponseDTO;
import lk.slt.fieldops.dto.LocationUpdateRequest;
import lk.slt.fieldops.service.LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/location")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @PostMapping("/update")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<LocationResponseDTO> updateLocation(
            @Valid @RequestBody LocationUpdateRequest request,
            @AuthenticationPrincipal Long userId) {
        log.info("POST /api/location/update userId={}, lat={}, lng={}",
                userId, request.getLatitude(), request.getLongitude());
        return ResponseEntity.ok(locationService.updateLocation(userId, request));
    }

    @GetMapping("/technician/{technicianId}")
    @PreAuthorize("hasAnyRole('CLIENT','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<LocationResponseDTO> getTechnicianLocation(
            @PathVariable Long technicianId) {
        log.info("GET /api/location/technician/{}", technicianId);
        return ResponseEntity.ok(locationService.getTechnicianLocation(technicianId));
    }

    @GetMapping("/all-active")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<LocationResponseDTO.LocationSummaryDTO> getAllActiveLocations() {
        log.info("GET /api/location/all-active");
        return ResponseEntity.ok(locationService.getAllActiveLocations());
    }

    @GetMapping("/team/{teamId}")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<LocationResponseDTO.TeamLocationDTO> getTeamLocations(
            @PathVariable Long teamId) {
        log.info("GET /api/location/team/{}", teamId);
        return ResponseEntity.ok(locationService.getTeamLocations(teamId));
    }

    @GetMapping("/nearby")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<LocationResponseDTO.NearbyTechnicianDTO>> getNearbyTechnicians(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "10.0") double radius) {
        log.info("GET /api/location/nearby lat={}, lng={}, radius={}", lat, lng, radius);
        return ResponseEntity.ok(locationService.getNearbyTechnicians(lat, lng, radius));
    }

    @PostMapping("/offline")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Void> markOffline(@AuthenticationPrincipal Long userId) {
        log.info("POST /api/location/offline userId={}", userId);
        locationService.markTechnicianOffline(userId);
        return ResponseEntity.ok().build();
    }
}
