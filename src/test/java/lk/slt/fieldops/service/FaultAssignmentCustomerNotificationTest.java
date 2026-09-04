package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.Notification;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultNoteRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.NotificationRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
import lk.slt.fieldops.shared.OpmcAccessGuard;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA_Compliance_Consolidated_Report.md — Stage G FCM Major: "a customer being told a technician
 * was assigned to their fault" ({@code FaultAssignmentService.assignFault}, the
 * {@code req.isNotifyCustomer()} branch) was WebSocket-only ({@code sendToUser(fault.getCustomerId(),
 * ..., "TECHNICIAN_ASSIGNED")}) — a customer who is not holding an open socket at that instant is
 * never told at all, and nothing survives for their in-app notification list.
 *
 * <p><b>Distinct from NOTIF-001</b> ({@code NotificationServiceTest::faultAssigned_triggersFcmToTeamLead}),
 * which is the Team-Lead side of this exact same method and stays a separately-open, unfixed gap —
 * this test does not touch or assert that side, so a regression there would not be masked here.</p>
 *
 * <p>Same manual-construction, real-{@code NotificationService}-over-a-mocked-repository pattern
 * {@code NotificationServiceTest} uses for the identical "assert the WebSocket mechanism, then the
 * persisted row" shape.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FaultAssignmentCustomerNotificationTest {

    @Mock private FaultRepository        faultRepo;
    @Mock private UserRepository         userRepo;
    @Mock private FaultHistoryRepository historyRepo;
    @Mock private FaultNoteRepository    noteRepo;
    @Mock private WebSocketEventPublisher webSocketEventPublisher;
    @Mock private WorkGroupRepository    workGroupRepository;
    @Mock private NotificationRepository notificationRepo;
    @Mock private JobRepository          jobRepo;

    private static final Long FAULT_ID    = 1L;
    private static final Long CUSTOMER_ID = 6L;
    private static final Long ADMIN_ID    = 99L;
    private static final Long WORK_GROUP_ID = 200L;

    @Test
    void faultAssigned_notifiesCustomerDurably() {
        FaultAssignmentService assignmentService = new FaultAssignmentService(
            faultRepo, userRepo, historyRepo, noteRepo, webSocketEventPublisher, workGroupRepository,
            new OpmcAccessGuard(userRepo), new NotificationService(notificationRepo), jobRepo);

        Fault fault = new Fault();
        fault.setId(FAULT_ID);
        fault.setFaultNumber("FLT-2026-01042");
        fault.setOpmcId(1L);
        fault.setCustomerId(CUSTOMER_ID);
        fault.setStatus(Fault.FaultStatus.REPORTED);
        when(faultRepo.findById(FAULT_ID)).thenReturn(Optional.of(fault));
        when(faultRepo.save(any(Fault.class))).thenAnswer(inv -> inv.getArgument(0));

        User customer = new User();
        customer.setId(CUSTOMER_ID);
        customer.setFullName("Client Chathura");
        customer.setFcmToken("fcm-token-client-6");
        when(userRepo.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        User admin = new User();
        admin.setId(ADMIN_ID);
        admin.setFullName("Admin Amelia");
        admin.setRole(User.Role.ADMIN);
        admin.setOpmcId(1L);
        when(userRepo.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        Opmc opmc = new Opmc();
        opmc.setId(1L);

        WorkGroup workGroup = new WorkGroup();
        workGroup.setId(WORK_GROUP_ID);
        workGroup.setName("Colombo North Team");
        workGroup.setOpmc(opmc);
        workGroup.setIsActive(true);
        when(workGroupRepository.findById(WORK_GROUP_ID)).thenReturn(Optional.of(workGroup));

        when(notificationRepo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        assignmentService.assignFault(FAULT_ID,
            FaultAssignmentDTO.AssignRequest.builder()
                .workGroupId(WORK_GROUP_ID)
                .priority("HIGH")
                .build(),
            ADMIN_ID);

        // Precondition: the assignment itself must have happened.
        assertEquals(WORK_GROUP_ID, fault.getWorkGroupId(),
            "Precondition for this test: the fault must actually be assigned to the Work Group");
        verify(historyRepo).save(any(FaultHistory.class));

        assertAll("fault assignment notifies the reporting customer durably, not just over the socket",

            // ── The mechanism that does exist: the live WebSocket push ──────────────────────
            () -> verify(webSocketEventPublisher).sendToUser(
                eq(String.valueOf(CUSTOMER_ID)), any(), any(), eq("TECHNICIAN_ASSIGNED")),

            // ── The mechanism that did not: an FCM push + persisted notifications row ───────
            () -> {
                ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
                verify(notificationRepo).save(saved.capture());
                Notification assigned = saved.getValue();

                assertEquals(Notification.NotificationType.TECHNICIAN_ASSIGNED, assigned.getType(),
                    "The persisted row must carry the TECHNICIAN_ASSIGNED type the Web Admin "
                        + "Portal's own NotificationsPage.js already maps an icon/label for");
                assertEquals(CUSTOMER_ID, assigned.getRecipientId(),
                    "The notification must be addressed to the reporting customer, not the "
                        + "Team Lead (whose own, separately-open gap this test does not touch)");
                assertEquals(FAULT_ID, assigned.getReferenceId());
                assertEquals("FAULT", assigned.getReferenceType());
                assertTrue(assigned.getBody() != null
                        && assigned.getBody().contains(fault.getFaultNumber()),
                    "The body must name the fault, was: " + assigned.getBody());
            }
        );
    }
}
