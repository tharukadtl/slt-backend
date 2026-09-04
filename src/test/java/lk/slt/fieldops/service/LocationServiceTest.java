package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.LocationUpdateRequest;
import lk.slt.fieldops.entity.TechnicianLocation;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.DaySessionMemberRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.TechnicianLocationRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * FAULT-018 (02_FAULT_TRACKING, FR-6) — a technician GPS update must be inside Sri Lanka's
 * bounds, and coordinates must be present.
 *
 * <p>Mockito unit test against {@link LocationService}, per the row's Pre-Conditions
 * ("LocationService validator") and this module's service-test convention.</p>
 *
 * <p><b>What this row is expected to expose.</b> {@code LocationService.updateLocation} performs no
 * geographic validation at all: it loads the user, deactivates prior rows, and persists whatever
 * latitude/longitude it was handed. The exception types the row names
 * ({@code InvalidLocationException}, {@code MissingGPSException}) do not exist anywhere in the
 * codebase — {@code shared/exception} contains only {@code DuplicateSessionException},
 * {@code InvalidRefreshTokenException} and {@code ResourceNotFoundException} — so the sub-checks
 * below assert that <i>some</i> exception is raised rather than naming a class that would not
 * compile. Nor does the DTO layer close the gap: {@code LocationUpdateRequest} carries only global
 * {@code @DecimalMin(-90)/@DecimalMax(90)} bounds, which London (51.5, -0.12) satisfies. The Sri
 * Lanka bounds that <i>are</i> implemented live in the separate Flask AI module
 * ({@code slt-ai-module/utils/validators.py}, {@code SL_LAT 5.9-9.9}, {@code SL_LNG 79.5-81.9} —
 * covered by FAULT-004), not in this backend.</p>
 *
 * <p>All three sub-checks run under {@code assertAll} so each produces its own verdict.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocationServiceTest {

    @Mock private TechnicianLocationRepository locationRepository;
    @Mock private UserRepository               userRepository;
    @Mock private JobRepository                jobRepository;
    @Mock private DaySessionMemberRepository   daySessionMemberRepository;
    @Mock private WebSocketEventPublisher      webSocketEventPublisher;
    @Mock private RestTemplate                 restTemplate;

    @InjectMocks private LocationService locationService;

    private static final Long TECH_ID = 5L;

    @BeforeEach
    void setUp() {
        User technician = new User();
        technician.setId(TECH_ID);
        technician.setFullName("Tech Nimal");
        technician.setRole(User.Role.TECHNICIAN);
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(technician));
        when(locationRepository.save(any(TechnicianLocation.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private LocationUpdateRequest at(Double latitude, Double longitude) {
        return LocationUpdateRequest.builder()
            .latitude(latitude)
            .longitude(longitude)
            .address("Test address")
            .status("TRAVELLING")
            .build();
    }

    @Test
    void updateLocation_slBoundsValidated() {
        assertAll("technician location bounds",

            // ── Step 1: Colombo (6.9271, 79.8612) is inside Sri Lanka and must be accepted ──
            () -> {
                assertDoesNotThrow(
                    () -> locationService.updateLocation(TECH_ID, at(6.9271, 79.8612)),
                    "A coordinate inside Sri Lanka must be accepted");
                verify(locationRepository, atLeastOnce()).save(any(TechnicianLocation.class));
            },

            // ── Step 2: London (51.5, -0.12) is far outside Sri Lanka and must be rejected ──
            () -> assertThrows(RuntimeException.class,
                () -> locationService.updateLocation(TECH_ID, at(51.5, -0.12)),
                "A coordinate outside Sri Lanka (London: 51.5, -0.12) must be rejected. "
                    + "LocationService.updateLocation applies no geographic check, and "
                    + "LocationUpdateRequest's @DecimalMin/@DecimalMax are global -90..90 / "
                    + "-180..180 bounds, which London satisfies — so the ping is stored as a "
                    + "valid technician position"),

            // ── Step 3: absent coordinates must be rejected, before anything is persisted ──
            () -> {
                clearInvocations(locationRepository);
                RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> locationService.updateLocation(TECH_ID, at(null, null)),
                    "A location update with no coordinates must be rejected by the service");

                assertFalse(thrown instanceof NullPointerException,
                    "A missing-GPS update must be rejected by an explicit validation error, not "
                        + "crash. LocationService.updateLocation performs no null check: it saves "
                        + "the row first and then NPEs unboxing the null latitude into "
                        + "WebSocketEventPublisher.publishLocationUpdate(String, double, double, "
                        + "String). Thrown: " + thrown);

                verify(locationRepository, never()).save(any(TechnicianLocation.class));
            }
        );
    }
}
