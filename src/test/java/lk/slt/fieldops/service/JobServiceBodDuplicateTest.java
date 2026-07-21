package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.BodRequest;
import lk.slt.fieldops.entity.DaySession;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.*;
import lk.slt.fieldops.shared.exception.DuplicateSessionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for QA_Compliance_Consolidated_Report §2.3:
 * "Team Lead once-per-calendar-day BOD limit unenforced".
 *
 * Reproduces the original bug scenario — the same team lead performing BOD
 * twice on the same calendar date — and asserts the second attempt is now
 * rejected with a {@link DuplicateSessionException} (mapped to HTTP 409 by
 * GlobalExceptionHandler, verified separately in
 * {@code GlobalExceptionHandlerDuplicateSessionTest}).
 *
 * Covers BOTH guard layers introduced by the fix:
 *   (a) the pre-check {@code existsByTeamLeadIdAndSessionDate} throwing directly, and
 *   (b) the TOCTOU race backstop — the DB unique constraint surfacing as a
 *       {@link DataIntegrityViolationException} on save, caught and re-thrown
 *       as a {@link DuplicateSessionException} rather than leaking a raw 500.
 */
@ExtendWith(MockitoExtension.class)
class JobServiceBodDuplicateTest {

    @Mock private DaySessionRepository       sessionRepo;
    @Mock private DaySessionMemberRepository memberRepo;
    @Mock private JobRepository              jobRepo;
    @Mock private CheckInOutRepository       checkInOutRepo;
    @Mock private MaterialUsageRepository    materialUsageRepo;
    @Mock private UserRepository             userRepo;
    @Mock private MaterialRepository         materialRepo;
    @Mock private FaultRepository            faultRepo;
    @Mock private NotificationService        notificationService;

    @InjectMocks private JobService jobService;

    private static final Long TL_ID = 42L;

    private BodRequest validRequest() {
        BodRequest req = new BodRequest();
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setVehicleId(3L);
        req.setOdometerStart(45200);
        req.setLocationAddress("Colombo");
        req.setTechnicianIds(List.of(7L, 8L));
        return req;
    }

    private DaySession savedSession() {
        DaySession s = new DaySession();
        s.setId(1L);
        s.setTeamLeadId(TL_ID);
        return s;
    }

    /**
     * The exact bug from the report: BOD once (success), then a SECOND BOD for
     * the same team lead on the same calendar date. The second must be blocked.
     */
    @Test
    void secondBodSameTeamLeadSameDay_isRejected() {
        // First call: no session yet → pre-check passes; second call: session now exists.
        when(sessionRepo.existsByTeamLeadIdAndSessionDate(eq(TL_ID), any(LocalDate.class)))
            .thenReturn(false, true);
        when(sessionRepo.save(any(DaySession.class))).thenReturn(savedSession());
        when(userRepo.findById(TL_ID)).thenReturn(Optional.of(mock(User.class)));

        // First BOD succeeds
        DaySession first = jobService.performBod(validRequest(), TL_ID, "Team Lead");
        assertNotNull(first);
        assertEquals(1L, first.getId());

        // Second BOD same team lead / same day → blocked
        DuplicateSessionException ex = assertThrows(
            DuplicateSessionException.class,
            () -> jobService.performBod(validRequest(), TL_ID, "Team Lead"));
        assertEquals("You have already checked in today.", ex.getMessage());

        // session.save must NOT have been called a second time
        verify(sessionRepo, times(1)).save(any(DaySession.class));
    }

    /** Pre-check path in isolation: existing session for today short-circuits before any write. */
    @Test
    void bodWhenSessionAlreadyExistsToday_throwsDuplicateAndWritesNothing() {
        when(sessionRepo.existsByTeamLeadIdAndSessionDate(eq(TL_ID), any(LocalDate.class)))
            .thenReturn(true);

        DuplicateSessionException ex = assertThrows(
            DuplicateSessionException.class,
            () -> jobService.performBod(validRequest(), TL_ID, "Team Lead"));
        assertEquals("You have already checked in today.", ex.getMessage());

        verify(sessionRepo, never()).save(any(DaySession.class));
        verify(memberRepo, never()).save(any());
        verify(checkInOutRepo, never()).save(any());
    }

    /**
     * TOCTOU race backstop: pre-check passes (false), but a concurrent request
     * created today's row, so the DB unique constraint rejects the save with a
     * {@link DataIntegrityViolationException}. The fix must translate that into
     * a {@link DuplicateSessionException} (→ 409), NOT let it leak as a 500.
     */
    @Test
    void bodRaceConditionOnSave_translatesDataIntegrityViolationTo409Exception() {
        when(sessionRepo.existsByTeamLeadIdAndSessionDate(eq(TL_ID), any(LocalDate.class)))
            .thenReturn(false);
        when(sessionRepo.save(any(DaySession.class)))
            .thenThrow(new DataIntegrityViolationException(
                "Duplicate entry for key 'uk_day_sessions_team_lead_date'"));

        DuplicateSessionException ex = assertThrows(
            DuplicateSessionException.class,
            () -> jobService.performBod(validRequest(), TL_ID, "Team Lead"));
        assertEquals("You have already checked in today.", ex.getMessage());

        // Nothing downstream of the failed save should have run
        verify(memberRepo, never()).save(any());
        verify(checkInOutRepo, never()).save(any());
    }
}
