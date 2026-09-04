package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.MaterialUsageRequest;
import lk.slt.fieldops.dto.UpdateJobRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.MaterialUsage;
import lk.slt.fieldops.entity.StockTransaction;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * JOB-005, JOB-007 and JOB-010 (03_JOB_LIFECYCLE, FR-7 / FR-8 / FR-9) — the three
 * {@link JobService} behaviours the sheet specifies as unit tests: mandatory rejection reason,
 * completion blocked without an after-photo, and material recording decrementing stock.
 *
 * <p><b>API naming.</b> The sheet names {@code jobService.rejectJob(id, userId, reason)} and
 * {@code jobService.completeJob(id, notes)}. Neither exists as a standalone method: both rejection
 * and completion go through the single {@link JobService#updateJobStatus} entry point with
 * {@code newStatus} of {@code REJECTED} / {@code COMPLETED} (see {@code JobController} PATCH
 * {@code /api/jobs/{id}/status}). The same guards the sheet describes live in that method, so it is
 * what these tests drive.</p>
 *
 * <p><b>Exception type.</b> The sheet asserts {@code ValidationException}. No such type exists in
 * this codebase; {@code JobService} signals business-rule violations with a plain
 * {@link RuntimeException}, which {@code GlobalExceptionHandler.handleRuntime} maps to HTTP 400 —
 * the same contract the sheet's Expected Result describes. These tests assert the exception AND its
 * message, so they still fail if the guard silently disappears.</p>
 *
 * <p><b>Rejected status.</b> The sheet says a rejected job becomes {@code CANCELLED} and the fault
 * resets to {@code OPEN}. The implemented model distinguishes the two: a technician-rejected job is
 * {@code REJECTED} (it stays actionable in the Team Lead's "Needs Attention" queue and can go back
 * to {@code PENDING}), while {@code CANCELLED} is terminal; and the fault resets to
 * {@code ASSIGNED} (the enum equivalent of the sheet's "OPEN" for a fault that still has a Team
 * Lead). Asserted as implemented, with the fault reset covered end-to-end in
 * {@code FaultStatusResetTest} (JOB-026).</p>
 *
 * <p>Pure Mockito unit test (no Spring, no MySQL), matching the module convention established by
 * {@code JobServiceBodDuplicateTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobServiceTest {

    @Mock private DaySessionRepository       sessionRepo;
    @Mock private DaySessionMemberRepository memberRepo;
    @Mock private JobRepository              jobRepo;
    @Mock private CheckInOutRepository       checkInOutRepo;
    @Mock private MaterialUsageRepository    materialUsageRepo;
    @Mock private UserRepository             userRepo;
    @Mock private MaterialRepository         materialRepo;
    @Mock private FaultRepository            faultRepo;
    @Mock private FaultHistoryRepository     faultHistoryRepo;
    @Mock private MaterialRequestRepository  materialRequestRepo;
    @Mock private NotificationService        notificationService;

    /**
     * Not a JobService collaborator — declared so the JOB-010 assertion can state precisely what
     * "stock decremented" would have to touch if it were implemented.
     */
    @Mock private StockTransactionRepository stockTxnRepo;

    private JobService jobService;

    private static final Long JOB_ID       = 42L;
    private static final Long TECH_ID      = 5L;
    private static final Long TEAM_LEAD_ID = 9L;

    @BeforeEach
    void setUp() {
        jobService = new JobService(sessionRepo, memberRepo, jobRepo, checkInOutRepo,
            materialUsageRepo, userRepo, materialRepo, faultRepo, faultHistoryRepo,
            materialRequestRepo, notificationService);

        User technician = new User();
        technician.setId(TECH_ID);
        technician.setFullName("Tech Nimal");
        technician.setRole(User.Role.TECHNICIAN);
        when(userRepo.findById(TECH_ID)).thenReturn(Optional.of(technician));

        User teamLead = new User();
        teamLead.setId(TEAM_LEAD_ID);
        teamLead.setFullName("Lead Kamal");
        teamLead.setRole(User.Role.TEAM_LEAD);
        teamLead.setFcmToken("tl-fcm-token");
        when(userRepo.findById(TEAM_LEAD_ID)).thenReturn(Optional.of(teamLead));

        when(jobRepo.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Job jobInStatus(Job.JobStatus status) {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setJobNumber("JOB-2026-00042");
        job.setFaultId(1L);
        job.setTeamLeadId(TEAM_LEAD_ID);
        job.setTechnicianId(TECH_ID);
        job.setCustomerId(4242L);
        job.setStatus(status);
        return job;
    }

    private UpdateJobRequest request(String newStatus) {
        UpdateJobRequest req = new UpdateJobRequest();
        req.setNewStatus(newStatus);
        return req;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOB-005 — reject requires a reason
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void rejectJob_nullReason_throwsValidation() {
        Job pending = jobInStatus(Job.JobStatus.PENDING);
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(pending));

        Fault fault = new Fault();
        fault.setId(1L);
        fault.setStatus(Fault.FaultStatus.IN_PROGRESS);
        when(faultRepo.findById(1L)).thenReturn(Optional.of(fault));

        // ── Step 1: reject with no reason must be refused ────────────────────────────────
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> jobService.updateJobStatus(JOB_ID, request("REJECTED"), TECH_ID));
        assertTrue(ex.getMessage().toLowerCase().contains("reason"),
            "The refusal must say a reason is required. Was: " + ex.getMessage());
        assertEquals(Job.JobStatus.PENDING, pending.getStatus(),
            "A refused rejection must leave the job's status untouched");
        verify(jobRepo, never()).save(any(Job.class));

        // A blank (whitespace-only) reason is the same violation, not a loophole.
        UpdateJobRequest blank = request("REJECTED");
        blank.setReason("   ");
        assertThrows(RuntimeException.class,
            () -> jobService.updateJobStatus(JOB_ID, blank, TECH_ID));
        verify(jobRepo, never()).save(any(Job.class));

        // ── Step 2: a real reason succeeds ───────────────────────────────────────────────
        UpdateJobRequest valid = request("REJECTED");
        valid.setReason("Too far");
        Job rejected = jobService.updateJobStatus(JOB_ID, valid, TECH_ID);

        // ── Step 3: the job is closed out as REJECTED, carrying the reason ───────────────
        assertEquals(Job.JobStatus.REJECTED, rejected.getStatus(),
            "A technician-rejected job is REJECTED (CANCELLED is the separate, terminal state)");
        assertEquals("Too far", rejected.getRejectionReason());
        assertEquals("TECHNICIAN", rejected.getRejectedByRole());
        assertNull(rejected.getTechnicianId(),
            "Rejecting returns the job to the team lead pool, so the technician is cleared");

        // ── Step 4: the linked fault is pulled back out of IN_PROGRESS ───────────────────
        assertEquals(Fault.FaultStatus.ASSIGNED, fault.getStatus(),
            "The fault must not stay IN_PROGRESS once nobody is working the job");
        verify(faultRepo, times(1)).save(fault);

        // ── Step 5: the Team Lead who owns the job is notified ───────────────────────────
        // (The sheet says "admin notified"; the implemented recipient is the job's Team Lead,
        //  who is the one holding the Needs Attention queue this job lands in.)
        verify(notificationService, times(1))
            .notifyJobRejectedToTeamLead(any(), any(), eq("JOB-2026-00042"), eq(JOB_ID), eq("Too far"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOB-007 — completion blocked without an after-photo
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void completeJob_noAfterPhoto_throwsValidation() {
        Job inProgress = jobInStatus(Job.JobStatus.IN_PROGRESS);
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(inProgress));

        Fault fault = new Fault();
        fault.setId(1L);
        fault.setCustomerId(4242L);
        fault.setFaultNumber("FLT-2026-00001");
        fault.setStatus(Fault.FaultStatus.IN_PROGRESS);
        when(faultRepo.findById(1L)).thenReturn(Optional.of(fault));

        // ── Step 1: complete with no after-photo → refused ───────────────────────────────
        UpdateJobRequest noPhoto = request("COMPLETED");
        noPhoto.setCompletionRemarks("Fixed");
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> jobService.updateJobStatus(JOB_ID, noPhoto, TECH_ID));
        assertTrue(ex.getMessage().toLowerCase().contains("photo"),
            "The refusal must name the missing after-photo. Was: " + ex.getMessage());
        assertEquals(Job.JobStatus.IN_PROGRESS, inProgress.getStatus(),
            "A refused completion must leave the job IN_PROGRESS");
        assertNull(inProgress.getCompletedAt());
        verify(jobRepo, never()).save(any(Job.class));

        // A blank photo list is the same violation.
        UpdateJobRequest blankPhoto = request("COMPLETED");
        blankPhoto.setCompletionPhotoUrls("  ");
        assertThrows(RuntimeException.class,
            () -> jobService.updateJobStatus(JOB_ID, blankPhoto, TECH_ID));
        verify(jobRepo, never()).save(any(Job.class));

        // ── Steps 2-3: with an after-photo attached, completion succeeds ─────────────────
        UpdateJobRequest withPhoto = request("COMPLETED");
        withPhoto.setCompletionRemarks("Fixed");
        withPhoto.setCauseOfFault("Damaged drop wire");
        withPhoto.setCompletionPhotoUrls("/uploads/photos/after-1.jpg");
        Job completed = jobService.updateJobStatus(JOB_ID, withPhoto, TECH_ID);

        assertEquals(Job.JobStatus.COMPLETED, completed.getStatus());
        assertNotNull(completed.getCompletedAt(), "completed_at must be stamped on completion");
        assertEquals("/uploads/photos/after-1.jpg", completed.getCompletionPhotoUrls());
        assertEquals(Fault.FaultStatus.COMPLETED, fault.getStatus(),
            "Completion must sync back to the linked fault");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOB-010 — recording materials must decrement stock
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The sheet's step is {@code recordMaterials(42L, [{matId:1,qty:2},{matId:3,qty:1}])}. The real
     * API is one material per call ({@link JobService#logMaterial}), so the two-material batch is
     * driven as two calls — the behaviour under test (stock movement per material) is identical.
     */
    @Test
    void recordMaterials_stockDecremented() {
        Job inProgress = jobInStatus(Job.JobStatus.IN_PROGRESS);
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(inProgress));
        when(materialUsageRepo.save(any(MaterialUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        Material cable = Material.builder()
            .id(1L)
            .name("Fiber Optic Cable")
            .currentStock(new BigDecimal("10"))
            .minimumThreshold(new BigDecimal("5"))
            .build();

        Material connector = Material.builder()
            .id(3L)
            .name("RJ45 Connector")
            .currentStock(new BigDecimal("4"))
            .minimumThreshold(new BigDecimal("5"))
            .build();

        when(materialRepo.findById(1L)).thenReturn(Optional.of(cable));
        when(materialRepo.findById(3L)).thenReturn(Optional.of(connector));

        // ── Step 1: log both materials against the job ───────────────────────────────────
        MaterialUsageRequest useCable = new MaterialUsageRequest();
        useCable.setMaterialId(1L);
        useCable.setQuantityUsed(new BigDecimal("2"));
        useCable.setChargeType("FOC");

        MaterialUsageRequest useConnector = new MaterialUsageRequest();
        useConnector.setMaterialId(3L);
        useConnector.setQuantityUsed(new BigDecimal("1"));
        useConnector.setChargeType("FOC");

        MaterialUsage first  = jobService.logMaterial(JOB_ID, useCable, TECH_ID);
        MaterialUsage second = jobService.logMaterial(JOB_ID, useConnector, TECH_ID);

        // Both usages are recorded against the job — this part is implemented.
        verify(materialUsageRepo, times(2)).save(any(MaterialUsage.class));
        assertEquals(1L, first.getMaterialId());
        assertEquals(new BigDecimal("2"), first.getQuantityUsed());
        assertEquals(3L, second.getMaterialId());
        assertEquals(new BigDecimal("1"), second.getQuantityUsed());

        assertAll("material usage must move stock, not just record a usage row",

            // ── Step 2: a stock transaction per material ─────────────────────────────────
            () -> verify(stockTxnRepo, times(2)).save(any(StockTransaction.class)),

            // ── Steps 3-4: on-hand stock actually decremented ────────────────────────────
            () -> assertEquals(0, new BigDecimal("8").compareTo(cable.getCurrentStock()),
                "Material 1 started at 10 and 2 were used, so stock must be 8. Was: "
                    + cable.getCurrentStock()),
            () -> assertEquals(0, new BigDecimal("3").compareTo(connector.getCurrentStock()),
                "Material 3 started at 4 and 1 was used, so stock must be 3. Was: "
                    + connector.getCurrentStock()),
            () -> verify(materialRepo, atLeastOnce()).save(any(Material.class)),

            // ── Step 5: low-stock alert once a material drops below its minimum ──────────
            () -> verify(notificationService, atLeastOnce())
                .notifyUser(any(), any(), any(), any(), any(), any(), any())
        );
    }

    /** Recording materials against a closed job stays refused (guard must not regress). */
    @Test
    void recordMaterials_onCompletedJob_isRefused() {
        Job completed = jobInStatus(Job.JobStatus.COMPLETED);
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(completed));

        MaterialUsageRequest req = new MaterialUsageRequest();
        req.setMaterialId(1L);
        req.setQuantityUsed(new BigDecimal("2"));

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> jobService.logMaterial(JOB_ID, req, TECH_ID));
        assertTrue(ex.getMessage().contains("COMPLETED"), ex.getMessage());
        verify(materialUsageRepo, never()).save(any(MaterialUsage.class));
    }
}
