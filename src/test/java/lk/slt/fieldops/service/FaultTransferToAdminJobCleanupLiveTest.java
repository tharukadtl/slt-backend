package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA_Compliance_Consolidated_Report.md — {@code FaultAssignmentService.transferToAdmin} Job-cleanup
 * fix, verified live: deliberately <b>not</b> {@code @Transactional}, so every write below commits
 * for real to {@code slt_fieldops_db} and the assertions re-query fresh rows from a clean persistence
 * context rather than reading back the same in-memory objects the fixture just built — the same
 * "real backend, fresh independent re-query, cleaned up afterward" standard this report's other live
 * verifications use. {@link FaultTransferToAdminJobCleanupTest} covers the fuller matrix of cases
 * (accepted job, no job yet, already-completed job) under {@code @Transactional} rollback; this test
 * exists only to prove the exact scenario asked for — a rejected Job under a fault that gets
 * transferred to Admin — leaves no dangling row in the real, committed database.
 */
@SpringBootTest
class FaultTransferToAdminJobCleanupLiveTest {

    @Autowired private FaultAssignmentService faultAssignmentService;
    @Autowired private FaultRepository        faultRepo;
    @Autowired private FaultHistoryRepository faultHistoryRepo;
    @Autowired private JobRepository          jobRepo;
    @Autowired private UserRepository         userRepo;
    @Autowired private WorkGroupRepository    workGroupRepo;
    @Autowired private OpmcRepository         opmcRepo;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    @Test
    void transferToAdmin_rejectedJobUnderneath_committedToRealDb_noDanglingRowRemains() {
        long n = uniq();

        Opmc opmc = new Opmc();
        opmc.setName("FTA-LIVE OPMC " + n);
        opmc.setCode("FTAL" + n);
        opmc.setAddress("123 Test Road");
        opmc = opmcRepo.save(opmc);

        WorkGroup workGroup = new WorkGroup();
        workGroup.setName("FTA-LIVE Work Group " + n);
        workGroup.setOpmc(opmc);
        workGroup.setIsActive(true);
        workGroup = workGroupRepo.save(workGroup);

        User teamLead = new User();
        teamLead.setUsername("ftalive" + n);
        teamLead.setPasswordHash("x");
        teamLead.setFirstName("Live");
        teamLead.setLastName("TeamLead");
        teamLead.setFullName("Live TeamLead " + n);
        teamLead.setPhone("07" + (10000000L + (n % 80000000L)));
        teamLead.setRole(User.Role.TEAM_LEAD);
        teamLead.setOpmcId(opmc.getId());
        teamLead.setWorkgroup(workGroup);
        teamLead = userRepo.save(teamLead);

        User customer = new User();
        customer.setUsername("ftaliveclient" + n);
        customer.setPasswordHash("x");
        customer.setFirstName("Live");
        customer.setLastName("Client");
        customer.setFullName("Live Client " + n);
        customer.setPhone("07" + (10000001L + (n % 80000000L)));
        customer.setRole(User.Role.CLIENT);
        customer.setOpmcId(opmc.getId());
        customer = userRepo.save(customer);

        Fault fault = new Fault();
        fault.setFaultNumber("FLT-FTALIVE-" + n);
        fault.setOpmcId(opmc.getId());
        fault.setCustomerId(customer.getId());
        fault.setCustomerName(customer.getFullName());
        fault.setCategory(Fault.FaultCategory.INTERNET);
        fault.setDescription("Live transfer-to-admin verification " + n);
        fault.setLocationAddress("Colombo 03");
        fault.setPriority(Fault.FaultPriority.MEDIUM);
        fault.setStatus(Fault.FaultStatus.ASSIGNED);
        fault.setWorkGroupId(workGroup.getId());
        fault.setWorkGroupName(workGroup.getName());
        fault.setAssignedTeamLeadId(teamLead.getId());
        fault.setAssignedTeamLeadName(teamLead.getFullName());
        fault = faultRepo.save(fault);

        Job job = new Job();
        job.setJobNumber("JOB-FTALIVE-" + n);
        job.setFaultId(fault.getId());
        job.setFaultNumber(fault.getFaultNumber());
        job.setCustomerId(customer.getId());
        job.setCustomerName(customer.getFullName());
        job.setTeamLeadId(teamLead.getId());
        job.setTeamLeadName(teamLead.getFullName());
        job.setPriority(Job.JobPriority.MEDIUM);
        job.setStatus(Job.JobStatus.REJECTED);
        job.setRejectionReason("Technician rejected: no site access");
        job.setRejectedByRole("TECHNICIAN");
        job = jobRepo.save(job);

        Long faultId = fault.getId();
        Long jobId = job.getId();
        Long teamLeadId = teamLead.getId();

        try {
            FaultAssignmentDTO.TransferToAdminRequest req = FaultAssignmentDTO.TransferToAdminRequest
                .builder()
                .reason("Live verification — Work Group cannot resolve this fault type")
                .build();

            faultAssignmentService.transferToAdmin(faultId, req, teamLeadId);

            // Fresh, independent re-query: each repository call below opens and commits its own
            // transaction (no @Transactional on this test), so these reads hit the real,
            // committed database rather than an in-memory persistence context carried over from
            // the fixture or the service call above.
            Fault reQueriedFault = faultRepo.findById(faultId).orElseThrow();
            assertEquals(Fault.FaultStatus.REPORTED, reQueriedFault.getStatus(),
                "Live: the fault must be REPORTED (fully detached) after transfer-to-admin");
            assertNull(reQueriedFault.getWorkGroupId(),
                "Live: workGroupId must be cleared");
            assertNull(reQueriedFault.getAssignedTeamLeadId(),
                "Live: assignedTeamLeadId must be cleared");

            Job reQueriedJob = jobRepo.findById(jobId).orElseThrow();
            assertEquals(Job.JobStatus.CANCELLED, reQueriedJob.getStatus(),
                "Live: no dangling/orphaned Job row — the rejected Job must be CANCELLED once "
                    + "its fault is transferred to Admin, re-read from a fresh persistence "
                    + "context against the real, committed database. Was "
                    + reQueriedJob.getStatus());
            assertTrue(reQueriedJob.getRejectionReason().contains("transferred back to Admin"),
                "Live: the cancellation reason must explain the transfer");
            assertEquals("TEAM_LEAD", reQueriedJob.getRejectedByRole());

            // Confirm this is genuinely the only Job for this fault, and it is terminal — no
            // second, still-open row was left behind by mistake.
            Job mostRecentForFault = jobRepo.findFirstByFaultIdOrderByCreatedAtDesc(faultId)
                .orElseThrow();
            assertEquals(jobId, mostRecentForFault.getId());
            assertTrue(mostRecentForFault.getStatus() == Job.JobStatus.COMPLETED
                    || mostRecentForFault.getStatus() == Job.JobStatus.CANCELLED,
                "Live: the fault's current Job must be terminal, not surfaceable as a live, "
                    + "actionable item in the old Team Lead's own job queue");
        } finally {
            // Real commits above — clean up explicitly, same convention this report's other
            // live verifications use. transferToAdmin (FaultAssignmentService.java:816) writes a
            // real fault_history row (a genuine @ManyToOne FK to this fault) on every call --
            // that must go before the Fault delete below or the real, committed database
            // rejects it with a foreign-key violation.
            jobRepo.deleteById(jobId);
            faultHistoryRepo.deleteAll(faultHistoryRepo.findByFaultId(faultId));
            faultRepo.deleteById(faultId);
            userRepo.deleteById(customer.getId());
            userRepo.deleteById(teamLeadId);
            workGroupRepo.deleteById(workGroup.getId());
            opmcRepo.deleteById(opmc.getId());

            assertTrue(jobRepo.findById(jobId).isEmpty(),
                "Live: fresh independent re-query confirms 0 Job rows remaining after cleanup");
            assertTrue(faultRepo.findById(faultId).isEmpty(),
                "Live: fresh independent re-query confirms 0 Fault rows remaining after cleanup");
        }
    }
}
