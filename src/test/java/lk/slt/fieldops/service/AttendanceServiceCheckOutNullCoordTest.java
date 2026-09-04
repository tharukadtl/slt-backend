package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.AttendanceDTO;
import lk.slt.fieldops.entity.CheckInOut;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CheckInOutRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the GPS-unavailable data-integrity fix — SERVICE /
 * persistence side of POST /api/attendance/check-out.
 *
 * Direct mirror of {@link AttendanceServiceCheckInNullCoordTest}. The DTO now
 * allows null lat/lng (see {@link lk.slt.fieldops.dto.CheckOutRequestValidationTest});
 * this test proves the accepted null flows all the way through
 * {@link AttendanceService#checkOut} to the persisted {@link CheckInOut} and back
 * into the response WITHOUT ever being silently coerced to 0.0, and that a
 * missing GPS fix does not block end-of-day check-out at all — the coordinates
 * are copied onto the record and never read by any check-out logic.
 *
 * Pure Mockito unit test (no Spring / no MySQL), matching the module convention.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceCheckOutNullCoordTest {

    @Mock private CheckInOutRepository    checkInOutRepository;
    @Mock private UserRepository          userRepository;
    @Mock private JobRepository           jobRepository;
    @Mock private FaultRepository         faultRepository;
    @Mock private WebSocketEventPublisher webSocketEventPublisher;

    @InjectMocks private AttendanceService attendanceService;

    private static final Long USER_ID = 42L;

    private User technician;
    private CheckInOut activeSession;

    @BeforeEach
    void setUp() {
        technician = new User();
        technician.setId(USER_ID);
        technician.setFullName("Null Island Tech");
        technician.setRole(User.Role.TECHNICIAN);
        technician.setPhone("0771234567");

        activeSession = new CheckInOut();
        activeSession.setId(7L);
        activeSession.setUser(technician);
        activeSession.setCheckInTime(LocalDateTime.now().minusHours(8));
        activeSession.setStatus("CHECKED_IN");
    }

    /** Shared stubbing for a technician with an open session and no open jobs. */
    private void givenOpenSessionWithNoOpenJobs() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(technician));
        when(checkInOutRepository.findActiveCheckInByUserId(USER_ID))
                .thenReturn(Optional.of(activeSession));
        when(jobRepository.findByTechnicianIdAndScheduledDate(eq(USER_ID), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());
        // save() returns the entity it was given (already mutated by checkOut).
        when(checkInOutRepository.save(any(CheckInOut.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void checkOut_withNullCoordinates_succeedsAndPersistsNullNotZero() {
        givenOpenSessionWithNoOpenJobs();

        AttendanceDTO.CheckOutRequest request =
                AttendanceDTO.CheckOutRequest.builder()
                        .latitude(null)
                        .longitude(null)
                        .address("Location unavailable")
                        .build();

        // 0) A missing GPS fix must not block end-of-day check-out at all.
        AttendanceDTO.AttendanceResponse response =
                assertDoesNotThrow(() -> attendanceService.checkOut(USER_ID, request));

        // 1) The entity actually persisted carries NULL coordinates, not 0.0,
        //    and the shift really was closed out.
        ArgumentCaptor<CheckInOut> captor =
                ArgumentCaptor.forClass(CheckInOut.class);
        verify(checkInOutRepository).save(captor.capture());
        CheckInOut persisted = captor.getValue();
        assertNull(persisted.getCheckOutLatitude(),
                "persisted check-out latitude must be null, not coerced to 0.0");
        assertNull(persisted.getCheckOutLongitude(),
                "persisted check-out longitude must be null, not coerced to 0.0");
        assertEquals("Location unavailable", persisted.getCheckOutAddress());
        assertEquals("CHECKED_OUT", persisted.getStatus());
        assertNotNull(persisted.getCheckOutTime(),
                "check-out time must be stamped even without a GPS fix");

        // 2) The response returned to the client also carries NULL, not 0.0.
        assertNotNull(response);
        assertNull(response.getCheckOutLatitude(),
                "response check-out latitude must be null, not 0.0");
        assertNull(response.getCheckOutLongitude(),
                "response check-out longitude must be null, not 0.0");
        assertEquals("CHECKED_OUT", response.getStatus());
    }

    @Test
    void checkOut_withRealCoordinates_persistsThemVerbatim() {
        givenOpenSessionWithNoOpenJobs();

        AttendanceDTO.CheckOutRequest request =
                AttendanceDTO.CheckOutRequest.builder()
                        .latitude(6.9271)
                        .longitude(79.8612)
                        .address("Colombo")
                        .build();

        AttendanceDTO.AttendanceResponse response =
                attendanceService.checkOut(USER_ID, request);

        assertEquals(6.9271, response.getCheckOutLatitude());
        assertEquals(79.8612, response.getCheckOutLongitude());
    }
}
