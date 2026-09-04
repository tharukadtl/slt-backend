package lk.slt.fieldops.security;

import lk.slt.fieldops.config.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-005 — Error responses must be sanitized: no stack traces, no internal class names, no DB
 * connection strings or credentials leaked to the caller.
 *
 * <p>Harness matches {@code BillDisputeAmendmentIntegrationTest} — real filter chain, real MySQL,
 * {@code @Transactional} rollback. MockMvc replaces the sheet's REST Assured (not a dependency of
 * this module); the sanitization under test lives in
 * {@code GlobalExceptionHandler}, which MockMvc exercises identically.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ErrorHandlingSecurityTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;

    /** Markers that would betray server internals if they reached the client. */
    private static final List<String> LEAK_MARKERS = List.of(
        "java.lang",          // internal class names / exception FQCNs
        "java.sql",
        "com.fasterxml",      // Jackson internals
        "org.springframework",
        "org.hibernate",
        "lk.slt.fieldops",    // our own package names
        "\n\tat ",            // stack-trace frames
        "jdbc:mysql",         // DB connection string
        "Caused by:"
    );

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, 1L);
    }

    private static void assertSanitized(String label, int status, String body) {
        assertTrue(status >= 400,
            label + ": expected an error status to be produced, got " + status);
        for (String marker : LEAK_MARKERS) {
            assertFalse(body.contains(marker),
                label + ": error response leaked internal detail \"" + marker.trim()
                    + "\". Full body: " + body);
        }
    }

    @Test
    void noInternalDetailsLeaked() throws Exception {
        // ── (1) Malformed JSON body on a permitAll endpoint → parser blows up server-side ────
        MvcResult malformedJson = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"admin\", \"password\": "))   // truncated / invalid JSON
            .andReturn();
        assertSanitized("malformed JSON POST /api/auth/login",
            malformedJson.getResponse().getStatus(),
            malformedJson.getResponse().getContentAsString());

        // ── (2) Type-mismatched path variable → binding exception inside the dispatcher ──────
        MvcResult badPathVar = mvc.perform(get("/api/faults/{id}", "not-a-number")
                .header("Authorization", bearer(9201L, "ADMIN")))
            .andReturn();
        assertSanitized("type-mismatch GET /api/faults/not-a-number",
            badPathVar.getResponse().getStatus(),
            badPathVar.getResponse().getContentAsString());

        // ── (3) Unsupported content type on a JSON endpoint ─────────────────────────────────
        MvcResult wrongContentType = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.TEXT_PLAIN)
                .content("username=admin"))
            .andReturn();
        assertSanitized("unsupported content type POST /api/auth/login",
            wrongContentType.getResponse().getStatus(),
            wrongContentType.getResponse().getContentAsString());
    }
}
