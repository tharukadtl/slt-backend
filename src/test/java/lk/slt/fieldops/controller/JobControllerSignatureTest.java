package lk.slt.fieldops.controller;

import lk.slt.fieldops.dto.SubmitSignatureRequest;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.service.JobService;
import lk.slt.fieldops.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for QA_Compliance_Consolidated_Report §2.5 (FR-9):
 * "Customer signature capture is a non-functional stub — a hardcoded
 * 'signature_placeholder' string flowed all the way into approved payments".
 *
 * The fix added a guard in {@link JobController#submitSignature} that rejects
 * the literal placeholder (and still rejects blank/null, pre-existing) BEFORE
 * calling {@link JobService#submitSignature}, so the placeholder can never be
 * persisted as a real customer signature.
 *
 * Reproduces the original bug scenario — POSTing the literal
 * {@code "signature_placeholder"} directly at the endpoint's controller method —
 * and asserts it is rejected without the service ever being invoked.
 *
 * Pure Mockito unit test (no MySQL / no Spring context), matching the DB-free
 * verification convention used elsewhere in this module.
 */
@ExtendWith(MockitoExtension.class)
class JobControllerSignatureTest {

    @Mock private JobService     jobService;
    @Mock private UserRepository userRepo;

    @InjectMocks private JobController controller;

    private static final Long JOB_ID = 55L;
    private static final Long USER_ID = 7L;

    private SubmitSignatureRequest body(String signature) {
        SubmitSignatureRequest req = new SubmitSignatureRequest();
        req.setSignature(signature);
        return req;
    }

    /**
     * THE bug from the report: the literal placeholder must be rejected and must
     * never reach the persistence layer.
     */
    @Test
    void submitSignature_withLiteralPlaceholder_isRejectedAndNeverPersisted() {
        RuntimeException ex = assertThrows(
            RuntimeException.class,
            () -> controller.submitSignature(JOB_ID, body("signature_placeholder"), USER_ID));

        assertTrue(ex.getMessage().contains("signature_placeholder"),
            "message should call out the placeholder: " + ex.getMessage());
        // Service must NOT have been asked to persist the placeholder.
        verify(jobService, never()).submitSignature(anyLong(), anyString(), anyLong());
    }

    /** Placeholder padded with whitespace is still caught (guard trims). */
    @Test
    void submitSignature_withPaddedPlaceholder_isRejectedAndNeverPersisted() {
        assertThrows(
            RuntimeException.class,
            () -> controller.submitSignature(JOB_ID, body("  signature_placeholder  "), USER_ID));

        verify(jobService, never()).submitSignature(anyLong(), anyString(), anyLong());
    }

    /** Pre-existing behavior must not regress: null signature rejected, no persist. */
    @Test
    void submitSignature_withNull_isRejectedAndNeverPersisted() {
        SubmitSignatureRequest emptyBody = new SubmitSignatureRequest(); // signature never set -> null
        RuntimeException ex = assertThrows(
            RuntimeException.class,
            () -> controller.submitSignature(JOB_ID, emptyBody, USER_ID));

        assertTrue(ex.getMessage().toLowerCase().contains("required"),
            "null signature should report it is required: " + ex.getMessage());
        verify(jobService, never()).submitSignature(anyLong(), anyString(), anyLong());
    }

    /** Pre-existing behavior must not regress: blank/whitespace signature rejected. */
    @Test
    void submitSignature_withBlank_isRejectedAndNeverPersisted() {
        assertThrows(
            RuntimeException.class,
            () -> controller.submitSignature(JOB_ID, body("   "), USER_ID));

        verify(jobService, never()).submitSignature(anyLong(), anyString(), anyLong());
    }

    /**
     * A real, non-placeholder signature is still accepted and persisted verbatim
     * — the guard must not break the happy path.
     */
    @Test
    void submitSignature_withRealSignature_isAcceptedAndPersisted() {
        String realSig = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA";
        Job persisted = new Job();
        persisted.setId(JOB_ID);
        persisted.setCompletionSignature(realSig);
        when(jobService.submitSignature(JOB_ID, realSig, USER_ID)).thenReturn(persisted);

        ResponseEntity<Job> response =
            controller.submitSignature(JOB_ID, body(realSig), USER_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(realSig, response.getBody().getCompletionSignature());
        // Persisted exactly once, with exactly the value provided.
        verify(jobService, times(1)).submitSignature(JOB_ID, realSig, USER_ID);
    }

    /**
     * Confirms the exception the guard throws is mapped to HTTP 400 (not 500) by
     * the existing GlobalExceptionHandler's generic RuntimeException handler —
     * i.e. the caller sees a clean "Bad Request", never a server error.
     */
    @Test
    void placeholderRejection_mapsTo400ViaGlobalExceptionHandler() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        RuntimeException ex = assertThrows(
            RuntimeException.class,
            () -> controller.submitSignature(JOB_ID, body("signature_placeholder"), USER_ID));

        ResponseEntity<Map<String, Object>> response = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> respBody = response.getBody();
        assertNotNull(respBody);
        assertEquals(400, respBody.get("status"));
        assertEquals("Bad Request", respBody.get("error"));
        assertNotNull(respBody.get("message"));
    }
}
