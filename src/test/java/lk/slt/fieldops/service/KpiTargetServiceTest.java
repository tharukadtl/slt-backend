package lk.slt.fieldops.service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import lk.slt.fieldops.dto.KpiDTO;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.KpiTarget;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.KpiTargetRepository;
import lk.slt.fieldops.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KPI-004 (06_KPI_PERFORMANCE, FR-17) — a technician's target progress must move as they complete
 * jobs, and the target must flip to {@code ACHIEVED} once the target value is met.
 *
 * <p><b>Class and method mapping.</b> The row maps to
 * {@code KpiTargetServiceTest::jobCompletion_progressUpdates} and calls
 * {@code kpiTargetService.updateProgress(techId)}. Neither exists: there is no
 * {@code KpiTargetService} anywhere in {@code fieldops}, and no method named {@code updateProgress}
 * on any service. Targets are created and read by {@link KpiCalculationService} alone. The mapped
 * class name is kept, and the recalculation is triggered through the only entry point the module
 * has for deriving a technician's KPI from their job rows —
 * {@link KpiCalculationService#getPersonalKpi(Long, String)}, which is exactly what
 * {@code GET /api/kpi/my-score} and {@code GET /api/kpi/score/{id}} call, and which already
 * rebuilds every other metric (completion rate, on-time rate, attendance, overall score) on every
 * read. The technician's targets are then re-read through
 * {@link KpiCalculationService#getTechnicianTargets(Long)} — the call behind
 * {@code GET /api/kpi/targets/technician/{id}} and the Admin portal's target cards.</p>
 *
 * <p><b>Fixture.</b> Follows the row's steps literally: a "Monthly Completion Rate" target of 90%
 * whose {@code currentValue} starts at the row's 70%, then the technician's job rows are made to
 * show a real 90% completion rate (9 of 10 jobs COMPLETED) — the "complete a job" step — and the
 * recalculation is triggered. {@code @SpringBootTest} with real repositories against the real
 * {@code slt_fieldops_db} is used rather than Mockito, because the question being asked is whether
 * any production code path writes {@code kpi_targets.current_value}; mocks could only assert
 * against a service that does not exist. {@code @Transactional} rolls the fixture back.</p>
 */
@SpringBootTest
@Transactional
class KpiTargetServiceTest {

    @Autowired private KpiCalculationService kpiService;
    @Autowired private UserRepository        userRepo;
    @Autowired private JobRepository         jobRepo;
    @Autowired private FaultRepository       faultRepo;
    @Autowired private KpiTargetRepository   targetRepo;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("u" + n);
        u.setPasswordHash("x");
        u.setFirstName("First");
        u.setLastName("Last");
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_BRANCH_ID);
        u.setOpmcName("Colombo Central");
        return userRepo.save(u);
    }

    private Fault newFault(User customer) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-KPT-" + n);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("KPI target fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.COMPLETED);
        return faultRepo.save(f);
    }

    private Job newJob(User technician, User teamLead, User customer, Fault fault,
                       Job.JobStatus status) {
        long n = uniq();
        Job j = new Job();
        j.setJobNumber("JOB-KPT-" + n);
        j.setFaultId(fault.getId());
        j.setFaultNumber(fault.getFaultNumber());
        j.setCustomerId(customer.getId());
        j.setCustomerName(customer.getFullName());
        j.setTeamLeadId(teamLead.getId());
        j.setTeamLeadName(teamLead.getFullName());
        j.setTechnicianId(technician.getId());
        j.setTechnicianName(technician.getFullName());
        j.setPriority(Job.JobPriority.MEDIUM);
        j.setStatus(status);
        return jobRepo.save(j);
    }

    /**
     * Inserts the fixture target with a native statement rather than through
     * {@code KpiCalculationService.assignTarget}.
     *
     * <p><b>Why.</b> No {@code KpiTarget} can be persisted through JPA at all against this schema:
     * {@code kpi_targets.min_jobs_per_day}, {@code target_customer_rating}, {@code target_month},
     * {@code target_sla_compliance} and {@code target_year} are all NOT NULL with no default and
     * are left null by {@code assignTarget}, and {@code period_year} is NOT NULL and not mapped by
     * the {@code KpiTarget} entity at all — under {@code STRICT_TRANS_TABLES} the INSERT can never
     * succeed. That defect is asserted separately at the end of the test. Seeding the fixture
     * around it is what lets this row actually test its own subject: whether anything in the system
     * moves {@code current_value} as jobs are completed.</p>
     */
    private Long seedTarget(User tech, User admin, String title, double targetValue,
                            double currentValue, LocalDate dueDate) {
        LocalDate today = LocalDate.now();
        em.createNativeQuery(
                "INSERT INTO kpi_targets (target_type, period_type, period_year, "
                    + "target_jobs_completed, user_id, assigned_by_id, title, description, "
                    + "target_value, current_value, unit, period, category, due_date, start_date, "
                    + "status, is_group_target, is_active, min_jobs_per_day, "
                    + "target_customer_rating, target_month, target_sla_compliance, target_year, "
                    + "created_at, updated_at) "
                    + "VALUES ('INDIVIDUAL', 'MONTHLY', ?, 0, ?, ?, ?, ?, ?, ?, '%', 'MONTHLY', "
                    + "'JOBS', ?, ?, 'ON_TRACK', 0, 1, 1, 4.0, ?, 90.0, ?, NOW(), NOW())")
            .setParameter(1, today.getYear())
            .setParameter(2, tech.getId())
            .setParameter(3, admin.getId())
            .setParameter(4, title)
            .setParameter(5, "Complete 90% of assigned jobs this month")
            .setParameter(6, targetValue)
            .setParameter(7, currentValue)
            .setParameter(8, dueDate)
            .setParameter(9, today)
            .setParameter(10, today.getMonthValue())
            .setParameter(11, today.getYear())
            .executeUpdate();

        Number id = (Number) em.createNativeQuery(
                "SELECT id FROM kpi_targets WHERE title = ? AND user_id = ?")
            .setParameter(1, title)
            .setParameter(2, tech.getId())
            .getSingleResult();
        return id.longValue();
    }

    @Test
    void jobCompletion_progressUpdates() {
        // ── Arrange: an admin, a technician, and a 90% completion-rate target ────────────────
        User admin    = newUser(User.Role.ADMIN,      "Admin Amelia");
        User teamLead = newUser(User.Role.TEAM_LEAD,  "TL Tharindu");
        User customer = newUser(User.Role.CLIENT,     "Client Chathura");
        User tech     = newUser(User.Role.TECHNICIAN, "Tech Kasun");
        Fault fault   = newFault(customer);
        userRepo.flush();
        faultRepo.flush();

        LocalDate dueDate = LocalDate.now().plusDays(20);
        String title = "Monthly Completion Rate " + uniq();

        // ── Step 1: the target sits at the row's starting progress of 70% of a 90% goal ─────
        Long targetId = seedTarget(tech, admin, title, 90.0, 70.0, dueDate);
        em.flush();
        em.clear();

        // ── Step 2: the technician's work now shows a real 90% completion rate ──────────────
        // 10 jobs this month, the tenth of which has just been completed: 9 COMPLETED, 1 open.
        for (int i = 0; i < 9; i++) {
            newJob(tech, teamLead, customer, fault, Job.JobStatus.COMPLETED);
        }
        newJob(tech, teamLead, customer, fault, Job.JobStatus.IN_PROGRESS);
        jobRepo.flush();
        em.flush();
        em.clear();

        // ── Step 3: trigger the recalculation (see the javadoc — this is the only trigger) ──
        KpiDTO.PersonalKpiDTO kpi = kpiService.getPersonalKpi(tech.getId(), KpiDTO.PERIOD_MONTHLY);

        assertEquals(90.0, kpi.getCompletionRate(), 0.05,
            "Setup: the technician's real completion rate must be 90% (9 of 10). Was "
                + kpi.getCompletionRate());

        em.flush();
        em.clear();

        KpiTarget reloaded = targetRepo.findById(targetId).orElseThrow();
        List<KpiDTO.TargetResponseDTO> viaApi = kpiService.getTechnicianTargets(tech.getId());
        KpiDTO.TargetResponseDTO target = viaApi.stream()
            .filter(t -> targetId.equals(t.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "The seeded target must be returned by getTechnicianTargets"));

        // Captured before the assign-path probe below, which deliberately provokes a failed
        // INSERT and leaves the transaction unusable for further reads.
        Double storedCurrentValue = reloaded.getCurrentValue();
        Double apiCurrentValue    = target.getCurrentValue();
        Double apiProgressPercent = target.getProgressPercent();
        String apiStatus          = target.getStatus();

        // ── The production assign path, probed last because its failure poisons the tx ──────
        String assignFailure = null;
        try {
            kpiService.assignTarget(admin.getId(), KpiDTO.AssignTargetRequest.builder()
                .technicianId(tech.getId())
                .title("Assign path probe " + uniq())
                .description("Probe")
                .targetValue(90.0)
                .unit("%")
                .period(KpiDTO.PERIOD_MONTHLY)
                .category(KpiDTO.CAT_JOBS)
                .dueDate(dueDate)
                .build());
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            assignFailure = root.getClass().getSimpleName() + ": " + root.getMessage();
        }
        final String assignFailureMessage = assignFailure;

        assertAll("target progress tracks job completion and flips to ACHIEVED when met",

            // ── Step 4: currentValue is recalculated from the technician's real work ────────
            () -> assertEquals(90.0, storedCurrentValue, 0.05,
                "kpi_targets.current_value must be recalculated from the technician's real "
                    + "completion rate (90%) once jobs are completed. Nothing in fieldops ever "
                    + "writes current_value after creation: the column is set to 0.0 in "
                    + "KpiCalculationService.assignTarget and there is no updateProgress, no "
                    + "scheduled recalculation and no write from JobService, so a grep for "
                    + "setCurrentValue over fieldops/src/main returns no hits at all. Value was: "
                    + storedCurrentValue),

            () -> assertEquals(90.0, apiCurrentValue, 0.05,
                "The target returned by GET /api/kpi/targets/technician/{id} must show the "
                    + "recalculated progress, was " + apiCurrentValue),

            () -> assertEquals(100.0, apiProgressPercent, 0.05,
                "progressPercent is currentValue/targetValue - at 90 of 90 it must read 100%, "
                    + "was " + apiProgressPercent),

            // ── Step 5: currentValue >= targetValue means ACHIEVED ─────────────────────────
            () -> assertEquals(KpiDTO.STATUS_ACHIEVED, apiStatus,
                "A target of 90% that the technician has now met must report ACHIEVED. Because "
                    + "current_value is frozen at whatever the row was created with, the status "
                    + "mapTargetToDTO derives can never reach ACHIEVED for a genuinely achieved "
                    + "target. Status was: " + apiStatus),

            // ── And the target must be creatable in the first place ────────────────────────
            () -> assertNull(assignFailureMessage,
                "KpiCalculationService.assignTarget must be able to persist a target. It leaves "
                    + "kpi_targets.min_jobs_per_day, target_customer_rating, target_month, "
                    + "target_sla_compliance and target_year null and the KpiTarget entity does "
                    + "not map period_year at all, while all six columns are NOT NULL with no "
                    + "default under STRICT_TRANS_TABLES - so no KPI target can be created by any "
                    + "caller. The fixture above had to be inserted with native SQL to test this "
                    + "row's actual subject. Failure was: " + assignFailureMessage)
        );
    }
}
