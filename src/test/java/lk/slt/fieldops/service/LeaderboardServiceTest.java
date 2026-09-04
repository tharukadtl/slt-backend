package lk.slt.fieldops.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import lk.slt.fieldops.dto.KpiDTO;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CheckInOutRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.KpiScoreRepository;
import lk.slt.fieldops.repository.KpiTargetRepository;
import lk.slt.fieldops.repository.PaymentRepository;
import lk.slt.fieldops.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * KPI-006 (06_KPI_PERFORMANCE, FR-18) — when two technicians have identical overall scores, the
 * leaderboard must break the tie on a stated criterion (the row's: more completed jobs ranks
 * higher) rather than leaving the order to chance.
 *
 * <p><b>Class mapping.</b> The row maps to {@code LeaderboardServiceTest::tiedScores_higherJobsWins}
 * and calls {@code leaderboardService.build(techs)}. There is no {@code LeaderboardService} in this
 * codebase — the leaderboard is built by {@link KpiCalculationService#getLeaderboard(String, Long, Long)},
 * which is also what {@code GET /api/kpi/leaderboard} calls. The mapped class name is kept and the
 * real builder is exercised through it. The third parameter (Stage F #2's OPMC scoping) is passed as
 * {@code null} (unscoped) throughout this file — both fixture technicians share {@code opmcId=1L}, so
 * it has no bearing on the tiebreaker behavior under test.</p>
 *
 * <p><b>Why the fixture is ordered the way it is.</b> {@code getLeaderboard} sorts with
 * {@code leaderboard.sort((a, b) -> Double.compare(b.getOverallScore(), a.getOverallScore()))} and
 * nothing else. {@code List.sort} is a stable merge sort, so equal scores keep the order they were
 * added in — which is {@code userRepository.findAll()} order, i.e. whatever the database hands
 * back. To make that dependence visible rather than accidentally passing, the repository is stubbed
 * to return the <b>lower-job-count</b> technician first. If a tiebreaker existed, Tech A (20
 * completed jobs) would still be ranked above Tech B (15) regardless of that ordering.</p>
 *
 * <p><b>Both technicians really do tie.</b> {@code getLeaderboard} scores a technician from their
 * completion rate alone — satisfaction (4.5), response time (22 min) and attendance (90.0) are
 * hard-coded constants inside the loop, and on-time is derived as {@code completionRate * 0.9}. Two
 * technicians who completed every job assigned to them therefore both score 100% completion and
 * land on the identical overall score, whatever their absolute job counts.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaderboardServiceTest {

    @Mock private UserRepository       userRepository;
    @Mock private JobRepository        jobRepository;
    @Mock private PaymentRepository    paymentRepository;
    @Mock private KpiTargetRepository  kpiTargetRepository;
    @Mock private KpiScoreRepository   kpiScoreRepository;
    @Mock private CheckInOutRepository checkInOutRepository;

    @InjectMocks private KpiCalculationService kpiService;

    private User technician(long id, String fullName) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setFullName(fullName);
        u.setPhone("0771234" + id);
        u.setRole(User.Role.TECHNICIAN);
        u.setOpmcId(1L);
        u.setOpmcName("Colombo Central");
        return u;
    }

    /** {@code n} jobs for {@code technicianId}, all COMPLETED and all created today. */
    private List<Job> completedJobs(long technicianId, int n, String prefix) {
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Job j = new Job();
            j.setId(technicianId * 1000 + i);
            j.setJobNumber(prefix + "-" + i);
            j.setTechnicianId(technicianId);
            j.setStatus(Job.JobStatus.COMPLETED);
            // createdAt is written by @PrePersist, which never runs for an unsaved entity, so the
            // period filter in getLeaderboard would drop every job without this.
            ReflectionTestUtils.setField(j, "createdAt", LocalDateTime.now());
            jobs.add(j);
        }
        return jobs;
    }

    @Test
    void tiedScores_higherJobsWins() {
        // ── Steps 1-2: Tech A completed 20 of 20, Tech B completed 15 of 15 ─────────────────
        // Both are on a 100% completion rate, so both land on exactly the same overall score.
        User techA = technician(101L, "Tech A");
        User techB = technician(102L, "Tech B");

        List<Job> allJobs = new ArrayList<>();
        allJobs.addAll(completedJobs(techA.getId(), 20, "JOB-A"));
        allJobs.addAll(completedJobs(techB.getId(), 15, "JOB-B"));

        // Deliberately the "wrong" order: the fewer-jobs technician comes back from the DB first.
        when(userRepository.findAll()).thenReturn(Arrays.asList(techB, techA));
        when(jobRepository.findAll()).thenReturn(allJobs);

        // ── Step 3: build the leaderboard ───────────────────────────────────────────────────
        List<KpiDTO.LeaderboardEntryDTO> lb = kpiService.getLeaderboard(KpiDTO.PERIOD_MONTHLY, null, null);

        assertEquals(2, lb.size(), "Both technicians must appear on the leaderboard");

        KpiDTO.LeaderboardEntryDTO first  = lb.get(0);
        KpiDTO.LeaderboardEntryDTO second = lb.get(1);

        assertAll("a tie on score is broken by completed job count",

            // The premise of the row: the two scores really are equal.
            () -> assertEquals(first.getOverallScore(), second.getOverallScore(), 0.001,
                "Fixture premise: both technicians completed every job assigned to them, so their "
                    + "overall scores must tie. Were " + first.getOverallScore() + " and "
                    + second.getOverallScore()),
            () -> assertEquals(20L, lb.stream()
                    .filter(e -> techA.getId().equals(e.getTechnicianId()))
                    .findFirst().orElseThrow().getCompletedJobs(),
                "Fixture premise: Tech A must show 20 completed jobs"),
            () -> assertEquals(15L, lb.stream()
                    .filter(e -> techB.getId().equals(e.getTechnicianId()))
                    .findFirst().orElseThrow().getCompletedJobs(),
                "Fixture premise: Tech B must show 15 completed jobs"),

            // ── Step 4: the tiebreaker ──────────────────────────────────────────────────────
            () -> assertEquals("Tech A", first.getTechnicianName(),
                "With equal scores the technician who completed more jobs (Tech A, 20) must rank "
                    + "above the one who completed fewer (Tech B, 15). "
                    + "KpiCalculationService.getLeaderboard sorts on overallScore alone and applies "
                    + "no secondary criterion, so tied technicians keep userRepository.findAll() "
                    + "order - here Tech B was returned first and therefore took rank 1. Rank 1 "
                    + "was: " + first.getTechnicianName()),
            () -> assertEquals(1, first.getRank(),
                "The top entry must be rank 1"),
            () -> assertEquals(2, second.getRank(),
                "The runner-up must be rank 2")
        );
    }
}
