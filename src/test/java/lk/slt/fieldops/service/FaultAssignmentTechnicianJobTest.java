package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultNoteRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for QA Critical Issue #2 (QA_Compliance_Consolidated_Report.md
 * §3, FR-17 / SRS 5.5.1): Admin could previously only assign a fault to a Team Lead,
 * never directly to a Technician.
 *
 * The frontend fix (FaultsPage.js) broadens the assignee picker, but the request
 * still lands on the same {@code POST /api/faults/{id}/assign} endpoint and the same
 * {@link FaultAssignmentService#assignFault} method regardless of which role is
 * picked — {@link FaultAssignmentDTO.AssignRequest#getTechnicianId()} accepts either.
 *
 * Before this fix, assigning directly to a Technician would silently fail to reach
 * them: the fault would be tagged as "assigned" but no {@code Job} row was ever
 * created for that Technician, since the only path that creates Jobs
 * ({@code JobService.createJob}) requires the caller to BE a Team Lead with an
 * active {@code DaySession}. {@link JobCreationService#createJobForFaultAssignment}
 * existed for exactly this scenario but was never wired up.
 *
 * These tests assert:
 *  - Assigning to a user with role TECHNICIAN calls
 *    {@code jobCreationService.createJobForFaultAssignment} so they actually get a
 *    Job and see it in their app.
 *  - Assigning to a user with role TEAM_LEAD does NOT call it — Team Leads still
 *    dispatch Jobs themselves via their own {@code createJob} flow, unchanged.
 *  - The equivalent reassign path ({@code reassignFault}) has the same behavior,
 *    since the Reassign tab shares the same broadened picker.
 */
@ExtendWith(MockitoExtension.class)
class FaultAssignmentTechnicianJobTest {

    @Mock private FaultRepository faultRepository;
    @Mock private UserRepository userRepository;
    @Mock private FaultHistoryRepository faultHistoryRepository;
    @Mock private FaultNoteRepository faultNoteRepository;
    @Mock private WebSocketEventPublisher webSocketEventPublisher;
    @Mock private JobCreationService jobCreationService;

    @InjectMocks
    private FaultAssignmentService faultAssignmentService;

    private User userWithRole(long id, String name, User.Role role) {
        User u = new User();
        u.setId(id);
        u.setFullName(name);
        u.setRole(role);
        return u;
    }

    private Fault openFault(long id) {
        Fault fault = new Fault();
        fault.setId(id);
        fault.setFaultNumber("FLT-" + id);
        fault.setStatus(Fault.FaultStatus.REPORTED);
        return fault;
    }

    @Test
    void assignFault_toTechnician_createsJob() {
        Fault fault = openFault(100L);
        User technician = userWithRole(5L, "Tech Tim", User.Role.TECHNICIAN);
        User admin = userWithRole(1L, "Admin Ann", User.Role.ADMIN);

        when(faultRepository.findById(100L)).thenReturn(Optional.of(fault));
        when(userRepository.findById(5L)).thenReturn(Optional.of(technician));
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        FaultAssignmentDTO.AssignRequest req = FaultAssignmentDTO.AssignRequest.builder()
                .technicianId(5L)
                .priority("HIGH")
                .notifyTechnician(false)
                .notifyCustomer(false)
                .build();

        faultAssignmentService.assignFault(100L, req, 1L);

        verify(jobCreationService, times(1))
                .createJobForFaultAssignment(eq(fault), eq(technician), eq("HIGH"));
    }

    @Test
    void assignFault_toTeamLead_doesNotCreateJob() {
        Fault fault = openFault(101L);
        User teamLead = userWithRole(6L, "TL Tara", User.Role.TEAM_LEAD);
        User admin = userWithRole(1L, "Admin Ann", User.Role.ADMIN);

        when(faultRepository.findById(101L)).thenReturn(Optional.of(fault));
        when(userRepository.findById(6L)).thenReturn(Optional.of(teamLead));
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        FaultAssignmentDTO.AssignRequest req = FaultAssignmentDTO.AssignRequest.builder()
                .technicianId(6L)
                .notifyTechnician(false)
                .notifyCustomer(false)
                .build();

        faultAssignmentService.assignFault(101L, req, 1L);

        verify(jobCreationService, never()).createJobForFaultAssignment(any(), any(), any());
    }

    @Test
    void reassignFault_toTechnician_createsJob() {
        Fault fault = openFault(102L);
        fault.setAssignedTeamLeadId(6L);
        fault.setAssignedTeamLeadName("TL Tara");
        User technician = userWithRole(5L, "Tech Tim", User.Role.TECHNICIAN);
        User admin = userWithRole(1L, "Admin Ann", User.Role.ADMIN);

        when(faultRepository.findById(102L)).thenReturn(Optional.of(fault));
        when(userRepository.findById(5L)).thenReturn(Optional.of(technician));
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        FaultAssignmentDTO.ReassignRequest req = FaultAssignmentDTO.ReassignRequest.builder()
                .newTechnicianId(5L)
                .reason("Team Lead unavailable")
                .notifyTechnician(false)
                .notifyPreviousTechnician(false)
                .build();

        faultAssignmentService.reassignFault(102L, req, 1L);

        verify(jobCreationService, times(1))
                .createJobForFaultAssignment(eq(fault), eq(technician), eq(null));
    }

    @Test
    void reassignFault_toTeamLead_doesNotCreateJob() {
        Fault fault = openFault(103L);
        fault.setAssignedTeamLeadId(6L);
        fault.setAssignedTeamLeadName("TL Tara");
        User newTeamLead = userWithRole(7L, "TL Tom", User.Role.TEAM_LEAD);
        User admin = userWithRole(1L, "Admin Ann", User.Role.ADMIN);

        when(faultRepository.findById(103L)).thenReturn(Optional.of(fault));
        when(userRepository.findById(7L)).thenReturn(Optional.of(newTeamLead));
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        FaultAssignmentDTO.ReassignRequest req = FaultAssignmentDTO.ReassignRequest.builder()
                .newTechnicianId(7L)
                .reason("Original TL on leave")
                .notifyTechnician(false)
                .notifyPreviousTechnician(false)
                .build();

        faultAssignmentService.reassignFault(103L, req, 1L);

        verify(jobCreationService, never()).createJobForFaultAssignment(any(), any(), any());
    }
}
