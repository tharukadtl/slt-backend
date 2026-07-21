package lk.slt.fieldops.shared.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the fix's exception mapping: a {@link DuplicateSessionException}
 * (thrown by JobService.performBod on a duplicate same-day BOD) is rendered as
 * HTTP 409 Conflict with a JSON body — not a 500, and not a 400.
 *
 * Regression coverage for QA_Compliance_Consolidated_Report §2.3.
 */
class GlobalExceptionHandlerDuplicateSessionTest {

    @Test
    void duplicateSessionException_mapsTo409Conflict() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        DuplicateSessionException ex =
            new DuplicateSessionException("You have already checked in today.");

        ResponseEntity<Map<String, Object>> response = handler.handleDuplicateSession(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(409, response.getStatusCode().value());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(409, body.get("status"));
        assertEquals("Conflict", body.get("error"));
        assertEquals("You have already checked in today.", body.get("message"));
        assertNotNull(body.get("timestamp"));
    }
}
