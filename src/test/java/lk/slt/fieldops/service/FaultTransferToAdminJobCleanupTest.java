package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA_Compliance_Consolidated_Report.md — {@code FaultAssignmentService.transferToAdmin} fully
 * detaches a Fault from its Work Group and Team Lead ({@code workGroupId}/{@code assignedTeamLeadId}
 * -> null, status -> REPORTED) but previously never touched the Job row underneath it. Since
 * {@code Job.teamLeadId} is {@code NOT NULL} (entity/Job.java:44), that Job was left dangling —
 * still pointing at the old Team Lead — and surfaced incorrectly in that Team Lead's own
 * {@code getTodaysJobsForTeamLead} ("Team Jobs"/"Needs Attention" queue on the Team Lead mobile
 * app), reassignable via the generic {@code reassignJob} action to a technician for a fault that no
 * longer belongs to their Work Group at all.
 *
 * <p><b>Fix mirrors an established precedent</b>: {@code JobService.routeOpenJobsAtEod}'s
 * {@code FORWARD_TO_ADMIN} case already handles the identical "fault forcibly pulled away from this
 * Team Lead" shape by cancelling the Job rather than trying to null a NOT NULL column
 * ({@code JobService.java:222-251}). {@code transferToAdmin} now does the same: the fault's current
 * Job (found the same way {@code IssueController} already does,
 * {@code findFirstByFaultIdOrderByCreatedAtDesc}) is set to {@code CANCELLED} with a
 * {@code rejectionReason} explaining the transfer and {@code rejectedByRole=TEAM_LEAD}, unless it is
 * already {@code COMPLETED}/{@code CANCELLED}.</p>
 *
 * <p>{@code @SpringBootTest} with real repositories against the real {@code slt_fieldops_db},
 * matching this suite's established convention (see {@code KpiTargetServiceTest}) — the question is
 * whether the real service leaves a real, queryable dangling row, which a mock cannot prove.
 * {@code @Transactional} rolls every fixture back.</p>
 */
@SpringBootTest
@Transactional
class FaultTransferToAdminJobCleanupTest {

    @Autowired private FaultAssignmentService faultAssignmentService;
    @Autowired private FaultRepository        faultRepo;
    @Autowired private JobRepository          jobRepo;
    @Autowired private UserRepository         userRepo;
    @Autowired private WorkGroupRepository    workGroupRepo;
    @Autowired private OpmcRepository         opmcRepo;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("FTA OPMC " + n);
        o.setCode("FTA" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private WorkGroup newWorkGroup(Opmc opmc) {
        long n = uniq();
        WorkGroup wg = new WorkGroup();
        wg.setName("FTA Work Group " + n);
        wg.setOpmc(opmc);
        wg.setIsActive(true);
        return workGroupRepo.save(wg);
    }

    private User newUser(User.Role role, Long opmcId, WorkGroup workgroup) {
        long n = uniq();
        User u = new User();
        u.setUsername("fta" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        u.setWorkgroup(workgroup);
        return userRepo.save(u);
    }

    private Fault newFault(User customer, WorkGroup workGroup, Long teamLeadId, String teamLeadName) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-FTA-" + n);
        f.setOpmcId(customer.getOpmcId());
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("Transfer-to-admin Job cleanup fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.ASSIGNED);
        f.setWorkGroupId(workGroup.getId());
        f.setWorkGroupName(workGroup.getName());
        f.setAssignedTeamLeadId(teamLeadId);
        f.setAssignedTeamLeadName(teamLeadName);
        return faultRepo.save(f);
    }

    private Job newJob(User teamLead, User customer, Fault fault, Job.JobStatus status) {
        long n = uniq();
        Job j = new Job();
        j.setJobNumber("JOB-FTA-" + n);
        j.setFaultId(fault.getId());
        j.setFaultNumber(fault.getFaultNumber());
        j.setCustomerId(customer.getId());
        j.setCustomerName(customer.getFullName());
        j.setTeamLeadId(teamLead.getId());
        j.setTeamLeadName(teamLead.getFullName());
        j.setPriority(Job.JobPriority.MEDIUM);
        j.setStatus(status);
        j.setRejectionReason(status == Job.JobStatus.REJECTED ? "Technician rejected: no access" : null);
        j.setRejectedByRole(status == Job.JobStatus.REJECTED ? "TECHNICIAN" : null);
        return jobRepo.save(j);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // The exact scenario asked for: a rejected Job under a fault that gets transferred to Admin
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void transferToAdmin_rejectedJobUnderneath_getsCancelled_notLeftDangling() {
        Opmc opmc = newOpmc();
        WorkGroup workGroup = newWorkGroup(opmc);
        User teamLead = newUser(User.Role.TEAM_LEAD, opmc.getId(), workGroup);
        User customer = newUser(User.Role.CLIENT, opmc.getId(), null);

        Fault fault = newFault(customer, workGroup, teamLead.getId(), teamLead.getFullName());
        // The technician already rejected this job — technicianId cleared server-side by the
        // real rejection path (JobService.updateJobStatus), reproduced directly here since this
        // test targets transferToAdmin, not the rejection flow itself.
        Job rejectedJob = newJob(teamLead, customer, fault, Job.JobStatus.REJECTED);
        em.flush();
        em.clear();

        FaultAssignmentDTO.TransferToAdminRequest req = FaultAssignmentDTO.TransferToAdminRequest
            .builder()
            .reason("Work Group cannot resolve this fault type")
            .build();

        faultAssignmentService.transferToAdmin(fault.getId(), req, teamLead.getId());
        em.flush();
        em.clear();

        Fault reloadedFault = faultRepo.findById(fault.getId()).orElseThrow();
        assertAll("the fault is fully detached from the Work Group/Team Lead",
            () -> assertEquals(Fault.FaultStatus.REPORTED, reloadedFault.getStatus()),
            () -> assertNull(reloadedFault.getWorkGroupId()),
            () -> assertNull(reloadedFault.getAssignedTeamLeadId())
        );

        Job reloadedJob = jobRepo.findById(rejectedJob.getId()).orElseThrow();
        assertAll("the Job is cancelled, not left dangling on the old Team Lead",
            () -> assertEquals(Job.JobStatus.CANCELLED, reloadedJob.getStatus(),
                "The stale REJECTED Job must be CANCELLED once its fault is transferred away, "
                    + "not left pointing at a Work Group assignment that no longer exists. Was "
                    + reloadedJob.getStatus()),
            () -> assertEquals("TEAM_LEAD", reloadedJob.getRejectedByRole(),
                "The cancellation must be attributed to the Team Lead's transfer action"),
            () -> assertNotNull(reloadedJob.getRejectionReason(),
                "The cancelled Job must carry a reason explaining why"),
            () -> assertTrue(reloadedJob.getRejectionReason().contains("transferred back to Admin"),
                "The reason must explain this was a transfer-to-Admin, not confuse it with the "
                    + "original technician rejection. Was: " + reloadedJob.getRejectionReason())
        );

        // The specific, real-world surfacing bug this fix closes: the old Team Lead's own job
        // list must no longer show this job as a live, actionable (non-terminal) item.
        assertFalse(isNonTerminal(reloadedJob),
            "A dangling non-terminal Job would keep surfacing in the old Team Lead's own "
                + "getTodaysJobsForTeamLead ('Team Jobs'/'Needs Attention') queue and remain "
                + "reassignable to a technician for a fault that has already moved on.");
    }

    @Test
    void transferToAdmin_openAcceptedJobUnderneath_alsoGetsCancelled() {
        Opmc opmc = newOpmc();
        WorkGroup workGroup = newWorkGroup(opmc);
        User teamLead = newUser(User.Role.TEAM_LEAD, opmc.getId(), workGroup);
        User customer = newUser(User.Role.CLIENT, opmc.getId(), null);

        Fault fault = newFault(customer, workGroup, teamLead.getId(), teamLead.getFullName());
        Job acceptedJob = newJob(teamLead, customer, fault, Job.JobStatus.ACCEPTED);
        em.flush();
        em.clear();

        FaultAssignmentDTO.TransferToAdminRequest req = FaultAssignmentDTO.TransferToAdminRequest
            .builder()
            .reason("Outside this Work Group's capability")
            .build();

        faultAssignmentService.transferToAdmin(fault.getId(), req, teamLead.getId());
        em.flush();
        em.clear();

        Job reloadedJob = jobRepo.findById(acceptedJob.getId()).orElseThrow();
        assertEquals(Job.JobStatus.CANCELLED, reloadedJob.getStatus(),
            "An ACCEPTED (not just REJECTED) open Job must also be cancelled when its fault is "
                + "transferred to Admin — the dangling-row problem is not specific to rejection. "
                + "Was " + reloadedJob.getStatus());
    }

    @Test
    void transferToAdmin_noJobExistsYet_doesNotThrow() {
        Opmc opmc = newOpmc();
        WorkGroup workGroup = newWorkGroup(opmc);
        User teamLead = newUser(User.Role.TEAM_LEAD, opmc.getId(), workGroup);
        User customer = newUser(User.Role.CLIENT, opmc.getId(), null);

        // A fault can be assigned to a Work Group without any Job ever having been created yet
        // (Team Lead hasn't dispatched to a technician). transferToAdmin must not assume one exists.
        Fault fault = newFault(customer, workGroup, teamLead.getId(), teamLead.getFullName());
        em.flush();
        em.clear();

        FaultAssignmentDTO.TransferToAdminRequest req = FaultAssignmentDTO.TransferToAdminRequest
            .builder()
            .reason("No technician dispatched yet, still transferring")
            .build();

        assertDoesNotThrow(() -> faultAssignmentService.transferToAdmin(fault.getId(), req, teamLead.getId()));

        Fault reloadedFault = faultRepo.findById(fault.getId()).orElseThrow();
        assertEquals(Fault.FaultStatus.REPORTED, reloadedFault.getStatus());
    }

    @Test
    void transferToAdmin_alreadyCompletedJobUnderneath_isNotResurrected() {
        Opmc opmc = newOpmc();
        WorkGroup workGroup = newWorkGroup(opmc);
        User teamLead = newUser(User.Role.TEAM_LEAD, opmc.getId(), workGroup);
        User customer = newUser(User.Role.CLIENT, opmc.getId(), null);

        // An unusual but real case per FaultAssignmentService's own status guard: the CURRENT
        // fault status must not be COMPLETED/CANCELLED for transferToAdmin to run at all, but an
        // OLDER Job row for the same fault (from an earlier assignment round) can already be
        // COMPLETED — that historical row must not be touched.
        Fault fault = newFault(customer, workGroup, teamLead.getId(), teamLead.getFullName());
        Job oldCompletedJob = newJob(teamLead, customer, fault, Job.JobStatus.COMPLETED);
        em.flush();
        em.clear();

        FaultAssignmentDTO.TransferToAdminRequest req = FaultAssignmentDTO.TransferToAdminRequest
            .builder()
            .reason("Reopened and now transferring")
            .build();

        faultAssignmentService.transferToAdmin(fault.getId(), req, teamLead.getId());
        em.flush();
        em.clear();

        Job reloadedJob = jobRepo.findById(oldCompletedJob.getId()).orElseThrow();
        assertEquals(Job.JobStatus.COMPLETED, reloadedJob.getStatus(),
            "An already-COMPLETED Job must never be resurrected/overwritten to CANCELLED. Was "
                + reloadedJob.getStatus());
    }

    private boolean isNonTerminal(Job job) {
        return job.getStatus() != Job.JobStatus.COMPLETED && job.getStatus() != Job.JobStatus.CANCELLED;
    }
}
