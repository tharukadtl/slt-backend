package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.AssignFaultRequest;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.dto.UpdateFaultRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CauseOfFaultRepository;
import lk.slt.fieldops.repository.CircuitRepository;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultNoteRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FAULT-015 (02_FAULT_TRACKING, FR-4) — every fault status change must leave an audit row in
 * {@code fault_history}, carrying the correct previous and new status.
 *
 * <p>Mockito unit test with {@code FaultHistoryRepository} mocked, per the row's Pre-Conditions
 * and the module's service-test convention ({@code JobServiceTest}).</p>
 *
 * <p><b>Naming.</b> The sheet calls the operation {@code assignFault(1L, 5L)}. The method on
 * {@link FaultService} is {@code assignToTeamLead(faultId, AssignFaultRequest, adminId, adminName,
 * teamLeadName)} — same operation, and the one that writes the history row. (The separate
 * {@code FaultAssignmentService.assignFault} is the assignment-workflow entry point used by
 * {@code POST /api/faults/{id}/assign}; the row maps to {@code FaultServiceTest}, so
 * {@link FaultService} is what is exercised here.)</p>
 *
 * <p><b>Status naming.</b> The row writes the pre-assignment status as {@code 'OPEN'}. The
 * persisted enum value is {@code REPORTED}; {@code OPEN} is only the client-facing alias produced
 * by {@code FaultDTO.getStatusDisplay()}. The audit row records the enum, so {@code REPORTED} is
 * what is asserted.</p>
 */
@ExtendWith(MockitoExtension.class)
class FaultServiceTest {

    @Mock private FaultRepository        faultRepo;
    @Mock private FaultHistoryRepository historyRepo;
    @Mock private FaultNoteRepository    noteRepo;
    @Mock private UserRepository         userRepo;
    @Mock private NotificationService    notificationService;
    @Mock private ExchangeService        exchangeService;
    @Mock private CircuitRepository      circuitRepo;
    @Mock private CauseOfFaultRepository causeOfFaultRepo;

    private FaultService faultService;

    private static final Long FAULT_ID   = 1L;
    private static final Long TEAM_LEAD_ID = 5L;
    private static final Long ADMIN_ID   = 900L;

    @BeforeEach
    void setUp() {
        faultService = new FaultService(faultRepo, historyRepo, noteRepo, userRepo,
            notificationService, exchangeService, circuitRepo, causeOfFaultRepo);
    }

    private Fault reportedFault() {
        Fault fault = new Fault();
        fault.setId(FAULT_ID);
        fault.setFaultNumber("FLT-2026-00001");
        fault.setOpmcId(1L);
        fault.setCustomerId(4242L);
        fault.setCustomerName("Test Client");
        fault.setCategory(Fault.FaultCategory.INTERNET);
        fault.setDescription("No internet since 08:00");
        fault.setPriority(Fault.FaultPriority.HIGH);
        fault.setStatus(Fault.FaultStatus.REPORTED);
        return fault;
    }

    // H1b: the defensive null-check inside reportFault (skip nearest-Exchange derivation when
    // GPS is absent) is unreachable through the real POST /api/faults endpoint today, since
    // ReportFaultRequest's latitude/longitude are @NotNull -- see
    // H1bNearestExchangeIntegrationTest.reportFault_noGps_rejectedByValidation_beforeAnyMatchIsAttempted,
    // which confirms that directly. This test covers the branch itself at the unit level, where
    // controller-level @Valid doesn't apply, so the guard's correctness isn't left completely
    // unverified just because today's only caller happens to make it unreachable.
    @Test
    void reportFault_nullLatLon_neverCallsExchangeService() {
        when(faultRepo.save(any(Fault.class))).thenAnswer(inv -> {
            Fault f = inv.getArgument(0);
            f.setId(FAULT_ID);
            return f;
        });

        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("INTERNET");
        req.setDescription("No GPS supplied");
        req.setOpmcId(1L);
        req.setLatitude(null);
        req.setLongitude(null);

        faultService.reportFault(req, 4242L, "Test Client", "0771234567");

        verifyNoInteractions(exchangeService);
    }

    @Test
    void assignFault_createsHistoryRow() {
        Fault fault = reportedFault();
        when(faultRepo.findById(FAULT_ID)).thenReturn(Optional.of(fault));
        when(faultRepo.save(any(Fault.class))).thenAnswer(inv -> inv.getArgument(0));

        AssignFaultRequest assign = new AssignFaultRequest();
        assign.setTeamLeadId(TEAM_LEAD_ID);
        assign.setNotes("Nearest team");

        // ── Step 1: assign the fault to a team lead ───────────────────────────────────────
        faultService.assignToTeamLead(FAULT_ID, assign, ADMIN_ID, "Admin Amelia", "Lead Kamal");

        // ── Step 2: a history row was written ────────────────────────────────────────────
        ArgumentCaptor<FaultHistory> captor = ArgumentCaptor.forClass(FaultHistory.class);
        verify(historyRepo, times(1)).save(captor.capture());
        FaultHistory assignRow = captor.getValue();

        // ── Steps 3-4: it records the right event and the right status pair ──────────────
        assertEquals("STATUS_CHANGED", assignRow.getEventType(),
            "Every status change must be audited as STATUS_CHANGED");
        assertEquals("REPORTED", assignRow.getPreviousValue(),
            "Previous status must be the pre-assignment enum REPORTED (client-facing alias: OPEN)");
        assertEquals("ASSIGNED", assignRow.getNewValue(),
            "New status must be ASSIGNED");
        assertEquals(fault.getFaultNumber(), assignRow.getFaultNumber(),
            "The audit row must be traceable to its fault");
        assertSame(fault, assignRow.getFault());
        assertFalse(assignRow.getIsSystem(),
            "An admin-initiated assignment is not a system event");
        assertTrue(assignRow.getDescription().contains("Admin Amelia"),
            "The audit row must name the actor, was: " + assignRow.getDescription());

        // The assignment itself landed on the fault.
        assertEquals(Fault.FaultStatus.ASSIGNED, fault.getStatus());
        assertEquals(TEAM_LEAD_ID, fault.getAssignedTeamLeadId());
        assertNotNull(fault.getAssignedAt());
        assertNotNull(fault.getDueDate(), "A HIGH-priority fault must get an SLA due date");

        // ── Step 5: completing the fault writes further history rows ─────────────────────
        // ASSIGNED -> IN_PROGRESS -> COMPLETED; FaultService.validateTransition permits no
        // direct ASSIGNED -> COMPLETED jump, so completion is two more audited transitions.
        UpdateFaultRequest inProgress = new UpdateFaultRequest();
        inProgress.setNewStatus("IN_PROGRESS");
        faultService.updateStatus(FAULT_ID, inProgress, TEAM_LEAD_ID, "Tech Nimal",
            FaultHistory.ChangedByRole.TECHNICIAN);

        UpdateFaultRequest completed = new UpdateFaultRequest();
        completed.setNewStatus("COMPLETED");
        completed.setCauseOfFault("Damaged drop wire");
        completed.setCompletionRemarks("Replaced 10m cable, tested OK");
        faultService.updateStatus(FAULT_ID, completed, TEAM_LEAD_ID, "Tech Nimal",
            FaultHistory.ChangedByRole.TECHNICIAN);

        verify(historyRepo, times(3)).save(captor.capture());
        List<FaultHistory> rows = captor.getAllValues();

        FaultHistory startRow    = rows.get(rows.size() - 2);
        FaultHistory completeRow = rows.get(rows.size() - 1);

        assertEquals("ASSIGNED",    startRow.getPreviousValue());
        assertEquals("IN_PROGRESS", startRow.getNewValue());
        assertEquals("STATUS_CHANGED", startRow.getEventType());

        assertEquals("IN_PROGRESS", completeRow.getPreviousValue(),
            "The completion row must chain from the immediately preceding status");
        assertEquals("COMPLETED",   completeRow.getNewValue());
        assertEquals("STATUS_CHANGED", completeRow.getEventType());

        assertEquals(Fault.FaultStatus.COMPLETED, fault.getStatus());
        assertNotNull(fault.getCompletedAt());
    }

    // JOB-001's original scenario (Admin assigns a fault directly to a Technician, which used to
    // create a Job via JobCreationService.createJobForFaultAssignment) no longer applies: SRS 5.5.1
    // (v1.9) retired direct Admin-to-Technician assignment entirely. A fault is now assigned to a
    // Work Group (FaultAssignmentServiceTest / FaultAssignmentWorkGroupTest cover that path), and a
    // Job is only ever created once a Team Lead dispatches to a Technician within their own Work
    // Group via JobService.createJob (JobServiceTest). JobCreationService itself was deleted, not
    // just deprecated, so there is nothing left here for this test to exercise.
}
