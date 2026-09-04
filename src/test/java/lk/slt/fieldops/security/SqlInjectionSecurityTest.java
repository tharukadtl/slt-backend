package lk.slt.fieldops.security;

import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.repository.FaultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SEC-002 — SQL injection: a classic {@code ' OR 1=1--} payload in a search parameter must be
 * treated as a literal string, never as SQL.
 *
 * <p>Harness matches {@code BillDisputeAmendmentIntegrationTest} (real filter chain + real MySQL,
 * {@code @Transactional} rollback). MockMvc is used instead of the sheet's REST Assured because
 * REST Assured is not a dependency of this module.</p>
 *
 * <p><b>Adaptation note.</b> The sheet names {@code GET /api/faults?search=...}, but
 * {@code FaultController.getAll()} declares NO {@code search} request parameter — Spring silently
 * ignores unknown query params, so that call can only ever prove "the payload changed nothing".
 * This test asserts exactly that for the sheet's literal endpoint, and additionally exercises the
 * closest REAL search-backed endpoint in the module, {@code GET /api/inventory/materials/search},
 * which does bind a {@code search} param into a repository query — that is where a genuine
 * injection would surface.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SqlInjectionSecurityTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private FaultRepository  faultRepo;

    /** SEC-002 Test Data. */
    private static final String INJECTION = "' OR 1=1--";

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, 1L);
    }

    @Test
    void searchParamSafe() throws Exception {
        String admin = bearer(9101L, "ADMIN");

        long rowsBefore = faultRepo.count();

        // ── (1) The sheet's literal endpoint: GET /api/faults?search=' OR 1=1-- ──────────────
        MvcResult faults = mvc.perform(get("/api/faults")
                .param("search", INJECTION)
                .header("Authorization", admin))
            .andReturn();

        int    faultsStatus = faults.getResponse().getStatus();
        String faultsBody   = faults.getResponse().getContentAsString();

        assertEquals(200, faultsStatus,
            "Injection payload must be handled normally (HTTP 200), not blow up the query. Body: "
                + faultsBody);
        // A successful injection would surface as a driver/Hibernate error being echoed back.
        assertFalse(faultsBody.contains("SQLSyntaxErrorException")
                 || faultsBody.contains("SQLException")
                 || faultsBody.contains("You have an error in your SQL syntax"),
            "Response leaked a SQL error — the payload reached the SQL parser. Body: " + faultsBody);

        // ── (2) The real search-backed endpoint, where a bound param actually enters a query ──
        MvcResult materials = mvc.perform(get("/api/inventory/materials/search")
                .param("search", INJECTION)
                .header("Authorization", admin))
            .andReturn();

        int    matStatus = materials.getResponse().getStatus();
        String matBody   = materials.getResponse().getContentAsString();

        assertEquals(200, matStatus,
            "Parameterized search must accept the payload as a plain string (HTTP 200). Body: "
                + matBody);
        assertFalse(matBody.contains("SQLSyntaxErrorException")
                 || matBody.contains("SQLException")
                 || matBody.contains("You have an error in your SQL syntax"),
            "Response leaked a SQL error from the search query. Body: " + matBody);
        // Treated as a LITERAL search string: no material name/SKU contains "' OR 1=1--",
        // so the result set must be empty. If the payload had been interpreted as SQL, the
        // OR 1=1 would have matched every row instead.
        assertEquals("[]", matBody.trim(),
            "Payload must be matched literally (0 results), not executed as SQL (all rows). Body: "
                + matBody);

        // ── (3) Nothing was written/dropped: faults table row count unchanged ────────────────
        assertEquals(rowsBefore, faultRepo.count(),
            "faults row count changed after the injection attempt — data was modified");
    }
}
