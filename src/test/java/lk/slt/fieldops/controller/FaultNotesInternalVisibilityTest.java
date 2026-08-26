package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.FaultAssignmentDTO;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * QA_Compliance_Consolidated_Report.md — internal-notes CLIENT-exposure finding, discovered during
 * the §E SRS-documentation inventory: {@code GET /api/faults/{id}/notes} is {@code @PreAuthorize}'d
 * for {@code CLIENT} alongside staff roles, and {@code internal} is a bare caller-supplied query
 * param — the concern was that nothing stops a CLIENT from passing {@code ?internal=true} and
 * reading staff-only notes about their own fault.
 *
 * <p><b>Live-verified finding: the exposure does not exist as filed.</b>
 * {@code FaultController.getNotes} ({@code FaultController.java:266-278}) already computes an
 * {@code isStaff} check (authority contains {@code ADMIN}/{@code TECHNICIAN}/{@code TEAM_LEAD} —
 * {@code ROLE_SUPER_ADMIN} matches via the {@code ADMIN} substring) and passes
 * {@code internal && isStaff} to the service, not the raw request param. A CLIENT's {@code isStaff}
 * is always {@code false}, so {@code internal} is force-false for them regardless of what they
 * request. Confirmed pre-existing (committed at {@code HEAD}, not part of any uncommitted diff —
 * {@code git show HEAD:.../FaultController.java} carries the identical guard) — this was not fixed
 * as part of this pass because there was nothing to fix; the original §E inventory's claim was
 * wrong, caused by a truncated file read that missed lines 272-277 during that investigation.
 *
 * <p>This test exists to make that live-verified conclusion durable and regression-checked, exactly
 * as the finding asked for, rather than leaving the guard's correctness resting on a one-time
 * manual read.
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain — real JWT filter, real
 * {@code @PreAuthorize}, real MySQL, {@code @Transactional} rollback (this session's established
 * convention for this controller family).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FaultNotesInternalVisibilityTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private ObjectMapper     json;

    private static final Long REAL_BRANCH_ID = 1L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "fn" + userId, role, REAL_BRANCH_ID);
    }

    private User newUser(User.Role role) {
        long n = uniq();
        User u = new User();
        u.setUsername("fn" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_BRANCH_ID);
        return userRepo.save(u);
    }

    private ReportFaultRequest reportRequest() {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");
        req.setDescription("No internet since morning");
        req.setLocationAddress("No. 5 Main Street, Colombo 03");
        req.setLocationCity("Colombo");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(REAL_BRANCH_ID);
        req.setPriority("HIGH");
        return req;
    }

    private Long reportFaultAs(User client) throws Exception {
        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reportRequest())))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus(),
            "Fault setup POST failed. Body: " + res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void addNote(Long faultId, User author, String role, String content, boolean isInternal) throws Exception {
        FaultAssignmentDTO.AddNoteRequest req = new FaultAssignmentDTO.AddNoteRequest();
        req.setContent(content);
        req.setNoteType("GENERAL");
        req.setInternal(isInternal);
        MvcResult res = mvc.perform(post("/api/faults/{id}/notes", faultId)
                .header("Authorization", bearer(author.getId(), role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
            "Note setup POST failed. Body: " + res.getResponse().getContentAsString());
    }

    private boolean containsContent(JsonNode notes, String content) {
        for (JsonNode n : notes) {
            if (content.equals(n.path("content").asText())) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // The core finding: CLIENT + ?internal=true must NOT surface internal notes
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void client_requestingInternalTrue_neverReceivesInternalNotes() throws Exception {
        User client = newUser(User.Role.CLIENT);
        User admin  = newUser(User.Role.ADMIN);
        Long faultId = reportFaultAs(client);

        addNote(faultId, admin, "ADMIN", "Internal triage note - staff only", true);
        addNote(faultId, admin, "ADMIN", "Public note - visible to client", false);

        // The exact attack the finding described: CLIENT explicitly asking for internal=true.
        MvcResult res = mvc.perform(get("/api/faults/{id}/notes", faultId)
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .param("internal", "true"))
            .andReturn();
        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        JsonNode notes = json.readTree(body);

        assertFalse(containsContent(notes, "Internal triage note - staff only"),
            "A CLIENT explicitly passing ?internal=true must never receive an internal note. Body: " + body);
        assertTrue(containsContent(notes, "Public note - visible to client"),
            "A CLIENT must still see public notes on their own fault. Body: " + body);
    }

    @Test
    void client_defaultRequest_seesOnlyPublicNotes() throws Exception {
        User client = newUser(User.Role.CLIENT);
        User admin  = newUser(User.Role.ADMIN);
        Long faultId = reportFaultAs(client);

        addNote(faultId, admin, "ADMIN", "Internal note, default-request check", true);
        addNote(faultId, admin, "ADMIN", "Public note, default-request check", false);

        MvcResult res = mvc.perform(get("/api/faults/{id}/notes", faultId)
                .header("Authorization", bearer(client.getId(), "CLIENT")))
            .andReturn();
        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        JsonNode notes = json.readTree(body);

        assertFalse(containsContent(notes, "Internal note, default-request check"),
            "Body: " + body);
        assertTrue(containsContent(notes, "Public note, default-request check"),
            "Body: " + body);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Sanity: staff roles genuinely can still see internal notes — the guard must not overcorrect
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void staffRoles_requestingInternalTrue_doReceiveInternalNotes() throws Exception {
        User client = newUser(User.Role.CLIENT);
        User admin  = newUser(User.Role.ADMIN);
        User teamLead = newUser(User.Role.TEAM_LEAD);
        User technician = newUser(User.Role.TECHNICIAN);
        User superAdmin = newUser(User.Role.SUPER_ADMIN);
        Long faultId = reportFaultAs(client);

        addNote(faultId, admin, "ADMIN", "Staff-visible internal note", true);

        for (User staff : new User[] {admin, teamLead, technician, superAdmin}) {
            String role = staff.getRole().name();
            MvcResult res = mvc.perform(get("/api/faults/{id}/notes", faultId)
                    .header("Authorization", bearer(staff.getId(), role))
                    .param("internal", "true"))
                .andReturn();
            String body = res.getResponse().getContentAsString();
            assertEquals(200, res.getResponse().getStatus(), role + " — Body: " + body);
            JsonNode notes = json.readTree(body);
            assertTrue(containsContent(notes, "Staff-visible internal note"),
                role + " must still see internal notes when explicitly requesting them. Body: " + body);
        }
    }
}
