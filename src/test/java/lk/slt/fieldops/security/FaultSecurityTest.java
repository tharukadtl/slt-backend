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
 * FAULT-017 (02_FAULT_TRACKING, FR-4) — SQL injection through the fault search parameter must be
 * impossible: no crash, no executed SQL, and the {@code faults} table intact afterwards.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured, which is not a dependency of this
 * module; MockMvc through the real filter chain is the established convention here and drives the
 * identical controller/repository/JDBC path a live REST Assured run would.</p>
 *
 * <p><b>Related coverage.</b> Sheet {@code 13_SECURITY_HARDENING} row SEC-002 covers the same class
 * of attack in {@link SqlInjectionSecurityTest} with the {@code ' OR 1=1--} payload. This row is
 * kept separate because it adds the destructive {@code '; DROP TABLE faults;--} payload and the
 * post-condition that the table still exists and still holds its rows.</p>
 *
 * <p><b>Adaptation note.</b> {@code FaultController.getAll()} declares no {@code search} request
 * parameter, and Spring silently ignores unknown query params — so on that endpoint these payloads
 * can only demonstrate "nothing changed". The test therefore also fires both payloads at
 * {@code GET /api/inventory/materials/search}, the closest endpoint in the module that really does
 * bind a caller-supplied {@code search} string into a repository query, which is where an actual
 * injection would surface.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FaultSecurityTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private jakarta.persistence.EntityManager em;

    /** FAULT-017 Test Data. */
    private static final String OR_TRUE   = "' OR 1=1--";
    private static final String DROP_TABLE = "'; DROP TABLE faults;--";

    private String adminBearer() {
        return "Bearer " + jwt.createAccessToken(9201L, "secadmin", "ADMIN", 1L);
    }

    private static void assertNoSqlErrorLeaked(String body, String where) {
        assertFalse(body.contains("SQLSyntaxErrorException")
                 || body.contains("SQLException")
                 || body.contains("You have an error in your SQL syntax"),
            where + " leaked a SQL error — the payload reached the SQL parser. Body: " + body);
    }

    @Test
    void sqlInjectionOnSearch_blocked() throws Exception {
        String admin = adminBearer();
        long rowsBefore = faultRepo.count();

        // ── Steps 1-2: GET /api/faults?search=' OR 1=1-- must behave normally ────────────
        MvcResult orTrue = mvc.perform(get("/api/faults")
                .param("search", OR_TRUE)
                .header("Authorization", admin))
            .andReturn();

        String orTrueBody = orTrue.getResponse().getContentAsString();
        assertEquals(200, orTrue.getResponse().getStatus(),
            "The ' OR 1=1-- payload must be handled normally (200), not crash the request. Body: "
                + orTrueBody);
        assertNoSqlErrorLeaked(orTrueBody, "GET /api/faults?search=' OR 1=1--");

        // ── Steps 3-4: the destructive payload must not produce a 500 ────────────────────
        MvcResult drop = mvc.perform(get("/api/faults")
                .param("search", DROP_TABLE)
                .header("Authorization", admin))
            .andReturn();

        int    dropStatus = drop.getResponse().getStatus();
        String dropBody   = drop.getResponse().getContentAsString();
        assertTrue(dropStatus == 200 || dropStatus == 400,
            "The DROP TABLE payload must yield 200 or 400, never a 500 server error. Status "
                + dropStatus + ", body: " + dropBody);
        assertNoSqlErrorLeaked(dropBody, "GET /api/faults?search='; DROP TABLE faults;--");

        // ── The same two payloads against a parameter that IS bound into a query ────────
        for (String payload : new String[] {OR_TRUE, DROP_TABLE}) {
            MvcResult materials = mvc.perform(get("/api/inventory/materials/search")
                    .param("search", payload)
                    .header("Authorization", admin))
                .andReturn();

            String body = materials.getResponse().getContentAsString();
            assertEquals(200, materials.getResponse().getStatus(),
                "Parameterized search must accept " + payload + " as a plain string. Body: " + body);
            assertNoSqlErrorLeaked(body, "materials search with " + payload);
            // Matched literally, so nothing matches. Had the payload been executed, `OR 1=1`
            // would have returned every row instead.
            assertEquals("[]", body.trim(),
                "Payload " + payload + " must be matched literally (0 results), not executed as "
                    + "SQL. Body: " + body);
        }

        // ── Step 5: the faults table still exists and still holds its rows ──────────────
        long rowsAfter = assertDoesNotThrow(() -> em.createQuery(
                "SELECT COUNT(f) FROM Fault f", Long.class).getSingleResult(),
            "Querying the faults table after the DROP TABLE payload must still work — if the "
                + "table had been dropped this would fail");
        assertEquals(rowsBefore, rowsAfter,
            "faults row count changed after the injection attempts — data was modified or dropped");
    }
}
