package lk.slt.fieldops.controller;

import lk.slt.fieldops.config.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AUTH-006, AUTH-007, AUTH-008 — role-based access control on the admin surface.
 *
 * <p><b>Harness.</b> {@code @SpringBootTest @AutoConfigureMockMvc @Transactional} against the
 * real MySQL database, tokens minted with the app's own {@link JwtTokenProvider} — the
 * {@code BillDisputeAmendmentIntegrationTest} convention. Requests traverse the ACTUAL
 * {@code SecurityConfig} filter chain and the {@code @PreAuthorize} method-security AOP, so both
 * tiers of enforcement (URL matchers and annotations) are genuinely exercised. MockMvc replaces
 * the sheet's REST Assured, which is not a dependency of this module.</p>
 *
 * <p><b>Two different 403 paths.</b> A role rejected by a {@code SecurityConfig} URL matcher is
 * denied inside the filter chain, so its 403 has no JSON body. A role rejected by
 * {@code @PreAuthorize} reaches the controller layer, where {@code GlobalExceptionHandler}
 * renders "Access Denied". Assertions below only demand a body where the denial genuinely comes
 * from the annotation.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RbacIntegrationTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "rbac" + userId, role, 1L);
    }

    private MvcResult getWith(String path, String bearer) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer)).andReturn();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTH-006 — an ADMIN token reaches every admin route
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void adminJwt_accessesAdminRoutes_returns200() throws Exception {
        String admin = bearer(910001L, "ADMIN");

        // ── Step 1: GET /api/faults is @PreAuthorize ADMIN/SUPER_ADMIN ─────────────────────
        MvcResult faults = getWith("/api/faults", admin);
        assertEquals(200, faults.getResponse().getStatus(),
            "An ADMIN token must be able to list faults. Body: "
                + faults.getResponse().getContentAsString());

        // ── Step 2: GET /api/users is @PreAuthorize SUPER_ADMIN/ADMIN/TEAM_LEAD ────────────
        MvcResult users = getWith("/api/users", admin);
        assertEquals(200, users.getResponse().getStatus(),
            "An ADMIN token must be able to list users. Body: "
                + users.getResponse().getContentAsString());

        // ── Step 3: the payment-approval route must not reject an ADMIN on role grounds ────
        // NOTE: SecurityConfig reserves "/api/payments/*/approve" for ADMIN/SUPER_ADMIN, but
        // PaymentController has no /approve handler (the admin decision endpoints are
        // PATCH /{id}/review and PATCH /{id}/amend). The request therefore passes authorization
        // and then 404s on routing — which is exactly the "200 or 404, never 403" the row asks
        // for, and proves the ADMIN role is not the thing blocking it.
        MvcResult approve = mvc.perform(post("/api/payments/{id}/approve", 1L)
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andReturn();
        int approveStatus = approve.getResponse().getStatus();
        assertNotEquals(403, approveStatus,
            "An ADMIN must never be Forbidden from the payment-approval route. Body: "
                + approve.getResponse().getContentAsString());
        assertNotEquals(401, approveStatus,
            "A valid ADMIN token must authenticate. Body: "
                + approve.getResponse().getContentAsString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTH-007 — a CLIENT token is refused the admin surface
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void clientJwt_usersEndpoint_returns403() throws Exception {
        String client = bearer(920002L, "CLIENT");

        // ── Steps 1 & 2 ────────────────────────────────────────────────────────────────────
        MvcResult users = getWith("/api/users", client);
        int    status = users.getResponse().getStatus();
        String body   = users.getResponse().getContentAsString();

        assertEquals(403, status,
            "A CLIENT token must be Forbidden from the ADMIN-only user list, got " + status
                + ". Body: " + body);

        // ── Step 3: the denial is reported as an access-control failure ───────────────────
        assertTrue(body.contains("Access Denied"),
            "The 403 body should carry the AccessDeniedException message. Body: " + body);

        // ── Step 4: no user data leaks ─────────────────────────────────────────────────────
        assertFalse(body.contains("\"username\""),
            "No user records may be returned to a CLIENT. Body: " + body);

        // The fault-assignment route (the row writes it as PATCH; the real mapping is
        // POST /api/faults/{id}/assign) is reserved for ADMIN/SUPER_ADMIN by a SecurityConfig
        // URL matcher, so the denial happens inside the filter chain and carries no body.
        MvcResult assign = mvc.perform(post("/api/faults/{id}/assign", 1L)
                .header("Authorization", client)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andReturn();
        assertEquals(403, assign.getResponse().getStatus(),
            "A CLIENT must be Forbidden from assigning faults. Body: "
                + assign.getResponse().getContentAsString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTH-008 — a TECHNICIAN token is refused the admin surface
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void techJwt_adminEndpoints_returns403() throws Exception {
        String tech = bearer(930003L, "TECHNICIAN");

        // ── Step 1: /api/payments is TEAM_LEAD/ADMIN/SUPER_ADMIN (SecurityConfig matcher) ──
        assertEquals(403, getWith("/api/payments", tech).getResponse().getStatus(),
            "A TECHNICIAN must be Forbidden from the payments collection");

        // ── Step 2: /api/users is SUPER_ADMIN/ADMIN/TEAM_LEAD (@PreAuthorize) ──────────────
        MvcResult users = getWith("/api/users", tech);
        assertEquals(403, users.getResponse().getStatus(),
            "A TECHNICIAN must be Forbidden from the user list. Body: "
                + users.getResponse().getContentAsString());
        assertFalse(users.getResponse().getContentAsString().contains("\"username\""),
            "No user records may be returned to a TECHNICIAN");

        // ── Step 3: fault assignment is ADMIN/SUPER_ADMIN ─────────────────────────────────
        MvcResult assign = mvc.perform(post("/api/faults/{id}/assign", 1L)
                .header("Authorization", tech)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andReturn();
        assertEquals(403, assign.getResponse().getStatus(),
            "A TECHNICIAN must be Forbidden from assigning faults. Body: "
                + assign.getResponse().getContentAsString());
    }
}
