package lk.slt.fieldops.service;

import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.dto.KpiScoreDTO;
import lk.slt.fieldops.dto.KpiSummaryDTO;
import lk.slt.fieldops.dto.KpiTargetRequest;
import lk.slt.fieldops.entity.KpiScore;
import lk.slt.fieldops.entity.KpiTarget;
import lk.slt.fieldops.repository.KpiScoreRepository;
import lk.slt.fieldops.repository.KpiTargetRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * KpiService — KPI calculations with nightly scheduled task.
 *
 * IMPORTANT: Add @EnableScheduling to your Application.java
 *
 * Methods:
 *   calculateDailyKpis()         → @Scheduled at 01:00 AM — auto-runs nightly
 *   getScoresForTechnician()     → Full history for a technician
 *   getLeaderboard()             → Top technicians for a given date
 *   getScoresInRange()           → Date range query
 *   getBranchScores()            → Branch performance on a date
 *   getSummary()                 → Aggregated summary with KpiSummaryDTO
 *   setTarget()                  → Admin saves KPI targets
 *   mapToDTO()                   → KpiScore → KpiScoreDTO
 */
@Service
public class KpiService {

    private static final Logger log = Logger.getLogger(KpiService.class.getName());

    private final KpiScoreRepository  scoreRepo;
    private final KpiTargetRepository targetRepo;
    private final JobRepository       jobRepo;

    public KpiService(KpiScoreRepository scoreRepo,
                      KpiTargetRepository targetRepo,
                      JobRepository jobRepo) {
        this.scoreRepo  = scoreRepo;
        this.targetRepo = targetRepo;
        this.jobRepo    = jobRepo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NIGHTLY SCHEDULER — runs at 01:00 AM every day
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional
    public void calculateDailyKpis() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("=== KPI SCHEDULER START: " + yesterday + " ===");

        List<Job> allJobs = jobRepo.findAll().stream()
            .filter(j -> yesterday.equals(j.getScheduledDate()))
            .toList();

        if (allJobs.isEmpty()) {
            log.info("No jobs for " + yesterday + " — skipping.");
            return;
        }

        allJobs.stream()
            .filter(j -> j.getTechnicianId() != null)
            .map(Job::getTechnicianId)
            .distinct()
            .forEach(techId -> calculateForTechnician(techId, yesterday, allJobs));

        log.info("=== KPI SCHEDULER COMPLETE ===");
    }

    private void calculateForTechnician(Long techId, LocalDate date, List<Job> allJobs) {
        if (scoreRepo.findByTechnicianIdAndScoreDate(techId, date).isPresent()) {
            return; // Idempotent — safe to re-run
        }

        List<Job> myJobs = allJobs.stream()
            .filter(j -> techId.equals(j.getTechnicianId()))
            .toList();

        if (myJobs.isEmpty()) return;

        long assigned  = myJobs.size();
        long completed = myJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.COMPLETED).count();
        long onHold    = myJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.HOLD).count();
        long cancelled = myJobs.stream().filter(j -> j.getStatus() == Job.JobStatus.CANCELLED).count();

        BigDecimal slaCompliance = assigned > 0
            ? BigDecimal.valueOf(completed * 100.0 / assigned).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        BigDecimal completionRate = assigned > 0
            ? BigDecimal.valueOf(completed * 100.0 / assigned)
            : BigDecimal.ZERO;

        // Overall = 70% completion rate + 30% SLA compliance
        BigDecimal overall = completionRate.multiply(BigDecimal.valueOf(0.70))
            .add(slaCompliance.multiply(BigDecimal.valueOf(0.30)))
            .setScale(2, RoundingMode.HALF_UP);

        KpiScore score = new KpiScore();
        score.setScoreDate(date);
        score.setScoredFor(KpiScore.ScoredFor.TECHNICIAN);
        score.setTechnicianId(techId);
        score.setTechnicianName(myJobs.get(0).getTechnicianName());
        score.setJobsAssigned((int) assigned);
        score.setJobsCompleted((int) completed);
        score.setJobsOnHold((int) onHold);
        score.setJobsCancelled((int) cancelled);
        score.setSlaCompliancePercent(slaCompliance);
        score.setOverallScore(overall.doubleValue());

        scoreRepo.save(score);
        log.info("KPI saved: tech=" + techId + " date=" + date + " score=" + overall);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // READ METHODS — return DTOs, not entities
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<KpiScoreDTO> getScoresForTechnician(Long techId) {
        return scoreRepo.findByTechnicianIdOrderByScoreDateDesc(techId)
            .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KpiScoreDTO> getLeaderboard(LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now().minusDays(1);
        return scoreRepo.findLeaderboardForDate(target)
            .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KpiScoreDTO> getScoresInRange(Long techId, LocalDate from, LocalDate to) {
        return scoreRepo.findByTechnicianAndDateRange(techId, from, to)
            .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KpiScoreDTO> getBranchScores(Long branchId, LocalDate date) {
        return scoreRepo.findByBranchIdAndScoreDate(branchId, date)
            .stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * Aggregated summary for a technician over a date range.
     * Returns KpiSummaryDTO with totals + daily breakdown.
     */
    @Transactional(readOnly = true)
    public KpiSummaryDTO getSummary(Long techId, LocalDate from, LocalDate to) {
        List<KpiScore> scores = scoreRepo.findByTechnicianAndDateRange(techId, from, to);

        int totalAssigned  = scores.stream().mapToInt(s -> s.getJobsAssigned()  != null ? s.getJobsAssigned()  : 0).sum();
        int totalCompleted = scores.stream().mapToInt(s -> s.getJobsCompleted() != null ? s.getJobsCompleted() : 0).sum();
        int totalOnHold    = scores.stream().mapToInt(s -> s.getJobsOnHold()    != null ? s.getJobsOnHold()    : 0).sum();
        int totalCancelled = scores.stream().mapToInt(s -> s.getJobsCancelled() != null ? s.getJobsCancelled() : 0).sum();

        double avgScoreRaw = scores.stream()
            .filter(s -> s.getOverallScore() != null)
            .mapToDouble(KpiScore::getOverallScore)
            .average()
            .orElse(0.0);
        BigDecimal avgScore = BigDecimal.valueOf(avgScoreRaw).setScale(2, RoundingMode.HALF_UP);

        BigDecimal completionRate = totalAssigned > 0
            ? BigDecimal.valueOf(totalCompleted * 100.0 / totalAssigned).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        KpiSummaryDTO summary = new KpiSummaryDTO();
        summary.setTechnicianId(techId);
        summary.setPeriodFrom(from);
        summary.setPeriodTo(to);
        summary.setTotalJobsAssigned(totalAssigned);
        summary.setTotalJobsCompleted(totalCompleted);
        summary.setTotalJobsOnHold(totalOnHold);
        summary.setTotalJobsCancelled(totalCancelled);
        summary.setCompletionRate(completionRate);
        summary.setAvgOverallScore(avgScore);
        summary.setPerformanceLabel(performanceLabel(avgScore));
        summary.setDailyBreakdown(scores.stream().map(this::mapToDTO).collect(Collectors.toList()));

        return summary;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TARGET MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public KpiTarget setTarget(KpiTargetRequest req) {
        // Update existing target if present, else create new
        KpiTarget target = targetRepo
            .findByBranchIdAndTargetYearAndTargetMonth(
                req.getBranchId(), req.getTargetYear(), req.getTargetMonth())
            .orElse(new KpiTarget());

        target.setBranchId(req.getBranchId());
        target.setTargetYear(req.getTargetYear());
        target.setTargetMonth(req.getTargetMonth());
        target.setMinJobsPerDay(req.getMinJobsPerDay());
        target.setTargetSlaCompliance(req.getTargetSlaCompliance());
        target.setTargetCustomerRating(req.getTargetCustomerRating());
        target.setMaxAvgResolutionHours(req.getMaxAvgResolutionHours());

        return targetRepo.save(target);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MAPPER — KpiScore entity → KpiScoreDTO
    // ══════════════════════════════════════════════════════════════════════════

    public KpiScoreDTO mapToDTO(KpiScore s) {
        KpiScoreDTO dto = new KpiScoreDTO();
        dto.setId(s.getId());
        dto.setScoreDate(s.getScoreDate());
        dto.setScoredFor(s.getScoredFor() != null ? s.getScoredFor().name() : null);
        dto.setTechnicianId(s.getTechnicianId());
        dto.setTechnicianName(s.getTechnicianName());
        dto.setBranchId(s.getBranchId());
        dto.setJobsAssigned(s.getJobsAssigned());
        dto.setJobsCompleted(s.getJobsCompleted());
        dto.setJobsOnHold(s.getJobsOnHold());
        dto.setJobsCancelled(s.getJobsCancelled());
        dto.setAvgResolutionHours(s.getAvgResolutionHours());
        dto.setSlaCompliancePercent(s.getSlaCompliancePercent());
        dto.setAvgCustomerRating(s.getAvgCustomerRating());
        dto.setOverallScore(s.getOverallScore() != null
            ? BigDecimal.valueOf(s.getOverallScore()) : null);
        dto.setPerformanceLabel(performanceLabel(s.getOverallScore() != null
            ? BigDecimal.valueOf(s.getOverallScore()) : null));
        return dto;
    }

    /** Maps a score (0–100) to a readable label */
    private String performanceLabel(BigDecimal score) {
        if (score == null) return "N/A";
        double v = score.doubleValue();
        if (v >= 90) return "EXCELLENT";
        if (v >= 75) return "GOOD";
        if (v >= 50) return "AVERAGE";
        return "POOR";
    }
}
