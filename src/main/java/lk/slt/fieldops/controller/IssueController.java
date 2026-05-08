package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.CreateIssueRequest;
import lk.slt.fieldops.dto.FaultDTO;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.FaultService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client-facing issue endpoints.
 * Maps mobile payload format ↔ internal FaultService.
 *
 * GET    /api/issues              → client's open issues
 * GET    /api/issues/history      → client's completed/cancelled issues
 * GET    /api/issues/{id}         → single issue
 * POST   /api/issues              → report new issue
 * PATCH  /api/issues/{id}/cancel  → cancel an issue
 */
@RestController
@RequestMapping("/api/issues")
public class IssueController {

    private final FaultService    faultService;
    private final UserRepository  userRepo;

    public IssueController(FaultService faultService, UserRepository userRepo) {
        this.faultService = faultService;
        this.userRepo     = userRepo;
    }

    // ── GET /api/issues ──────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<IssueResponse>> getMyIssues(
            @AuthenticationPrincipal Long userId) {
        List<IssueResponse> issues = faultService.getMyFaults(userId)
                .stream()
                .filter(f -> !"COMPLETED".equals(f.getStatus()) && !"CANCELLED".equals(f.getStatus()))
                .map(this::toIssueResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(issues);
    }

    // ── GET /api/issues/history ───────────────────────────────────────────────
    @GetMapping("/history")
    public ResponseEntity<List<IssueResponse>> getIssueHistory(
            @AuthenticationPrincipal Long userId) {
        List<IssueResponse> history = faultService.getMyFaults(userId)
                .stream()
                .filter(f -> "COMPLETED".equals(f.getStatus()) || "CANCELLED".equals(f.getStatus()))
                .map(this::toIssueResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(history);
    }

    // ── GET /api/issues/{id} ──────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<IssueResponse> getIssueById(
            @PathVariable Long id) {
        return ResponseEntity.ok(toIssueResponse(faultService.getFaultById(id)));
    }

    // ── POST /api/issues ──────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<IssueResponse> createIssue(
            @Valid @RequestBody CreateIssueRequest body,
            @AuthenticationPrincipal Long userId) {

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory(mapCategoryToBackend(body.getCategory()));

        String desc = body.getDescription();
        if (body.getTitle() != null && !body.getTitle().isBlank()) {
            desc = body.getTitle() + ": " + desc;
        }
        req.setDescription(desc);

        if (body.getLocation() != null) {
            req.setLocationAddress(body.getLocation().getAddress());
            req.setLatitude(body.getLocation().getLatitude());
            req.setLongitude(body.getLocation().getLongitude());
        }

        req.setBranchId(user.getBranchId() != null ? user.getBranchId() : 1L);

        String name  = user.getFullName() != null ? user.getFullName() : user.getUsername();
        String phone = user.getPhone() != null ? user.getPhone() : user.getPhoneNumber();

        FaultDTO created = faultService.reportFault(req, userId, name, phone);
        return ResponseEntity.status(HttpStatus.CREATED).body(toIssueResponse(created));
    }

    // ── PUT /api/issues/{id} ──────────────────────────────────────────────────
    @PutMapping("/{id}")
    public ResponseEntity<IssueResponse> updateIssue(
            @PathVariable Long id,
            @Valid @RequestBody CreateIssueRequest body,
            @AuthenticationPrincipal Long userId) {

        String desc = body.getDescription();
        if (body.getTitle() != null && !body.getTitle().isBlank()) {
            desc = body.getTitle() + ": " + desc;
        }
        String locationAddress = body.getLocation() != null ? body.getLocation().getAddress() : null;
        Double latitude        = body.getLocation() != null ? body.getLocation().getLatitude()  : null;
        Double longitude       = body.getLocation() != null ? body.getLocation().getLongitude() : null;

        FaultDTO updated = faultService.updateIssue(id,
                mapCategoryToBackend(body.getCategory()), desc,
                locationAddress, latitude, longitude);
        return ResponseEntity.ok(toIssueResponse(updated));
    }

    // ── PATCH /api/issues/{id}/cancel ─────────────────────────────────────────
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<IssueResponse> cancelIssue(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Long userId) {

        String reason = body != null ? body.get("reason") : "Cancelled by client";
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String name = user.getFullName() != null ? user.getFullName() : user.getUsername();

        FaultDTO cancelled = faultService.cancelFault(id, reason, userId, name,
                FaultHistory.ChangedByRole.CLIENT);
        return ResponseEntity.ok(toIssueResponse(cancelled));
    }

    // ── GET /api/issues/{id}/technician-location ──────────────────────────────
    @GetMapping("/{id}/technician-location")
    public ResponseEntity<Map<String, Object>> getTechnicianLocation(@PathVariable Long id) {
        FaultDTO fault = faultService.getFaultById(id);
        if (fault.getAssignedTeamLeadId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No technician assigned yet"));
        }
        // Real-time location not implemented yet — return stub
        return ResponseEntity.ok(Map.of(
                "technicianId",   fault.getAssignedTeamLeadId().toString(),
                "technicianName", fault.getAssignedTeamLeadName() != null
                                      ? fault.getAssignedTeamLeadName() : "Technician",
                "latitude",       6.9271,
                "longitude",      79.8612,
                "eta",            30,
                "distance",       "On the way",
                "lastUpdated",    java.time.LocalDateTime.now().toString()
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IssueResponse toIssueResponse(FaultDTO f) {
        IssueResponse r = new IssueResponse();
        r.id          = f.getId() != null ? f.getId().toString() : null;
        r.faultNumber = f.getFaultNumber();
        r.title       = deriveTitle(f);
        r.description = f.getDescription();
        r.category    = mapCategoryToMobile(f.getCategory());
        r.status      = mapStatusToMobile(f.getStatus());
        r.clientId    = f.getCustomerId() != null ? f.getCustomerId().toString() : null;
        r.technicianId = f.getAssignedTeamLeadId() != null
                          ? f.getAssignedTeamLeadId().toString() : null;

        IssueResponse.LocationResponse loc = new IssueResponse.LocationResponse();
        loc.address   = f.getLocationAddress();
        loc.latitude  = f.getLatitude();
        loc.longitude = f.getLongitude();
        r.location    = loc;

        r.createdAt   = f.getReportedAt()  != null ? f.getReportedAt().toString()  : null;
        r.updatedAt   = f.getUpdatedAt()   != null ? f.getUpdatedAt().toString()   : null;
        r.completedAt = f.getCompletedAt() != null ? f.getCompletedAt().toString() : null;
        return r;
    }

    private String deriveTitle(FaultDTO f) {
        String desc = f.getDescription();
        if (desc != null && desc.contains(": ")) {
            return desc.substring(0, desc.indexOf(": "));
        }
        String cat = mapCategoryToMobile(f.getCategory());
        return cat != null ? cat.substring(0, 1).toUpperCase() + cat.substring(1) + " Issue" : "Issue";
    }

    private String mapCategoryToBackend(String mobile) {
        if (mobile == null) return "OTHER";
        return switch (mobile.toLowerCase()) {
            case "broadband", "internet", "fiber" -> "INTERNET";
            case "telephone", "phone"             -> "PHONE";
            case "television", "tv", "peotv"      -> "TV";
            default                               -> "OTHER";
        };
    }

    private String mapCategoryToMobile(String backend) {
        if (backend == null) return "other";
        return switch (backend.toUpperCase()) {
            case "INTERNET" -> "broadband";
            case "PHONE"    -> "telephone";
            case "TV"       -> "television";
            default         -> "other";
        };
    }

    private String mapStatusToMobile(String backend) {
        if (backend == null) return "pending";
        return switch (backend.toUpperCase()) {
            case "REPORTED"    -> "pending";
            case "ASSIGNED"    -> "assigned";
            case "IN_PROGRESS",
                 "HOLD"        -> "in_progress";
            case "COMPLETED"   -> "completed";
            case "CANCELLED"   -> "cancelled";
            default            -> "pending";
        };
    }

    // ── Response DTO (matches mobile Issue interface) ─────────────────────────

    static class IssueResponse {
        public String id;
        public String faultNumber;
        public String title;
        public String description;
        public String category;
        public String status;
        public String clientId;
        public String technicianId;
        public String[] photos;
        public LocationResponse location;
        public String createdAt;
        public String updatedAt;
        public String completedAt;

        static class LocationResponse {
            public String address;
            public Double latitude;
            public Double longitude;
        }
    }
}
