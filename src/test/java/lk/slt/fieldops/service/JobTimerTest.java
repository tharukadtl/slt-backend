package lk.slt.fieldops.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOB-009 (03_JOB_LIFECYCLE, FR-8) — the work timer: start &rarr; pause &rarr; resume &rarr; pause
 * must leave two completed timer-log rows for the job, whose intervals sum to the total worked
 * time.
 *
 * <p><b>What this test found.</b> There is no work-timer persistence in this backend at all. There
 * is no {@code JobTimerLog} entity, no {@code JobTimerLogRepository} (which the row's
 * Pre-Conditions name), and no {@code startTimer} / {@code pauseTimer} / {@code resumeTimer} on
 * {@link JobService}. The only elapsed-time signals the {@code jobs} table carries are the
 * single-shot {@code started_at} / {@code hold_at} / {@code completed_at} stamps, which cannot
 * represent more than one work interval — a pause/resume cycle overwrites nothing and is simply
 * lost. On the client, {@code TaskDetailScreen.tsx} runs the timer purely in component state (a
 * {@code setInterval} over a local {@code startTime}) and never persists it, and its "Pause Work"
 * handler just clears that local state.</p>
 *
 * <p>Because the API the row specifies does not exist, this test cannot be written as a normal
 * call-and-assert unit test — referencing {@code JobTimerLogRepository} directly would not compile.
 * It instead resolves the timer API reflectively so that it <b>executes and returns a real
 * verdict</b>: it fails today, naming exactly what is missing, and will pass unchanged once the
 * timer persistence is implemented. It deliberately does NOT assert the absence of the feature —
 * that would lock the gap in as correct behaviour.</p>
 *
 * <p><b>Production change required to make this green:</b> a {@code JobTimerLog} entity
 * ({@code job_id}, {@code started_at}, {@code stopped_at}) with its repository, plus
 * {@code startTimer} / {@code pauseTimer} / {@code resumeTimer} on {@link JobService} and the
 * matching endpoints. Out of scope for this suite (test code only).</p>
 */
class JobTimerTest {

    private static final String TIMER_LOG_ENTITY =
        "lk.slt.fieldops.entity.JobTimerLog";
    private static final String TIMER_LOG_REPOSITORY =
        "lk.slt.fieldops.repository.JobTimerLogRepository";

    private static final List<String> TIMER_METHODS =
        List.of("startTimer", "pauseTimer", "resumeTimer");

    private static Class<?> loadOrNull(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static List<String> missingJobServiceTimerMethods() {
        List<String> present = Arrays.stream(JobService.class.getMethods())
            .map(Method::getName)
            .collect(Collectors.toList());
        return TIMER_METHODS.stream()
            .filter(m -> !present.contains(m))
            .collect(Collectors.toList());
    }

    @Test
    void pauseResumeCycles_correctLogRows() {
        Class<?> timerLogEntity     = loadOrNull(TIMER_LOG_ENTITY);
        Class<?> timerLogRepository = loadOrNull(TIMER_LOG_REPOSITORY);
        List<String> missingMethods = missingJobServiceTimerMethods();

        assertAll("work-timer persistence (start -> pause -> resume -> pause)",

            // ── Pre-condition the row names: "JobTimerLogRepo mocked" ────────────────────
            () -> assertNotNull(timerLogEntity,
                "JOB-009 requires a persisted work-timer log so that a start/pause/resume/pause "
                    + "cycle produces two completed interval rows. No " + TIMER_LOG_ENTITY
                    + " entity exists — the jobs table only carries single-shot started_at / "
                    + "hold_at / completed_at stamps, which cannot represent more than one "
                    + "interval, and the mobile TaskDetailScreen timer is component state only "
                    + "(never persisted). PRODUCTION CHANGE REQUIRED."),

            () -> assertNotNull(timerLogRepository,
                "No " + TIMER_LOG_REPOSITORY + " exists, so there is nothing to query for the "
                    + "row's assertion 'timerLogRepo.findByJobId(42L).size() == 2'. "
                    + "PRODUCTION CHANGE REQUIRED."),

            // ── Steps 1-4: the four operations the row drives ────────────────────────────
            () -> assertTrue(missingMethods.isEmpty(),
                "JobService is missing the timer operations JOB-009 drives: " + missingMethods
                    + ". Pausing and resuming work currently has no server-side effect at all, so "
                    + "total worked time cannot be computed from stored intervals. "
                    + "PRODUCTION CHANGE REQUIRED.")
        );
    }
}
