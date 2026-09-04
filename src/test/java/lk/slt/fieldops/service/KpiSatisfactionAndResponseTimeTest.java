package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.KpiDTO;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA_Compliance_Consolidated_Report.md #2 — {@code KpiCalculationService.getPersonalKpi} used to
 * hardcode {@code satisfaction = 4.5} and {@code avgResponseTime = 22.0} for every technician
 * regardless of their real jobs. Both are now derived from real data:
 * <ul>
 *   <li><b>satisfaction</b> — the average of {@code Fault.customerRating} (1-5) across the
 *       technician's own COMPLETED jobs in the scoring period.</li>
 *   <li><b>avgResponseTime</b> — the average number of minutes between {@code Job.createdAt}
 *       (the instant a Team Lead dispatches the job to the technician,
 *       {@code JobService.createJob}) and {@code Job.acceptedAt} (the technician's first action
 *       on it, stamped by {@code JobService.updateStatus} on the ACCEPTED transition).</li>
 * </ul>
 * {@code onTimeRate} is deliberately untouched (it already derives from real
 * {@code completionRate}, not a fabricated constant — out of scope for this fix).
 *
 * <p><b>Investigation finding, asserted here rather than silently assumed:</b> project-wide search
 * confirmed {@code Fault.setCustomerRating(...)} is never called anywhere in {@code fieldops} or
 * {@code SLTMobileApp} — there is no controller endpoint and no mobile screen that ever lets a
 * customer actually submit a rating. So today, with zero real ratings in the system, satisfaction
 * genuinely averages to 0 for every technician (see
 * {@link #satisfaction_noRatingsYet_defaultsToZero_notFabricatedPositivity()}) — this is the
 * honest reflection of real (currently absent) data, matching the same
 * {@code .average().orElse(0)} convention {@code ReportService.buildSatisfactionReport} already
 * uses, not a bug in this fix. It will start reflecting real scores the moment a rating-capture
 * write path is built elsewhere in the product; building that path is a separate, larger gap than
 * this fix's scope (wiring the KPI calculation to whatever rating data exists).</p>
 *
 * <p>{@code @SpringBootTest} with real repositories against the real {@code slt_fieldops_db},
 * matching this suite's established KPI-test convention (see
 * {@link KpiTargetServiceTest}) — the question being asked is whether the real calculation reads
 * real {@code Fault}/{@code Job} rows, which a mock cannot prove. {@code @Transactional} rolls
 * every fixture back.</p>
 */
@SpringBootTest
@Transactional
class KpiSatisfactionAndResponseTimeTest {

    @Autowired private KpiCalculationService kpiService;
    @Autowired private UserRepository        userRepo;
    @Autowired private JobRepository         jobRepo;
    @Autowired private FaultRepository       faultRepo;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("kst" + n);
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

    private Fault newFault(User customer, Integer customerRating) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-KST-" + n);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("KPI satisfaction/response-time fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.COMPLETED);
        f.setCustomerRating(customerRating);
        return faultRepo.save(f);
    }

    private Job newJob(User technician, User teamLead, User customer, Fault fault,
                        Job.JobStatus status) {
        long n = uniq();
        Job j = new Job();
        j.setJobNumber("JOB-KST-" + n);
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

    /** Backdates nothing — reads back the real {@code @PrePersist}-stamped createdAt, then sets
     *  acceptedAt exactly {@code minutesLater} after it, so the response-time delta is exact and
     *  deterministic without fighting the entity's own timestamp lifecycle. */
    private Job acceptAfter(Job job, long minutesLater) {
        Job fresh = jobRepo.findById(job.getId()).orElseThrow();
        assertNotNull(fresh.getCreatedAt(), "Setup: createdAt must be stamped by @PrePersist");
        fresh.setAcceptedAt(fresh.getCreatedAt().plusMinutes(minutesLater));
        return jobRepo.save(fresh);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Satisfaction — real average of Fault.customerRating, not the old hardcoded 4.5
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void satisfaction_derivedFromRealCustomerRatings_notHardcoded() {
        User teamLead = newUser(User.Role.TEAM_LEAD,  "TL Ranasinghe");
        User customer = newUser(User.Role.CLIENT,     "Client Fernando");
        User tech     = newUser(User.Role.TECHNICIAN, "Tech Perera");

        Fault fault5 = newFault(customer, 5);
        Fault fault3 = newFault(customer, 3);
        newJob(tech, teamLead, customer, fault5, Job.JobStatus.COMPLETED);
        newJob(tech, teamLead, customer, fault3, Job.JobStatus.COMPLETED);
        em.flush();
        em.clear();

        KpiDTO.PersonalKpiDTO kpi = kpiService.getPersonalKpi(tech.getId(), KpiDTO.PERIOD_MONTHLY);

        assertEquals(4.0, kpi.getCustomerSatisfactionScore(), 0.01,
            "Average of real ratings 5 and 3 must be 4.0, not the old hardcoded 4.5. Was "
                + kpi.getCustomerSatisfactionScore());
    }

    @Test
    void satisfaction_noRatingsYet_defaultsToZero_notFabricatedPositivity() {
        User teamLead = newUser(User.Role.TEAM_LEAD,  "TL Wickrama");
        User customer = newUser(User.Role.CLIENT,     "Client Silva");
        User tech     = newUser(User.Role.TECHNICIAN, "Tech Bandara");

        Fault unrated = newFault(customer, null);
        newJob(tech, teamLead, customer, unrated, Job.JobStatus.COMPLETED);
        em.flush();
        em.clear();

        KpiDTO.PersonalKpiDTO kpi = kpiService.getPersonalKpi(tech.getId(), KpiDTO.PERIOD_MONTHLY);

        assertEquals(0.0, kpi.getCustomerSatisfactionScore(), 0.01,
            "With no real customer rating anywhere, satisfaction must honestly read 0 — not the "
                + "old hardcoded 4.5, which fabricated a positive score nobody actually gave. Was "
                + kpi.getCustomerSatisfactionScore());
    }

    @Test
    void satisfaction_onlyCountsCompletedJobsFaults() {
        User teamLead = newUser(User.Role.TEAM_LEAD,  "TL Gunaratne");
        User customer = newUser(User.Role.CLIENT,     "Client Jayasuriya");
        User tech     = newUser(User.Role.TECHNICIAN, "Tech Rathnayake");

        Fault completedRated5 = newFault(customer, 5);
        Fault stillInProgressRated1 = newFault(customer, 1);
        newJob(tech, teamLead, customer, completedRated5, Job.JobStatus.COMPLETED);
        newJob(tech, teamLead, customer, stillInProgressRated1, Job.JobStatus.IN_PROGRESS);
        em.flush();
        em.clear();

        KpiDTO.PersonalKpiDTO kpi = kpiService.getPersonalKpi(tech.getId(), KpiDTO.PERIOD_MONTHLY);

        assertEquals(5.0, kpi.getCustomerSatisfactionScore(), 0.01,
            "Only the COMPLETED job's fault (rated 5) must count; the still-open job's rated-1 "
                + "fault must not drag the average down. Was " + kpi.getCustomerSatisfactionScore());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Response time — real minutes between Job.createdAt and Job.acceptedAt, not hardcoded 22.0
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void avgResponseTime_derivedFromRealJobTimestamps_notHardcoded() {
        User teamLead = newUser(User.Role.TEAM_LEAD,  "TL Dissanayake");
        User customer = newUser(User.Role.CLIENT,     "Client Herath");
        User tech     = newUser(User.Role.TECHNICIAN, "Tech Weerasinghe");
        Fault fault   = newFault(customer, null);

        Job job1 = newJob(tech, teamLead, customer, fault, Job.JobStatus.ACCEPTED);
        Job job2 = newJob(tech, teamLead, customer, fault, Job.JobStatus.ACCEPTED);
        acceptAfter(job1, 15);
        acceptAfter(job2, 45);
        em.flush();
        em.clear();

        KpiDTO.PersonalKpiDTO kpi = kpiService.getPersonalKpi(tech.getId(), KpiDTO.PERIOD_MONTHLY);

        assertEquals(30.0, kpi.getAvgResponseTimeMinutes(), 0.5,
            "Average of real 15-minute and 45-minute responses must be 30, not the old hardcoded "
                + "22.0. Was " + kpi.getAvgResponseTimeMinutes());
    }

    @Test
    void avgResponseTime_excludesJobsNeverAccepted() {
        User teamLead = newUser(User.Role.TEAM_LEAD,  "TL Karunaratne");
        User customer = newUser(User.Role.CLIENT,     "Client Peiris");
        User tech     = newUser(User.Role.TECHNICIAN, "Tech Ekanayake");
        Fault fault   = newFault(customer, null);

        Job accepted = newJob(tech, teamLead, customer, fault, Job.JobStatus.ACCEPTED);
        acceptAfter(accepted, 20);
        // Still PENDING — acceptedAt is null, must not contribute a fabricated 0-minute response.
        newJob(tech, teamLead, customer, fault, Job.JobStatus.PENDING);
        em.flush();
        em.clear();

        KpiDTO.PersonalKpiDTO kpi = kpiService.getPersonalKpi(tech.getId(), KpiDTO.PERIOD_MONTHLY);

        assertEquals(20.0, kpi.getAvgResponseTimeMinutes(), 0.5,
            "The never-accepted PENDING job must be excluded entirely, not counted as an "
                + "instant 0-minute response. Was " + kpi.getAvgResponseTimeMinutes());
    }

    @Test
    void avgResponseTime_zeroWhenNoJobEverAccepted() {
        User teamLead = newUser(User.Role.TEAM_LEAD,  "TL Senanayake");
        User customer = newUser(User.Role.CLIENT,     "Client Mendis");
        User tech     = newUser(User.Role.TECHNICIAN, "Tech Rajapaksa");
        Fault fault   = newFault(customer, null);

        newJob(tech, teamLead, customer, fault, Job.JobStatus.PENDING);
        em.flush();
        em.clear();

        KpiDTO.PersonalKpiDTO kpi = kpiService.getPersonalKpi(tech.getId(), KpiDTO.PERIOD_MONTHLY);

        assertEquals(0.0, kpi.getAvgResponseTimeMinutes(), 0.01,
            "With no accepted job at all, response time must read 0, not the old hardcoded 22.0. "
                + "Was " + kpi.getAvgResponseTimeMinutes());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // getTeamKpi — the batched per-team fault lookup must not cross-contaminate members
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void teamKpi_perMemberSatisfaction_notCrossContaminatedByBatchedFaultLookup() {
        User teamLead = newUser(User.Role.TEAM_LEAD,  "TL Fonseka");
        User customer = newUser(User.Role.CLIENT,     "Client Amarasinghe");
        User techA    = newUser(User.Role.TECHNICIAN, "Tech A Fonseka");
        User techB    = newUser(User.Role.TECHNICIAN, "Tech B Fonseka");

        Fault faultA = newFault(customer, 5);
        Fault faultB = newFault(customer, 1);
        newJob(techA, teamLead, customer, faultA, Job.JobStatus.COMPLETED);
        newJob(techB, teamLead, customer, faultB, Job.JobStatus.COMPLETED);
        em.flush();
        em.clear();

        KpiDTO.TeamKpiDTO teamKpi = kpiService.getTeamKpi(REAL_BRANCH_ID, KpiDTO.PERIOD_MONTHLY);

        List<KpiDTO.PersonalKpiDTO> members = teamKpi.getMemberKpis();
        KpiDTO.PersonalKpiDTO a = members.stream()
            .filter(m -> m.getTechnicianId().equals(techA.getId())).findFirst().orElseThrow();
        KpiDTO.PersonalKpiDTO b = members.stream()
            .filter(m -> m.getTechnicianId().equals(techB.getId())).findFirst().orElseThrow();

        assertAll("each member keeps their own fault's rating out of the shared batched lookup",
            () -> assertEquals(5.0, a.getCustomerSatisfactionScore(), 0.01,
                "Tech A's own 5-star fault must not be diluted by Tech B's 1-star fault. Was "
                    + a.getCustomerSatisfactionScore()),
            () -> assertEquals(1.0, b.getCustomerSatisfactionScore(), 0.01,
                "Tech B's own 1-star fault must not be inflated by Tech A's 5-star fault. Was "
                    + b.getCustomerSatisfactionScore())
        );
    }
}
