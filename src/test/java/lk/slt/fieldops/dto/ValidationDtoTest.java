package lk.slt.fieldops.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API-017 (11_API_ENDPOINTS) — the four controllers that used to bind raw {@code Map} request
 * bodies now validate through typed {@code @Valid} DTOs, so malformed input is rejected as a clean,
 * field-specific HTTP 400 instead of a raw NPE / 500.
 *
 * <p><b>Why this is new coverage of an already-fixed area, not a duplicate.</b> The underlying
 * defect ("Multiple endpoints bind raw {@code Map} bodies with no {@code @Valid} validation —
 * {@code InventoryController}, {@code VehicleController}, {@code JobController},
 * {@code AuthController}") is recorded as RESOLVED 2026-07-24 in
 * {@code docs/QA_Compliance_Consolidated_Report.md}, and that entry's own verification line reads
 * "Verified via full {@code mvn test} regression pass, 38/38 passing, plus a live end-to-end pass"
 * — i.e. it was verified by <i>nothing breaking</i>, never by an assertion that the new constraints
 * actually reject anything. A repository-wide search for validation coverage finds only
 * {@code CheckInRequestValidationTest} / {@code CheckOutRequestValidationTest} (attendance DTOs, a
 * different controller and a different fix) and {@code FaultValidatorTest} (FAULT-002/003). <b>None
 * of the four DTOs this row names had any test at all.</b></p>
 *
 * <p><b>Why it goes through MockMvc rather than a bare Jakarta {@code Validator}.</b> The two
 * attendance validation tests above exercise a standalone {@code Validator}, which is the right
 * tool for asking "does this annotation fire". This row asks something the standalone approach
 * structurally cannot answer: whether {@code @Valid} is <i>wired into the request path</i> and
 * whether {@code GlobalExceptionHandler} renders the result as a field-specific 400. That
 * distinction is not theoretical here — this project has already been bitten by exactly it: the
 * same 2026-07-24 Resolution Log entry records that {@code JobControllerSignatureTest} invokes the
 * controller method directly via Mockito, "which never runs Bean Validation at all, since
 * {@code @Valid} is enforced by Spring MVC's request-binding pipeline, not by the Java method call
 * itself." So this test drives real HTTP through the real filter chain.</p>
 *
 * <p><b>Tool substitution.</b> The row's {@code Tool} column says REST Assured, which is not a
 * dependency of this module; MockMvc through the real chain is the module's convention and the
 * identical code path (the {@code 13_SECURITY_HARDENING} precedent). Column K is left unchanged.
 * The row's {@code Test Type} says "Unit", but its own {@code Assertion Code} is an HTTP call
 * ({@code given().body(missingField).post('/api/inventory/materials')...}) — the HTTP reading is
 * followed, since that is the only reading under which the assertion means anything.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ValidationDtoTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private ObjectMapper     json;

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "val" + userId, role, 1L);
    }

    /** One probe: an endpoint, the role authorised to reach it, and the field expected to be flagged. */
    private record Probe(String label, MockHttpServletRequestBuilder request, String expectedField) {}

    /**
     * Asserts one endpoint rejects its malformed body as a field-specific 400.
     * Returns a human-readable failure line, or null when the probe passed.
     */
    private String check(Probe probe) throws Exception {
        MvcResult res = mvc.perform(probe.request()).andReturn();
        int    status = res.getResponse().getStatus();
        String body   = res.getResponse().getContentAsString();

        // Step 2, part 1: HTTP 400 — not a 500, and not a silently-accepted 200/201.
        if (status != 400) {
            String why = switch (status) {
                case 500 -> "raw 500 — the missing field reached the service layer instead of "
                            + "being caught by @Valid";
                case 200, 201 -> "the malformed body was ACCEPTED";
                case 401, 403 -> "authorization rejected the probe before validation ran, so this "
                            + "probe proved nothing about validation";
                default -> "unexpected status";
            };
            return probe.label() + ": expected 400, got " + status + " (" + why + "). Body: " + body;
        }

        JsonNode parsed = json.readTree(body);

        // Step 2, part 2: it is a VALIDATION 400, in GlobalExceptionHandler.handleValidation's
        // shape — {status, error:"Validation Failed", fields:{...}} — not a generic business-error
        // 400 from handleRuntime, which would mean the DTO constraint never fired.
        if (!"Validation Failed".equals(parsed.path("error").asText())) {
            return probe.label() + ": got a 400, but not from Bean Validation (error='"
                + parsed.path("error").asText() + "'). A generic business-error 400 means the "
                + "@Valid constraint did not fire. Body: " + body;
        }

        JsonNode fields = parsed.path("fields");
        if (!fields.isObject() || fields.isEmpty()) {
            return probe.label() + ": the 400 carries no 'fields' map, so the client cannot tell "
                + "WHICH field was wrong. Body: " + body;
        }
        if (!fields.has(probe.expectedField())) {
            return probe.label() + ": expected the 'fields' map to name '" + probe.expectedField()
                + "', got " + fields.fieldNames().next() + " … Body: " + body;
        }
        if (fields.path(probe.expectedField()).asText().isBlank()) {
            return probe.label() + ": field '" + probe.expectedField() + "' has a blank message. "
                + "Body: " + body;
        }
        return null;
    }

    @Test
    void allFourControllersRejectMalformedInput() throws Exception {
        // Step 1: POST to each of the four controllers with a required field missing.
        // Each body is otherwise well-formed JSON, so a rejection can only come from Bean
        // Validation rather than from a parse error.
        List<Probe> probes = List.of(

            // ── 1. InventoryController — MaterialDTO.CreateRequest.name is @NotBlank ────────
            new Probe("InventoryController POST /api/inventory/materials (missing 'name')",
                post("/api/inventory/materials")
                    .header("Authorization", bearer(970001L, "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sku\":\"SKU-API017\",\"unit\":\"m\",\"unitPrice\":10.00}"),
                "name"),

            // ── 2. VehicleController — SetVehicleStatusRequest.status is @NotNull ──────────
            new Probe("VehicleController PATCH /api/vehicles/{id}/status (missing 'status')",
                patch("/api/vehicles/{id}/status", 1L)
                    .header("Authorization", bearer(970002L, "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
                "status"),

            // ── 3. JobController — ReassignJobRequest.newTechnicianId is @NotNull ─────────
            new Probe("JobController POST /api/jobs/{id}/reassign (missing 'newTechnicianId')",
                post("/api/jobs/{id}/reassign", 1L)
                    .header("Authorization", bearer(970003L, "TEAM_LEAD"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
                "newTechnicianId"),

            // ── 4. AuthController — RefreshTokenRequest.refreshToken is @NotBlank ─────────
            // Unauthenticated by design: /api/auth/** is permitAll, so this probe also confirms
            // validation runs on the pre-auth surface, not only behind the JWT filter.
            new Probe("AuthController POST /api/auth/refresh (missing 'refreshToken')",
                post("/api/auth/refresh")
                    .header("X-Forwarded-For", "198.51.100." + (1 + (int) (System.nanoTime() % 250)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
                "refreshToken")
        );

        List<String> failures = new ArrayList<>();
        for (Probe probe : probes) {
            String failure = check(probe);
            if (failure != null) failures.add(failure);
        }

        assertTrue(failures.isEmpty(),
            "All four previously-raw-Map endpoints must reject a missing required field with a "
                + "field-specific HTTP 400. " + failures.size() + " of " + probes.size()
                + " did not:\n  - " + String.join("\n  - ", failures));

        // ── Positive control: a blank-but-present value is rejected too, not just an absent key ──
        // @NotNull alone would let "" through; the row's contract is @NotBlank on the string DTOs.
        // Without this, a DTO that had only swapped Map for a typed object with no constraints
        // would still pass everything above for the absent-key case on the @NotNull fields.
        MvcResult blank = mvc.perform(post("/api/auth/refresh")
                .header("X-Forwarded-For", "198.51.100." + (1 + (int) (System.nanoTime() % 250)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"   \"}"))
            .andReturn();
        String blankBody = blank.getResponse().getContentAsString();
        assertEquals(400, blank.getResponse().getStatus(),
            "A present-but-blank refreshToken must be rejected by @NotBlank, not merely a missing "
                + "key by @NotNull. Body: " + blankBody);
        assertEquals("Validation Failed", json.readTree(blankBody).path("error").asText(),
            "…and rejected by Bean Validation specifically. Body: " + blankBody);

        // ── Negative control: a WELL-FORMED body must NOT be rejected as a validation error ──
        // This is what separates "the DTOs validate correctly" from "these endpoints are simply
        // broken and 400 on everything" — which would satisfy every assertion above.
        MvcResult wellFormed = mvc.perform(post("/api/inventory/materials")
                .header("Authorization", bearer(970004L, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"API-017 Probe Cable\",\"sku\":\"SKU-API017-OK\","
                    + "\"unit\":\"m\",\"unitPrice\":25.00,\"stockQuantity\":10,"
                    + "\"minThreshold\":1,\"maxThreshold\":100,\"reorderQuantity\":5}"))
            .andReturn();
        String okBody = wellFormed.getResponse().getContentAsString();
        assertNotEquals(400, wellFormed.getResponse().getStatus(),
            "A complete, valid material payload must NOT be rejected — otherwise the four "
                + "assertions above would be satisfied by an endpoint that simply 400s on "
                + "everything. Body: " + okBody);
        assertFalse(okBody.contains("\"Validation Failed\""),
            "A complete, valid payload must produce no validation errors. Body: " + okBody);
    }
}
