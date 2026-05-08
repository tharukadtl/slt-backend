package lk.slt.fieldops.controller;

import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.service.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/team")
public class TeamController {

    private final JobService jobService;

    public TeamController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/members")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<User>> getTeamMembers(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(jobService.getTeamMembersForTeamLead(userId));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> getTeamStats(@AuthenticationPrincipal Long userId) {
        List<Job> todaysJobs = jobService.getTodaysJobsForTeamLead(userId);
        long inProgress = todaysJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.IN_PROGRESS).count();
        long completed  = todaysJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.COMPLETED).count();
        double completionRate = todaysJobs.isEmpty()
                ? 0.0
                : Math.round((double) completed / todaysJobs.size() * 1000.0) / 10.0;
        return ResponseEntity.ok(Map.of(
            "totalJobs",      todaysJobs.size(),
            "inProgress",     inProgress,
            "completed",      completed,
            "avgTime",        0.0,
            "completionRate", completionRate
        ));
    }
}
