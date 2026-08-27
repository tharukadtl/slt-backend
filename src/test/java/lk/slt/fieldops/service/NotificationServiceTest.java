package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.dto.MaterialRequestDTO;
import lk.slt.fieldops.dto.ReviewPaymentRequest;
import lk.slt.fieldops.dto.UpdateFaultRequest;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.MaterialRequest;
import lk.slt.fieldops.entity.Notification;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.PaymentApproval;
import lk.slt.fieldops.entity.StockTransaction;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.CauseOfFaultRepository;
import lk.slt.fieldops.repository.CircuitRepository;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultNoteRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.MaterialCategoryRepository;
import lk.slt.fieldops.repository.MaterialRepository;
import lk.slt.fieldops.repository.MaterialRequestRepository;
import lk.slt.fieldops.repository.NotificationRepository;
import lk.slt.fieldops.repository.PaymentApprovalRepository;
import lk.slt.fieldops.repository.PaymentRepository;
import lk.slt.fieldops.repository.StockTransactionRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupAllocationRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FAULT-008 (02_FAULT_TRACKING, FR-5) — a fault status change must push an FCM notification to the
 * client who reported it, and persist the matching in-app notification row.
 *
 * <p>Mockito unit test (the module's convention for service-level checks, see
 * {@code JobServiceTest}) rather than a live Firebase call: {@code sendPush}
 * short-circuits whenever {@code app.firebase.enabled} is false or no {@code FirebaseApp} is
 * initialised, so the observable, assertable contract is the {@link NotificationService} call that
 * a status change is supposed to make.</p>
 *
 * <p><b>What this row exposes.</b> {@code FaultService.updateStatus} writes the new status and a
 * {@code fault_history} row, then returns — it never touches {@link NotificationService}. The only
 * fault-lifecycle notification {@code FaultService} raises is {@code notifyFaultReported}, sent to
 * ADMINs at report time. {@code NotificationService.notifyFaultAssigned} exists but has no caller
 * anywhere in the codebase, and the client is only ever notified on completion, from
 * {@code JobService} (not from a fault status change). So sub-check 1 below is expected to be red;
 * sub-checks 2 and 3 confirm the notification plumbing itself is sound, which localises the gap to
 * the missing call rather than a broken notifier.</p>
 *
 * <p>All three sub-checks run under {@code assertAll} so each produces its own verdict.</p>
 *
 * <p><b>Also hosts RES-015</b> (05_RESOURCE_MGMT, FR-15) — material-request approval/rejection
 * notifying the requesting technician — which the sheet maps to
 * {@code NotificationServiceTest::requestStatusChange_techNotified}. See that method's own javadoc
 * for its substitutions.</p>
 *
 * <p><b>And two rows of 08_NOTIFICATIONS (FR-21), added 2026-08-12</b>, which the sheet maps to this
 * same class: NOTIF-001 {@link #faultAssigned_triggersFcmToTech()} and NOTIF-002
 * {@link #paymentApproved_fcmToTeamLead()}. NOTIF-003 (FCM on material-request status change) is
 * <b>not</b> written again — it is the same assertion as RES-015 above, so that row was repointed to
 * {@link #requestStatusChange_techNotified()} rather than duplicated.</p>
 *
 * <p><b>The shape all four notification rows in this class share.</b> Every one of the four event
 * sources — fault status change, fault assignment, material-request decision, payment review — is
 * expected by the sheet to reach the recipient through {@link NotificationService} (which does both
 * halves: an FCM push <i>and</i> a persisted {@code notifications} row). Only the fault-report and
 * job-assign paths actually do that. The other three either notify nobody or push an ephemeral
 * WebSocket frame that is lost on anyone who is offline. Each method below asserts the mechanism
 * that <i>does</i> exist (so a future fix cannot regress it) alongside the persisted row that does
 * not, and none of them asserts the absence, which would lock the gap in as correct behaviour.</p>
 *
 * <p><b>Why {@code fcmClient} is never mocked, despite the sheet's Assertion Code.</b> There is no
 * {@code FcmClient} type in this codebase and no {@code FcmMessage}. The push is
 * {@code NotificationService.sendPush(fcmToken, title, body)}, calling the static
 * {@code FirebaseMessaging.getInstance()} directly, and it short-circuits with a log line whenever
 * {@code app.firebase.enabled} is false or no {@code FirebaseApp} is initialised — which is always,
 * here. The assertable contract is therefore the {@link NotificationService} call and the row it
 * writes, which is what every method below verifies.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    // ── Collaborators of the notifier under test ──────────────────────────────────────────
    @Mock private NotificationRepository notificationRepo;

    // ── Collaborators of the fault path that is supposed to trigger it ────────────────────
    @Mock private FaultRepository        faultRepo;
    @Mock private FaultHistoryRepository historyRepo;
    @Mock private FaultNoteRepository    noteRepo;
    @Mock private UserRepository         userRepo;
    @Mock private NotificationService    notificationServiceMock;
    @Mock private ExchangeService        exchangeService;
    @Mock private CircuitRepository      circuitRepo;
    @Mock private CauseOfFaultRepository causeOfFaultRepo;

    // ── Collaborators of the material-request path (RES-015) ──────────────────────────────
    @Mock private MaterialRequestRepository  materialRequestRepo;
    @Mock private MaterialRepository         materialRepo;
    @Mock private MaterialCategoryRepository materialCategoryRepo;
    @Mock private StockTransactionRepository stockTransactionRepo;
    @Mock private WebSocketEventPublisher    webSocketEventPublisher;
    @Mock private WorkGroupAllocationRepository workGroupAllocationRepo;

    // ── Collaborators of the fault-assignment path (NOTIF-001) ────────────────────────────
    @Mock private WorkGroupRepository workGroupRepository;

    // ── Collaborators of the payment-review path (NOTIF-002) ──────────────────────────────
    @Mock private PaymentRepository         paymentRepo;
    @Mock private PaymentApprovalRepository approvalRepo;
    @Mock private JobRepository             jobRepo;

    private FaultService faultService;

    private static final Long FAULT_ID  = 1042L;
    private static final Long CLIENT_ID = 5501L;
    private static final Long ACTOR_ID  = 7701L;

    @BeforeEach
    void setUp() {
        faultService = new FaultService(faultRepo, historyRepo, noteRepo, userRepo,
            notificationServiceMock, exchangeService, circuitRepo, causeOfFaultRepo);
    }

    private Fault reportedFault() {
        Fault fault = new Fault();
        fault.setId(FAULT_ID);
        fault.setFaultNumber("FLT-2026-01042");
        fault.setOpmcId(1L);
        fault.setCustomerId(CLIENT_ID);
        fault.setCustomerName("Test Client");
        fault.setCategory(Fault.FaultCategory.INTERNET);
        fault.setDescription("No internet since 08:00");
        fault.setPriority(Fault.FaultPriority.HIGH);
        fault.setStatus(Fault.FaultStatus.REPORTED);
        return fault;
    }

    @Test
    void faultStatusChange_triggersFcm() {
        // ── Arrange: a REPORTED fault owned by CLIENT_ID ──────────────────────────────────
        Fault fault = reportedFault();
        when(faultRepo.findById(FAULT_ID)).thenReturn(Optional.of(fault));
        when(faultRepo.save(any(Fault.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateFaultRequest request = new UpdateFaultRequest();
        request.setNewStatus("ASSIGNED");

        // ── Act: step 1 — the status change the row describes ─────────────────────────────
        faultService.updateStatus(FAULT_ID, request, ACTOR_ID, "Admin Amelia",
            FaultHistory.ChangedByRole.ADMIN);

        // The status change itself must have happened, whatever the notification outcome.
        assertEquals(Fault.FaultStatus.ASSIGNED, fault.getStatus(),
            "Precondition for this row: the status change must be applied");
        verify(historyRepo).save(any(FaultHistory.class));

        // A real NotificationService (repo mocked) for the plumbing sub-checks.
        NotificationService realNotifier = new NotificationService(notificationRepo);
        when(notificationRepo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        assertAll("fault status change notifies the reporting client",

            // ── Steps 2-3: the client must be notified about the status change ───────────
            () -> verify(notificationServiceMock, atLeastOnce()).notifyUser(
                eq(CLIENT_ID), any(), any(), any(), any(), eq(FAULT_ID), eq("FAULT")),

            // ── Step 4a: the notifier does persist a row when it IS called ───────────────
            () -> {
                realNotifier.notifyFaultAssigned(CLIENT_ID, "fcm-token-abc",
                    fault.getFaultNumber(), FAULT_ID);

                ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
                verify(notificationRepo, atLeastOnce()).save(saved.capture());
                Notification row = saved.getValue();

                assertEquals(CLIENT_ID, row.getRecipientId(),
                    "The notification must be addressed to the reporting client");
                assertEquals(Notification.NotificationType.FAULT_ASSIGNED, row.getType());
                assertEquals(FAULT_ID, row.getReferenceId(),
                    "The notification must reference the fault it is about");
                assertEquals("FAULT", row.getReferenceType());
                assertFalse(row.getIsRead(), "A new notification starts unread");
            },

            // ── Step 4b: the payload names the fault ─────────────────────────────────────
            () -> {
                ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
                verify(notificationRepo, atLeastOnce()).save(saved.capture());
                Notification row = saved.getValue();

                assertTrue(row.getBody().contains(fault.getFaultNumber()),
                    "The notification body must contain the fault identifier, was: " + row.getBody());
                assertNotNull(row.getTitle(), "The notification must carry a title");
                assertTrue(row.getTitle().toLowerCase().contains("fault"),
                    "The title should identify this as a fault update; the sheet expects "
                        + "'Fault Update' and the code sends '" + row.getTitle() + "'");
            }
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-015 (05_RESOURCE_MGMT, FR-15) — material request status change notifies the technician
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private static final Long MR_TECH_ID     = 5501L;
    private static final Long MR_ADMIN_ID    = 9901L;
    private static final Long MR_MATERIAL_ID = 5L;
    private static final Long MR_REQUEST_ID  = 77L;
    private static final int  MR_QUANTITY    = 3;
    private static final String MR_MATERIAL_NAME = "Fibre Drop Cable";

    /**
     * RES-015 — approving and rejecting a material request must each notify the technician who
     * raised it, with the notification type, the material name and quantity, and the requester as
     * recipient.
     *
     * <p><b>Collaborator naming vs. the sheet.</b> The sheet expects
     * {@code verify(notifService).send(argThat(n -> n.getType().equals("MATERIAL_REQUEST_APPROVED")
     * && n.getRecipientId().equals(techId)))}. {@link MaterialRequestService} does not use
     * {@link NotificationService} at all — it pushes a transient WebSocket frame via
     * {@code WebSocketEventPublisher.sendToUser(userId, title, message, type)}. Both mechanisms are
     * asserted: the WebSocket push (which does exist, and whose recipient/type are correct) and the
     * persisted {@code notifications} row (which the row's "Assert notification contains
     * materialName and quantity" and the recipient assertion both presuppose, and which is where
     * this is expected to be red).</p>
     *
     * <p><b>Why the rejected type is resolved by name.</b> {@code Notification.NotificationType} has
     * {@code MATERIAL_REQUEST_SUBMITTED} and {@code MATERIAL_REQUEST_APPROVED} but <b>no</b>
     * {@code MATERIAL_REQUEST_REJECTED} constant — {@code MaterialRequestService} pushes that value
     * as a bare String over the socket, which nothing validates. Referencing it as a constant would
     * not compile, so it is resolved with {@code valueOf} at runtime: the test still executes and
     * fails naming exactly what is missing, and passes unchanged once the constant lands. It
     * deliberately does not assert the constant's absence, which would lock the gap in.</p>
     */
    @Test
    void requestStatusChange_techNotified() {
        // ── Arrange ──────────────────────────────────────────────────────────────────────────
        MaterialRequestService requestService = new MaterialRequestService(
            materialRequestRepo, materialRepo, materialCategoryRepo, userRepo,
            stockTransactionRepo, webSocketEventPublisher, workGroupAllocationRepo);

        User technician = new User();
        technician.setId(MR_TECH_ID);
        technician.setFullName("Tech Nimal");
        technician.setRole(User.Role.TECHNICIAN);
        when(userRepo.findById(MR_TECH_ID)).thenReturn(Optional.of(technician));

        User admin = new User();
        admin.setId(MR_ADMIN_ID);
        admin.setFullName("Admin Amelia");
        admin.setRole(User.Role.ADMIN);
        when(userRepo.findById(MR_ADMIN_ID)).thenReturn(Optional.of(admin));

        Material material = new Material();
        material.setId(MR_MATERIAL_ID);
        material.setName(MR_MATERIAL_NAME);
        material.setSku("MAT-CBL-001");
        material.setUnit("m");
        material.setUnitPrice(BigDecimal.valueOf(120));
        material.setMinimumThreshold(BigDecimal.TEN);
        material.setCurrentStock(BigDecimal.valueOf(20));
        when(materialRepo.findById(MR_MATERIAL_ID)).thenReturn(Optional.of(material));
        when(materialRepo.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockTransactionRepo.save(any(StockTransaction.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(materialRequestRepo.save(any(MaterialRequest.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(notificationRepo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        MaterialRequest approvedTarget = MaterialRequest.builder()
            .id(MR_REQUEST_ID)
            .requestNumber("MR-2026-00077")
            .requestedBy(MR_TECH_ID)
            .requestedByName("Tech Nimal")
            .status(MaterialRequest.RequestStatus.PENDING)
            .urgency("HIGH")
            .itemsData(MR_MATERIAL_ID + ":" + MR_QUANTITY + ":0")
            .totalEstimatedCost(360.0)
            .totalApprovedCost(0.0)
            .build();
        when(materialRequestRepo.findById(MR_REQUEST_ID)).thenReturn(Optional.of(approvedTarget));

        // ── Act: step 1 — approve ────────────────────────────────────────────────────────────
        requestService.approveRequest(MR_REQUEST_ID,
            MaterialRequestDTO.ApproveRequest.builder()
                .notes("Approved — in stock")
                .notifyRequester(true)
                .build(),
            MR_ADMIN_ID);

        // ── Act: step 2 — reject a second, independent request ───────────────────────────────
        MaterialRequest rejectedTarget = MaterialRequest.builder()
            .id(MR_REQUEST_ID + 1)
            .requestNumber("MR-2026-00078")
            .requestedBy(MR_TECH_ID)
            .requestedByName("Tech Nimal")
            .status(MaterialRequest.RequestStatus.PENDING)
            .urgency("NORMAL")
            .itemsData(MR_MATERIAL_ID + ":" + MR_QUANTITY + ":0")
            .build();
        when(materialRequestRepo.findById(MR_REQUEST_ID + 1))
            .thenReturn(Optional.of(rejectedTarget));

        requestService.rejectRequest(MR_REQUEST_ID + 1,
            MaterialRequestDTO.RejectRequest.builder()
                .reason("Out of stock")
                .notifyRequester(true)
                .build(),
            MR_ADMIN_ID);

        assertAll("both material-request decisions notify the requesting technician",

            // ── The mechanism that does exist: the live WebSocket push ──────────────────────
            () -> verify(webSocketEventPublisher).sendToUser(
                eq(String.valueOf(MR_TECH_ID)), any(), any(), eq("MATERIAL_REQUEST_APPROVED")),
            () -> verify(webSocketEventPublisher).sendToUser(
                eq(String.valueOf(MR_TECH_ID)), any(), any(), eq("MATERIAL_REQUEST_REJECTED")),

            // ── Step 1: the approval must leave a durable notification row ─────────────────
            () -> {
                ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
                verify(notificationRepo, atLeastOnce()).save(saved.capture());
                List<Notification> rows = saved.getAllValues();

                Notification approved = rows.stream()
                    .filter(n -> n.getType() == Notification.NotificationType
                        .MATERIAL_REQUEST_APPROVED)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                        "Approving a material request must persist a MATERIAL_REQUEST_APPROVED "
                            + "notification for the requester. MaterialRequestService only calls "
                            + "WebSocketEventPublisher.sendToUser, which pushes a transient frame "
                            + "over the socket and writes no notifications row — a technician who "
                            + "is offline when the admin approves is never told at all. Rows "
                            + "written: " + rows));

                // ── Steps 3-4: right recipient, and enough detail to act on ───────────────
                assertEquals(MR_TECH_ID, approved.getRecipientId(),
                    "The notification must be addressed to the technician who raised the request");
                assertTrue(approved.getBody() != null
                        && approved.getBody().contains(MR_MATERIAL_NAME),
                    "The notification must name the material, was: " + approved.getBody());
                assertTrue(approved.getBody() != null
                        && approved.getBody().contains(String.valueOf(MR_QUANTITY)),
                    "The notification must state the approved quantity, was: "
                        + approved.getBody());
            },

            // ── Step 2: the rejected side needs a type constant before it can be stored ────
            () -> {
                Notification.NotificationType rejectedType = assertDoesNotThrow(
                    () -> Notification.NotificationType.valueOf("MATERIAL_REQUEST_REJECTED"),
                    "Notification.NotificationType has MATERIAL_REQUEST_SUBMITTED and "
                        + "MATERIAL_REQUEST_APPROVED but no MATERIAL_REQUEST_REJECTED constant, "
                        + "so a rejection cannot be persisted as a notification at all — "
                        + "MaterialRequestService pushes the string over the socket and nothing "
                        + "validates it against the enum");

                ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
                verify(notificationRepo, atLeastOnce()).save(saved.capture());

                Notification rejected = saved.getAllValues().stream()
                    .filter(n -> n.getType() == rejectedType)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                        "Rejecting a material request must persist a notification for the "
                            + "requester, carrying the reason"));

                assertEquals(MR_TECH_ID, rejected.getRecipientId());
                assertTrue(rejected.getBody() != null
                        && rejected.getBody().contains("Out of stock"),
                    "The rejection notification must carry the reason, was: "
                        + rejected.getBody());
            }
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // 08_NOTIFICATIONS (FR-21) — NOTIF-001 and NOTIF-002
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Every {@code notifications} row the code under test wrote, without ever failing on "wanted but
     * not invoked" — so a missing row is reported by this class's own explanatory message rather
     * than by a bare Mockito verification error. {@code atLeast(0)} is a capture, not a demand.
     */
    private List<Notification> savedNotifications() {
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo, atLeast(0)).save(saved.capture());
        return saved.getAllValues();
    }

    private static final Long NA_TECH_ID  = 5L;
    private static final Long NA_ADMIN_ID = 9902L;

    /**
     * NOTIF-001 (FR-21) — assigning a fault to a Work Group must push an FCM notification to that
     * Work Group's Team Lead, titled for the assignment and carrying the fault reference, addressed
     * to the Team Lead and to nobody else.
     *
     * <p><b>Updated for Stage D (SRS 5.5.1, v1.9).</b> Direct Admin-to-Technician assignment was
     * retired: a fault is now assigned to a Work Group, and the notification target is that Work
     * Group's Team Lead, not a Technician directly. The underlying gap this test documents is
     * unchanged — only the recipient's role changed.</p>
     *
     * <p><b>Which "assign" this is.</b> The row's step 1 is {@code faultService.assignFault(1L, 5L)}.
     * {@code FaultService} has no {@code assignFault}; the real assignment path is
     * {@link FaultAssignmentService#assignFault} behind {@code POST /api/faults/{id}/assign}, which
     * is what this drives. Its request DTO defaults {@code notifyTeamLead} and
     * {@code notifyCustomer} to true, so the notification branch is entered exactly as a real admin
     * assignment enters it.</p>
     *
     * <p><b>Where this is expected to be red.</b> {@link FaultAssignmentService} does not depend on
     * {@link NotificationService} at all — its constructor arguments are the fault, user,
     * history and note repositories, {@link WebSocketEventPublisher} and {@code WorkGroupRepository}.
     * On assignment it pushes a transient WebSocket frame to the Team Lead and stops: no FCM push,
     * no {@code notifications} row. A Team Lead whose handset is not currently holding an open
     * socket to {@code /ws/notifications} is never told a fault was given to their Work Group, and
     * there is nothing to render in the in-app list afterwards. This is the same failure shape as
     * RES-015 above, on a different lifecycle stage — and it is <b>distinct from FAULT-008</b>
     * ({@link #faultStatusChange_triggersFcm()}), which is about a later status change notifying the
     * reporting <i>client</i>. That test also already proves
     * {@code NotificationService.notifyFaultAssigned} itself works when called, so this one does not
     * repeat the plumbing check; the gap here is purely the missing call.</p>
     *
     * <p>The WebSocket push that <i>does</i> exist is asserted first, so a future fix that adds the
     * persisted row cannot quietly drop the live one.</p>
     */
    @Test
    void faultAssigned_triggersFcmToTeamLead() {
        // ── Arrange: a REPORTED fault, the Work Group + Team Lead it is given to, and the admin ──
        FaultAssignmentService assignmentService = new FaultAssignmentService(
            faultRepo, userRepo, historyRepo, noteRepo, webSocketEventPublisher, workGroupRepository,
            new lk.slt.fieldops.shared.OpmcAccessGuard(userRepo));

        Fault fault = reportedFault();
        when(faultRepo.findById(FAULT_ID)).thenReturn(Optional.of(fault));
        when(faultRepo.save(any(Fault.class))).thenAnswer(inv -> inv.getArgument(0));

        User teamLead = new User();
        teamLead.setId(NA_TECH_ID);
        teamLead.setFullName("TL Nimal");
        teamLead.setRole(User.Role.TEAM_LEAD);
        teamLead.setFcmToken("fcm-token-tl-5");

        Opmc opmc = new Opmc();
        opmc.setId(1L);

        WorkGroup workGroup = new WorkGroup();
        workGroup.setId(200L);
        workGroup.setName("Colombo North Team");
        workGroup.setOpmc(opmc);
        workGroup.setTeamLead(teamLead);
        workGroup.setIsActive(true);
        when(workGroupRepository.findById(200L)).thenReturn(Optional.of(workGroup));

        User admin = new User();
        admin.setId(NA_ADMIN_ID);
        admin.setFullName("Admin Amelia");
        admin.setRole(User.Role.ADMIN);
        admin.setOpmcId(1L);
        when(userRepo.findById(NA_ADMIN_ID)).thenReturn(Optional.of(admin));

        when(notificationRepo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        // ── Act: step 1 — the assignment the row describes ───────────────────────────────────
        assignmentService.assignFault(FAULT_ID,
            FaultAssignmentDTO.AssignRequest.builder()
                .workGroupId(200L)
                .priority("HIGH")
                .build(),
            NA_ADMIN_ID);

        // Precondition for the row: the assignment itself must have happened.
        assertEquals(200L, fault.getWorkGroupId(),
            "Precondition for this row: the fault must actually be assigned to the Work Group");
        verify(historyRepo).save(any(FaultHistory.class));

        assertAll("fault assignment notifies the Work Group's Team Lead",

            // ── The mechanism that does exist: the live WebSocket push ──────────────────────
            () -> verify(webSocketEventPublisher).sendToUser(
                eq(String.valueOf(NA_TECH_ID)), any(), any(), eq("FAULT_ASSIGNED")),

            // ── Steps 2-4: an FCM/in-app notification addressed to the Team Lead ────────────
            () -> {
                List<Notification> rows = savedNotifications();

                Notification assigned = rows.stream()
                    .filter(n -> n.getType() == Notification.NotificationType.FAULT_ASSIGNED)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                        "Assigning a fault must push an FCM notification to the assigned Work "
                            + "Group's Team Lead and persist the matching notifications row. "
                            + "FaultAssignmentService.assignFault does not depend on "
                            + "NotificationService at all — it calls "
                            + "WebSocketEventPublisher.sendToUser(teamLeadId, 'New Fault in Your "
                            + "Work Group', ...) and stops, so a Team Lead who is not holding an "
                            + "open socket at that instant is never told, and nothing survives for "
                            + "the in-app list. NotificationService.notifyFaultAssigned(teamLeadId, "
                            + "fcmToken, faultNumber, faultId) already exists and works (see "
                            + "faultStatusChange_triggersFcm) — it simply has no caller. Rows "
                            + "written: " + rows));

                // Step 4: recipient must be the Team Lead, not the customer or the admin.
                assertEquals(NA_TECH_ID, assigned.getRecipientId(),
                    "The notification must be addressed to the Work Group's Team Lead");
                assertEquals(FAULT_ID, assigned.getReferenceId(),
                    "The notification must reference the fault it is about");
                assertEquals("FAULT", assigned.getReferenceType());

                // Step 3: the payload must name the assignment and identify the fault.
                assertNotNull(assigned.getTitle(), "The notification must carry a title");
                assertTrue(assigned.getTitle().toLowerCase().contains("assign"),
                    "The title must identify this as an assignment; the sheet expects "
                        + "'Fault Assigned' and the code sends '" + assigned.getTitle() + "'");
                assertTrue(assigned.getBody() != null
                        && (assigned.getBody().contains(fault.getFaultNumber())
                            || assigned.getBody().contains(String.valueOf(FAULT_ID))),
                    "The body must contain the fault identifier, was: " + assigned.getBody());
            }
        );
    }

    // ── NOTIF-002 fixtures ────────────────────────────────────────────────────────────────────

    private static final Long   NP_PAYMENT_ID  = 15L;
    private static final Long   NP_TEAM_LEAD_ID = 500L;
    private static final Long   NP_ADMIN_ID     = 9903L;
    private static final Long   NP_CUSTOMER_ID  = 6L;
    private static final String NP_AMOUNT       = "4750.00";

    /**
     * NOTIF-002 (FR-21) — approving a payment must push an FCM notification to the Team Lead who
     * submitted it, carrying the approved amount, addressed to that Team Lead.
     *
     * <p><b>Vocabulary vs. the sheet.</b> There is no {@code paymentService.approvePayment(id,
     * adminId, amount)}: approval is {@code PaymentService.reviewPayment(id, {decision:"APPROVED"},
     * adminId, adminName)} behind {@code PATCH /api/payments/{id}/review}, and the resulting status
     * is {@code FINAL}, not {@code APPROVED} — the same domain-vocabulary correction
     * {@code PaymentServiceTest} documents for the whole of 04_PAYMENT_FLOW.</p>
     *
     * <p><b>Relationship to PAY-006/008, which are resolved.</b> Those rows found that the
     * submitting Team Lead was not notified at all on the Approve and Adjust branches; the fix of
     * 2026-08-07 (Resolution Log #38) added {@code notifyTeamLead(...)} calls to both, and
     * {@code PaymentServiceTest::approvePayment_statusApproved} asserts that call. That helper is
     * {@code webSocketEventPublisher.sendToUser(teamLeadId, title, message, "PAYMENT_UPDATE")} and
     * nothing else — so the notification exists but is a transient socket frame. This row asks a
     * different question from PAY-006: not "is the Team Lead notified" (yes, now) but "is it an FCM
     * push that reaches them off-socket, and is it recorded" (no). {@link PaymentService} does not
     * depend on {@link NotificationService} at all, and {@code notifyPaymentApproved} /
     * {@code notifyPaymentRejected} — which exist and do both halves — have no caller anywhere.</p>
     */
    @Test
    void paymentApproved_fcmToTeamLead() {
        // ── Arrange: a DRAFT payment submitted by a Team Lead, awaiting admin review ─────────
        PaymentService paymentService = new PaymentService(
            paymentRepo, approvalRepo, jobRepo, faultRepo, webSocketEventPublisher);

        Payment payment = new Payment();
        payment.setId(NP_PAYMENT_ID);
        payment.setPaymentNumber("PAY-2026-00015");
        payment.setJobId(42L);
        payment.setJobNumber("JOB-2026-00042");
        payment.setOpmcId(1L);
        payment.setCustomerId(NP_CUSTOMER_ID);
        payment.setCustomerName("Test Client");
        payment.setTeamLeadId(NP_TEAM_LEAD_ID);
        payment.setTeamLeadName("TL Tharindu");
        payment.setTotalAmount(new BigDecimal(NP_AMOUNT));
        payment.setStatus(Payment.PaymentStatus.DRAFT);

        when(paymentRepo.findById(NP_PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(paymentRepo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepo.countBilledByYearMonth(anyInt(), anyInt())).thenReturn(0L);
        when(approvalRepo.save(any(PaymentApproval.class))).thenAnswer(inv -> inv.getArgument(0));
        when(notificationRepo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewPaymentRequest approve = new ReviewPaymentRequest();
        approve.setDecision("APPROVED");

        // ── Act: step 1 — the admin approves at the submitted amount ─────────────────────────
        Payment approved = paymentService.reviewPayment(
            NP_PAYMENT_ID, approve, NP_ADMIN_ID, "Admin Amelia");

        // Precondition for the row: the approval itself must have happened.
        assertEquals(Payment.PaymentStatus.FINAL, approved.getStatus(),
            "Precondition for this row: an approved payment reaches FINAL "
                + "(this codebase's 'APPROVED')");
        assertEquals(0, approved.getApprovedAmount().compareTo(new BigDecimal(NP_AMOUNT)),
            "Precondition: the approved amount is the submitted total");

        assertAll("payment approval notifies the submitting Team Lead",

            // ── The mechanism that does exist: the live WebSocket push (PAY-006's fix) ──────
            () -> {
                ArgumentCaptor<String> title   = ArgumentCaptor.forClass(String.class);
                ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
                verify(webSocketEventPublisher).sendToUser(
                    eq(String.valueOf(NP_TEAM_LEAD_ID)), title.capture(), message.capture(),
                    eq("PAYMENT_UPDATE"));

                assertTrue(title.getValue().contains("Approved"),
                    "Step 3: the payload title must say the payment was approved, was: "
                        + title.getValue());
                assertTrue(message.getValue().contains(NP_AMOUNT),
                    "Step 3: the payload must carry the approved amount " + NP_AMOUNT
                        + ", was: " + message.getValue());
            },

            // ── Steps 2-4: an FCM/in-app notification addressed to the Team Lead ────────────
            () -> {
                List<Notification> rows = savedNotifications();

                Notification approvedNote = rows.stream()
                    .filter(n -> n.getType() == Notification.NotificationType.PAYMENT_APPROVED)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                        "Approving a payment must push an FCM notification to the submitting "
                            + "Team Lead and persist the matching notifications row. "
                            + "PaymentService.reviewPayment's notifyTeamLead(...) helper (added "
                            + "2026-08-07 for PAY-006/008) is "
                            + "webSocketEventPublisher.sendToUser(teamLeadId, title, message, "
                            + "'PAYMENT_UPDATE') and nothing more — PaymentService has no "
                            + "NotificationService dependency, so no FCM push is sent and no "
                            + "notifications row is written. A Team Lead who is off-socket when "
                            + "the admin approves learns nothing until they next open the app and "
                            + "re-read the payment list. NotificationService.notifyPaymentApproved"
                            + "(teamLeadId, fcmToken, paymentNumber, paymentId) already exists and "
                            + "does both halves — it has no caller anywhere. Rows written: "
                            + rows));

                // Step 4: recipient must be the Team Lead, not the customer.
                assertEquals(NP_TEAM_LEAD_ID, approvedNote.getRecipientId(),
                    "The notification must be addressed to the submitting Team Lead, not the "
                        + "customer (who does get a separate 'Bill Ready' socket frame)");
                assertEquals(NP_PAYMENT_ID, approvedNote.getReferenceId());
                assertEquals("PAYMENT", approvedNote.getReferenceType());

                // Step 3: the payload must state the amount that was approved.
                assertTrue(approvedNote.getBody() != null
                        && approvedNote.getBody().contains(NP_AMOUNT),
                    "The notification must carry the approved amount " + NP_AMOUNT
                        + ", was: " + approvedNote.getBody());
            }
        );
    }
}
