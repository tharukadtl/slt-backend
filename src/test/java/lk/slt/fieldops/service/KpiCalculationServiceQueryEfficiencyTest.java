package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.KpiDTO;
import lk.slt.fieldops.entity.CheckInOut;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CheckInOutRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.PaymentRepository;
import lk.slt.fieldops.repository.UserRepository;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA_Compliance_Consolidated_Report.md — {@code KpiCalculationService.getPersonalKpi} used to
 * load the entire jobs/payments tables via {@code findAll()} and filter in memory, and
 * {@code getTeamKpi} called {@code getPersonalKpi} once per member (its own full-table scans,
 * repeated N times). Both are now query-level filtered and batch-fetched. This proves it at a
 * realistic data volume: a team far larger than any single test elsewhere in this suite, sitting
 * inside jobs/payments/check-in-out tables that also carry a large pool of rows belonging to
 * *other* people the calls under test must not be scanning.
 *
 * <p><b>Why a query counter, not a stopwatch, is the primary evidence.</b> On a local, otherwise
 * idle MySQL instance, even a full {@code findAll()} scan of a few hundred rows can still return
 * in well under a second — wall-clock time alone would not reliably catch a regression back to
 * the old pattern at this test's volume. Hibernate's own {@link Statistics#getQueryExecutionCount()}
 * is used instead: a flat, small, team-size-independent query count is the only way to
 * distinguish "queries the database for exactly what it needs" from "loads everything and filters
 * in Java", regardless of how fast the database happens to answer either one. An elapsed-time
 * assertion is included too, as a generous sanity bound, not as the primary proof.</p>
 */
@SpringBootTest
@Transactional
class KpiCalculationServiceQueryEfficiencyTest {

    @Autowired private KpiCalculationService kpiService;
    @Autowired private UserRepository        userRepo;
    @Autowired private JobRepository         jobRepo;
    @Autowired private PaymentRepository     paymentRepo;
    @Autowired private CheckInOutRepository  checkInOutRepo;
    @Autowired private FaultRepository       faultRepo;
    @Autowired private OpmcRepository        opmcRepo;
    @Autowired private EntityManager         em;
    @Autowired private EntityManagerFactory  emf;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("QueryEfficiency OPMC " + n);
        o.setCode("QE" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private User newUser(User.Role role, Long opmcId, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("qe" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    /** A Fault for jobs to hang off — {@code jobs.fault_id} is NOT NULL. */
    private Fault newFault(User customer) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-QE-" + n);
        f.setOpmcId(customer.getOpmcId());
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("Query efficiency fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.COMPLETED);
        return faultRepo.save(f);
    }

    private List<Job> newJobsFor(User technician, User teamLead, User customer, Fault fault,
                                  int total, int completed) {
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            long n = uniq();
            Job j = new Job();
            j.setJobNumber("JOB-QE-" + n);
            j.setFaultId(fault.getId());
            j.setFaultNumber(fault.getFaultNumber());
            j.setCustomerId(customer.getId());
            j.setCustomerName(customer.getFullName());
            j.setTeamLeadId(teamLead.getId());
            j.setTeamLeadName(teamLead.getFullName());
            j.setTechnicianId(technician.getId());
            j.setTechnicianName(technician.getFullName());
            j.setPriority(Job.JobPriority.MEDIUM);
            j.setStatus(i < completed ? Job.JobStatus.COMPLETED : Job.JobStatus.IN_PROGRESS);
            jobs.add(jobRepo.save(j));
        }
        return jobs;
    }

    private void newPaymentFor(User teamLead, User customer, Job job) {
        long n = uniq();
        Payment p = new Payment();
        p.setPaymentNumber("PAY-QE-" + n);
        p.setPaymentReference("PAY-QE-" + n);
        p.setJobId(job.getId());
        p.setJobNumber(job.getJobNumber());
        p.setOpmcId(teamLead.getOpmcId());
        p.setCustomerId(customer.getId());
        p.setCustomerName(customer.getFullName());
        p.setTeamLeadId(teamLead.getId());
        p.setTeamLeadName(teamLead.getFullName());
        p.setMaterialsFocTotal(BigDecimal.ZERO);
        p.setMaterialsChargeableTotal(BigDecimal.ZERO);
        p.setLabourCharge(new BigDecimal("3000.00"));
        p.setTotalAmount(new BigDecimal("3000.00"));
        paymentRepo.save(p);
    }

    private void newAttendanceFor(User user) {
        CheckInOut row = new CheckInOut();
        row.setUser(user);
        row.setCheckType("ATTENDANCE");
        row.setCheckInTime(LocalDateTime.now().minusHours(8));
        row.setCheckOutTime(LocalDateTime.now().minusMinutes(5));
        row.setStatus("CHECKED_OUT");
        checkInOutRepo.save(row);
    }

    /**
     * {@code kpi_targets} has several NOT-NULL columns the {@code KpiTarget} entity does not map
     * at all ({@code target_type}, {@code period_year}) plus several more it maps but the
     * service never sets ({@code min_jobs_per_day}, {@code target_customer_rating},
     * {@code target_month}, {@code target_sla_compliance}, {@code target_year}) — the identical
     * pre-existing schema/entity mismatch {@code KpiIntegrationTest} documents against the real
     * assign-target endpoint ("no KPI target can be assigned to anyone through the API at all").
     * A plain entity save fails here for that same reason, so this fixture inserts natively
     * instead — it only needs to seed rows for {@code getPersonalKpi}/{@code getTeamKpi}'s read
     * path (which reads only {@code period}/{@code is_active}/{@code user_id}/
     * {@code target_value}/{@code current_value}), not exercise the broken write path.
     */
    private void newTargetFor(User user, User assignedBy) {
        long n = uniq();
        int year = LocalDate.now().getYear();
        int month = LocalDate.now().getMonthValue();
        em.createNativeQuery(
                "INSERT INTO kpi_targets "
                    + "(target_type, period_type, period_year, target_jobs_completed, "
                    + "min_jobs_per_day, target_customer_rating, target_month, "
                    + "target_sla_compliance, target_year, category, current_value, "
                    + "description, due_date, is_active, is_group_target, period, status, "
                    + "target_value, title, unit, assigned_by_id, user_id, opmc_id) "
                    + "VALUES ('INDIVIDUAL', 'MONTHLY', :year, 0, 1, 4.50, :month, 90.00, "
                    + ":year, 'JOBS', 0.0, 'QE fixture target', :dueDate, 1, 0, :period, "
                    + "'ON_TRACK', 90.0, :title, '%', :assignedById, :userId, :opmcId)")
            .setParameter("year", year)
            .setParameter("month", month)
            .setParameter("dueDate", LocalDate.now().plusDays(20))
            .setParameter("period", KpiDTO.PERIOD_MONTHLY)
            .setParameter("title", "QE Target " + n)
            .setParameter("assignedById", assignedBy.getId())
            .setParameter("userId", user.getId())
            .setParameter("opmcId", user.getOpmcId())
            .executeUpdate();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @Test
    void teamAndPersonalKpi_batchedAndFastAtRealisticVolume() {
        // ── Arrange: the "noise" side of the database — a different OPMC, different people,
        // hundreds of jobs/payments/attendance/target rows that a correct query must never
        // touch when computing KPIs for the real team below ─────────────────────────────────
        Opmc noiseOpmc = newOpmc();
        User noiseTeamLead = newUser(User.Role.TEAM_LEAD, noiseOpmc.getId(), "Noise TL");
        User noiseCustomer = newUser(User.Role.CLIENT, noiseOpmc.getId(), "Noise Client");
        User noiseAdmin = newUser(User.Role.ADMIN, noiseOpmc.getId(), "Noise Admin");
        Fault noiseFault = newFault(noiseCustomer);
        for (int i = 0; i < 6; i++) {
            User nt = newUser(User.Role.TECHNICIAN, noiseOpmc.getId(), "Noise Tech " + i);
            List<Job> noiseJobs =
                    newJobsFor(nt, noiseTeamLead, noiseCustomer, noiseFault, 100, 60);
            newPaymentFor(noiseTeamLead, noiseCustomer, noiseJobs.get(0));
            newAttendanceFor(nt);
            newTargetFor(nt, noiseAdmin);
        }
        // 6 * 100 = 600 unrelated jobs, plus 6 unrelated payments/attendance/target rows.

        // ── Arrange: the real team under test — 25 technicians, 12 jobs each ────────────────
        Opmc ourOpmc = newOpmc();
        User teamLead = newUser(User.Role.TEAM_LEAD, ourOpmc.getId(), "QE Team Lead");
        User admin    = newUser(User.Role.ADMIN,     ourOpmc.getId(), "QE Admin");
        User customer = newUser(User.Role.CLIENT,    ourOpmc.getId(), "QE Client");
        Fault fault   = newFault(customer);

        List<User> team = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            User tech = newUser(User.Role.TECHNICIAN, ourOpmc.getId(), "QE Tech " + i);
            List<Job> techJobs = newJobsFor(tech, teamLead, customer, fault, 12, 5);
            newPaymentFor(teamLead, customer, techJobs.get(0));
            newAttendanceFor(tech);
            newAttendanceFor(tech);
            newTargetFor(tech, admin);
            team.add(tech);
        }
        flushAndClear();

        // ── Act: getTeamKpi, with Hibernate's own query counter as the evidence ─────────────
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        long teamStart = System.nanoTime();
        KpiDTO.TeamKpiDTO teamKpi = kpiService.getTeamKpi(ourOpmc.getId(), KpiDTO.PERIOD_MONTHLY);
        long teamElapsedMs = (System.nanoTime() - teamStart) / 1_000_000;
        long teamQueryCount = stats.getQueryExecutionCount();

        assertAll("getTeamKpi is batch-fetched, not one query set per member",
            () -> assertEquals(26, teamKpi.getTotalMembers(),
                "getTeamKpi counts both TECHNICIAN and TEAM_LEAD roles, so all 25 seeded "
                    + "technicians plus the 1 seeded team lead must be counted as team members"),
            () -> assertEquals(25L * 12, teamKpi.getTotalJobsAssigned(),
                "Every seeded job across the team must be counted, none dropped and none from "
                    + "the noise OPMC double-counted"),
            () -> assertEquals(25L * 5, teamKpi.getTotalJobsCompleted(),
                "Only the seeded COMPLETED jobs must be counted as completed"),
            () -> assertTrue(teamQueryCount <= 8,
                "getTeamKpi must run a small, flat number of queries regardless of team size "
                    + "(1 member lookup + 4 batched jobs/payments/attendance/targets queries, "
                    + "give or take) rather than one query set per member. A 25-member team "
                    + "would mean 25+ repeated query sets under the old "
                    + "members.stream().map(m -> getPersonalKpi(...)) pattern this fix replaced; "
                    + "actual query count was " + teamQueryCount),
            () -> assertTrue(teamElapsedMs < 5000,
                "getTeamKpi for a 25-member team amid 600+ unrelated noise-OPMC jobs must return "
                    + "in well under 5s, took " + teamElapsedMs + "ms")
        );

        // ── Act: getPersonalKpi for one team member, amid 600+ unrelated noise jobs ─────────
        User sample = team.get(0);
        stats.clear();

        long personalStart = System.nanoTime();
        KpiDTO.PersonalKpiDTO personalKpi =
                kpiService.getPersonalKpi(sample.getId(), KpiDTO.PERIOD_MONTHLY);
        long personalElapsedMs = (System.nanoTime() - personalStart) / 1_000_000;
        long personalQueryCount = stats.getQueryExecutionCount();

        assertAll("getPersonalKpi is query-filtered, not a full-table scan",
            () -> assertEquals(12, personalKpi.getTotalJobs(),
                "Only this technician's own 12 jobs must be counted, not the 600 unrelated "
                    + "noise-OPMC jobs also sitting in the jobs table"),
            () -> assertEquals(5, personalKpi.getCompletedJobs(),
                "Only this technician's own 5 completed jobs must be counted"),
            () -> assertTrue(personalQueryCount <= 6,
                "getPersonalKpi must run a small, flat number of queries (user lookup + 4 "
                    + "filtered jobs/payments/attendance/targets queries) regardless of how many "
                    + "unrelated rows exist elsewhere in the table. Actual query count was "
                    + personalQueryCount),
            () -> assertTrue(personalElapsedMs < 3000,
                "getPersonalKpi must return in well under 3s even with 600+ unrelated jobs "
                    + "sitting in the table, took " + personalElapsedMs + "ms")
        );
    }
}
