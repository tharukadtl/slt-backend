package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.*;
import lk.slt.fieldops.entity.*;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * JobController — 16 endpoints for the BOD/EOD/Job workflow.
 *
 * BASE URL: /api/jobs
 *
 * ── BOD / EOD ─────────────────────────────────────────────────────────────────
 * POST  /api/jobs/bod                    Team Lead does BOD
 * POST  /api/jobs/eod                    Team Lead does EOD
 * GET   /api/jobs/session                Get today's active session
 * GET   /api/jobs/session/{id}/members   Get technicians in a session
 *
 * ── JOB MANAGEMENT ────────────────────────────────────────────────────────────
 * POST  /api/jobs                        Team Lead creates job (assigns fault to tech)
 * GET   /api/jobs/today                  Get today's jobs (TL or Technician)
 * GET   /api/jobs/my                     Technician: get my jobs today
 * GET   /api/jobs/{id}                   Get one job by ID
 * PATCH /api/jobs/{id}/status            Update job status (ACCEPTED/IN_PROGRESS/HOLD/COMPLETED)
 * POST  /api/jobs/{id}/reassign          Team Lead reassigns a job to another Technician
 *
 * ── MATERIAL USAGE ────────────────────────────────────────────────────────────
 * POST  /api/jobs/{id}/materials         Technician logs material used
 * GET   /api/jobs/{id}/materials         Get all materials for a job
 *
 * ── UTILITIES ─────────────────────────────────────────────────────────────────
 * GET   /api/jobs/technician/{id}/active Check if Technician is in active session
 * GET   /api/jobs/{id}/checkinout        Get check-in/out records for a session
 * GET   /api/jobs/summary                Team Lead dashboard job summary
 * GET   /api/jobs/{id}/cancel            Cancel a job
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService     jobService;
    private final UserRepository userRepo;

    public JobController(JobService jobService, UserRepository userRepo) {
        this.jobService = jobService;
        this.userRepo   = userRepo;
    }

    private String resolveFullName(Long userId) {
        return userRepo.findById(userId)
            .map(User::getFullName)
            .orElse("User #" + userId);
    }

    // ── 1. Team Lead does BOD ─────────────────────────────────────────────────
    /**
     * POST /api/jobs/bod
     * Team Lead selects vehicle + technicians to start the day.
     * After this, listed technicians can log in.
     */
    @PostMapping("/bod")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<DaySession> performBod(
            @Valid @RequestBody BodRequest request,
            @AuthenticationPrincipal Long userId) {

        String teamLeadName = resolveFullName(userId);  // TODO Phase 3: load from User
        DaySession session = jobService.performBod(request, userId, teamLeadName);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    // ── 2. Team Lead does EOD ─────────────────────────────────────────────────
    /**
     * POST /api/jobs/eod
     * Closes the session, returns all open jobs to TL, logs out technicians.
     */
    @PostMapping("/eod")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<Map<String, Object>> performEod(
            @Valid @RequestBody EodRequest request,
            @AuthenticationPrincipal Long userId) {

        String teamLeadName = resolveFullName(userId);
        Map<String, Object> result = jobService.performEod(request, userId, teamLeadName);
        return ResponseEntity.ok(result);
    }

    // ── 3. Get today's active session ─────────────────────────────────────────
    @GetMapping("/session")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<DaySession> getTodaysSession(
            @AuthenticationPrincipal Long userId) {

        return ResponseEntity.ok(jobService.getTodaysSession(userId));
    }

    // ── 4. Get technicians in a session ───────────────────────────────────────
    @GetMapping("/session/{sessionId}/members")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<DaySessionMember>> getSessionMembers(
            @PathVariable Long sessionId) {

        return ResponseEntity.ok(jobService.getSessionMembers(sessionId));
    }

    // ── Admin: get ALL jobs ───────────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<Job>> getAllJobs() {
        return ResponseEntity.ok(jobService.getAllJobs());
    }

    // ── 5. Create job (Team Lead assigns fault to Technician) ─────────────────
    /**
     * POST /api/jobs
     * Body: { "faultId": 12, "technicianId": 8 }
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<Job> createJob(
            @Valid @RequestBody CreateJobRequest request,
            @AuthenticationPrincipal Long userId) {

        String teamLeadName = resolveFullName(userId);
        Job created = jobService.createJob(request, userId, teamLeadName);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── 6. Get today's jobs (Team Lead view — all jobs in session) ─────────────
    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<List<Job>> getTodaysJobs(
            @AuthenticationPrincipal Long userId) {

        return ResponseEntity.ok(jobService.getTodaysJobsForTeamLead(userId));
    }

    // ── 7. Technician: get MY jobs today ──────────────────────────────────────
    @GetMapping("/my")
    @PreAuthorize("hasRole('TECHNICIAN')")
    public ResponseEntity<List<Job>> getMyJobs(
            @AuthenticationPrincipal Long userId) {

        return ResponseEntity.ok(jobService.getTodaysJobsForTechnician(userId));
    }

    // ── 8. Get one job by ID ──────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Job> getById(@PathVariable Long id) {
        return ResponseEntity.ok(jobService.getJobById(id));
    }

    // ── 9. Technician updates job status ──────────────────────────────────────
    /**
     * PATCH /api/jobs/{id}/status
     * Body: { "newStatus": "ACCEPTED" }
     *       { "newStatus": "IN_PROGRESS" }
     *       { "newStatus": "HOLD", "reason": "Missing cable" }
     *       { "newStatus": "COMPLETED", "causeOfFault": "...", "completionRemarks": "..." }
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN')")
    public ResponseEntity<Job> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateJobRequest request,
            @AuthenticationPrincipal Long userId) {

        return ResponseEntity.ok(jobService.updateJobStatus(id, request, userId));
    }

    // ── 10. Reassign job to another Technician ────────────────────────────────
    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<Job> reassign(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body,
            @AuthenticationPrincipal Long userId) {

        Long newTechnicianId = body.get("newTechnicianId");
        if (newTechnicianId == null) {
            throw new RuntimeException("newTechnicianId is required in the request body.");
        }
        return ResponseEntity.ok(jobService.reassignJob(id, newTechnicianId, userId));
    }

    // ── 11. Log material usage ────────────────────────────────────────────────
    /**
     * POST /api/jobs/{id}/materials
     * Body: { "materialId": 5, "quantityUsed": 10.5, "chargeType": "FOC" }
     */
    @PostMapping("/{id}/materials")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD')")
    public ResponseEntity<MaterialUsage> logMaterial(
            @PathVariable Long id,
            @Valid @RequestBody MaterialUsageRequest request,
            @AuthenticationPrincipal Long userId) {

        MaterialUsage usage = jobService.logMaterial(id, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(usage);
    }

    // ── 12. Get materials logged for a job ────────────────────────────────────
    @GetMapping("/{id}/materials")
    public ResponseEntity<List<MaterialUsage>> getMaterials(@PathVariable Long id) {
        return ResponseEntity.ok(jobService.getMaterialsForJob(id));
    }

    // ── 13. Check if Technician is active today ───────────────────────────────
    /**
     * GET /api/jobs/technician/{id}/active
     * Returns true if technician is in an active BOD session today.
     * Used by Auth module to gate Technician login.
     */
    @GetMapping("/technician/{techId}/active")
    public ResponseEntity<Map<String, Object>> isTechnicianActive(
            @PathVariable Long techId) {

        boolean active = jobService.isTechnicianActiveToday(techId);
        return ResponseEntity.ok(Map.of(
            "technicianId", techId,
            "activeToday",  active,
            "message",      active
                ? "Technician is in an active session today."
                : "Technician is NOT in any active session today. Team Lead must add them at BOD."
        ));
    }

    // ── 14. Get check-in/out records for a session ────────────────────────────
    @GetMapping("/session/{sessionId}/checkinout")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<CheckInOut>> getCheckInOut(
            @PathVariable Long sessionId) {

        return ResponseEntity.ok(jobService.getCheckInOutForSession(sessionId));
    }

    // ── 14b. Technician checkout status for EOD screen ───────────────────────
    @GetMapping("/session/{sessionId}/technician-status")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<lk.slt.fieldops.dto.TechnicianCheckoutStatusDTO>> getTechnicianCheckoutStatus(
            @PathVariable Long sessionId) {

        return ResponseEntity.ok(jobService.getTechnicianCheckoutStatus(sessionId));
    }

    // ── 15. Team Lead dashboard summary ──────────────────────────────────────
    /**
     * GET /api/jobs/summary
     * Returns count of pending/in-progress/completed jobs for today.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<Map<String, Object>> getSummary(
            @AuthenticationPrincipal Long userId) {

        List<Job> todaysJobs = jobService.getTodaysJobsForTeamLead(userId);

        long pending    = todaysJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.PENDING).count();
        long accepted   = todaysJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.ACCEPTED).count();
        long inProgress = todaysJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.IN_PROGRESS).count();
        long onHold     = todaysJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.HOLD).count();
        long completed  = todaysJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.COMPLETED).count();

        return ResponseEntity.ok(Map.of(
            "totalToday",  todaysJobs.size(),
            "pending",     pending,
            "accepted",    accepted,
            "inProgress",  inProgress,
            "onHold",      onHold,
            "completed",   completed
        ));
    }

    // ── 16. Cancel a job ──────────────────────────────────────────────────────
    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ResponseEntity<Job> cancelJob(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId) {

        UpdateJobRequest req = new UpdateJobRequest();
        req.setNewStatus("CANCELLED");
        req.setReason("Cancelled by Team Lead");
        return ResponseEntity.ok(jobService.updateJobStatus(id, req, userId));
    }

    // ── 17. Store completion signature ────────────────────────────────────────
    @PostMapping("/{id}/signature")
    @PreAuthorize("hasAnyRole('TECHNICIAN','TEAM_LEAD','ADMIN')")
    public ResponseEntity<Job> submitSignature(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long userId) {

        String signature = body.get("signature");
        if (signature == null || signature.isBlank()) {
            throw new RuntimeException("signature is required in the request body.");
        }
        return ResponseEntity.ok(jobService.submitSignature(id, signature, userId));
    }
}
