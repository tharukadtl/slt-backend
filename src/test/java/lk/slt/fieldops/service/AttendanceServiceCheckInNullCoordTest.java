package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.AttendanceDTO;
import lk.slt.fieldops.entity.CheckInOut;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CheckInOutRepository;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the GPS-unavailable data-integrity fix — SERVICE /
 * persistence side of POST /api/attendance/check-in.
 *
 * The DTO now allows null lat/lng (see {@link lk.slt.fieldops.dto.CheckInRequestValidationTest}).
 * This test proves the accepted null flows all the way through
 * {@link AttendanceService#checkIn} to the persisted {@link CheckInOut} and back
 * into the response WITHOUT ever being silently coerced to 0.0 — i.e. a
 * GPS-unavailable check-in is stored as a genuine "unknown location", not a
 * phantom (0,0).
 *
 * Pure Mockito unit test (no Spring / no MySQL), matching the module convention.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceCheckInNullCoordTest {

    @Mock private CheckInOutRepository    checkInOutRepository;
    @Mock private UserRepository          userRepository;
    @Mock private JobRepository           jobRepository;
    @Mock private WebSocketEventPublisher webSocketEventPublisher;

    @InjectMocks private AttendanceService attendanceService;

    private static final Long USER_ID = 42L;

    private User technician;

    @BeforeEach
    void setUp() {
        technician = new User();
        technician.setId(USER_ID);
        technician.setFullName("Null Island Tech");
        technician.setRole(User.Role.TECHNICIAN);
        technician.setPhone("0771234567");
    }

    @Test
    void checkIn_withNullCoordinates_persistsNullNotZero() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(technician));
        when(checkInOutRepository.existsTodayCheckIn(eq(USER_ID), any(LocalDateTime.class)))
                .thenReturn(false);
        // save() returns the entity it was given (with user + status already set).
        when(checkInOutRepository.save(any(CheckInOut.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AttendanceDTO.CheckInRequest request =
                AttendanceDTO.CheckInRequest.builder()
                        .latitude(null)
                        .longitude(null)
                        .address("Location unavailable")
                        .build();

        AttendanceDTO.AttendanceResponse response =
                attendanceService.checkIn(USER_ID, request);

        // 1) The entity actually persisted carries NULL coordinates, not 0.0.
        ArgumentCaptor<CheckInOut> captor =
                ArgumentCaptor.forClass(CheckInOut.class);
        verify(checkInOutRepository).save(captor.capture());
        CheckInOut persisted = captor.getValue();
        assertNull(persisted.getCheckInLatitude(),
                "persisted latitude must be null, not coerced to 0.0");
        assertNull(persisted.getCheckInLongitude(),
                "persisted longitude must be null, not coerced to 0.0");
        assertEquals("Location unavailable", persisted.getCheckInAddress());
        assertEquals("CHECKED_IN", persisted.getStatus());

        // 2) The response returned to the client also carries NULL, not 0.0.
        assertNotNull(response);
        assertNull(response.getCheckInLatitude(),
                "response latitude must be null, not 0.0");
        assertNull(response.getCheckInLongitude(),
                "response longitude must be null, not 0.0");
    }

    @Test
    void checkIn_withRealCoordinates_persistsThemVerbatim() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(technician));
        when(checkInOutRepository.existsTodayCheckIn(eq(USER_ID), any(LocalDateTime.class)))
                .thenReturn(false);
        when(checkInOutRepository.save(any(CheckInOut.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AttendanceDTO.CheckInRequest request =
                AttendanceDTO.CheckInRequest.builder()
                        .latitude(6.9271)
                        .longitude(79.8612)
                        .address("Colombo")
                        .build();

        AttendanceDTO.AttendanceResponse response =
                attendanceService.checkIn(USER_ID, request);

        assertEquals(6.9271, response.getCheckInLatitude());
        assertEquals(79.8612, response.getCheckInLongitude());
    }
}
