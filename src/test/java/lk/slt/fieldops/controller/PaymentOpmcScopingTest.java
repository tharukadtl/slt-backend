package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.PaymentRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * QA_Compliance_Consolidated_Report.md §A-4 ("Cross-OPMC bill exposure") — expanded scope.
 *
 * <p>The finding was originally logged against three unscoped list calls
 * ({@code PaymentsPage.js}'s {@code GET /api/payments/pending} / {@code /all}). Investigation before
 * implementing found the true exposure is broader: {@code GET /api/payments/opmc/{opmcId}} — the
 * endpoint the original write-up called "already-scoped" and a candidate replacement for the unscoped
 * pair — never actually enforced anything; it took an arbitrary client-supplied {@code opmcId} with no
 * check that it matched the caller's own OPMC. And the single-payment/action endpoints
 * ({@code GET /{id}}, {@code PATCH /{id}/review} [approve/reject/adjust], {@code PATCH /{id}/amend},
 * {@code GET /{id}/approvals}) had zero OPMC boundary at all — not a lesser version of one, none. This
 * class covers all five endpoint groups against the now-added {@code OpmcAccessGuard} checks.
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain, matching {@code ReportOpmcScopingTest}'s and
 * {@code OpmcWriteActionsRoleRestrictionTest}'s convention: real JWT filter, real {@code @PreAuthorize},
 * real MySQL, {@code @Transactional} rollback.</p>
 *
 * <p><b>Granularity note.</b> Scoping here is OPMC-level via {@code OpmcAccessGuard}, matching the
 * finding's own SRS citation (5.5.6 line 480, "OPMC Admins who see only their own OPMC's data") and this
 * session's established {@code OpmcAccessGuard} convention. A TEAM_LEAD caller is scoped to their own
 * OPMC here, not their own Work Group — Work-Group-level narrowing (matching
 * {@code MaterialRequestService}'s approve/reject boundary) is a genuine, separate follow-up, not decided
 * as part of this fix.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentOpmcScopingTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository userRepo;
    @Autowired private OpmcRepository opmcRepo;
    @Autowired private PaymentRepository paymentRepo;
    @Autowired private ObjectMapper json;

    private static final Long REAL_OPMC_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "pos" + userId, role, opmcId);
    }

    private User newUser(User.Role role, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("pos" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private Opmc newOtherOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("POS Other OPMC " + n);
        o.setCode("POS" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private Payment newPayment(Long opmcId, Payment.PaymentStatus status) {
        long n = uniq();
        Payment p = new Payment();
        p.setPaymentNumber("PAY-POS-" + n);
        p.setPaymentReference("PAY-POS-" + n);
        p.setJobId(7L);
        p.setJobNumber("JOB-POS-" + n);
        p.setOpmcId(opmcId);
        p.setCustomerId(900L);
        p.setCustomerName("POS Client " + n);
        p.setTeamLeadId(500L);
        p.setTeamLeadName("POS Test TL");
        p.setMaterialsFocTotal(new BigDecimal("50.00"));
        p.setMaterialsChargeableTotal(new BigDecimal("1000.00"));
        p.setLabourCharge(new BigDecimal("200.00"));
        p.setTotalAmount(new BigDecimal("1250.00"));
        if (status == Payment.PaymentStatus.FINAL || status == Payment.PaymentStatus.DISPUTED) {
            p.setApprovedAmount(new BigDecimal("1250.00"));
        }
        p.setStatus(status);
        return paymentRepo.save(p);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Group 1 — GET /api/payments/pending and GET /api/payments/all (the originally-logged finding)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void pending_adminScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        Payment own = newPayment(REAL_OPMC_ID, Payment.PaymentStatus.DRAFT);
        Payment foreign = newPayment(other.getId(), Payment.PaymentStatus.DRAFT);
        userRepo.flush();
        paymentRepo.flush();

        JsonNode adminBody = json.readTree(mvc.perform(get("/api/payments/pending")
                .header("Authorization", bearer(admin.getId(), "ADMIN", REAL_OPMC_ID)))
            .andReturn().getResponse().getContentAsString());
        JsonNode superAdminBody = json.readTree(mvc.perform(get("/api/payments/pending")
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN", REAL_OPMC_ID)))
            .andReturn().getResponse().getContentAsString());

        assertTrue(containsPaymentId(adminBody, own.getId()),
            "Admin's pending queue must include their own OPMC's DRAFT payment. Body: " + adminBody);
        assertFalse(containsPaymentId(adminBody, foreign.getId()),
            "Admin's pending queue must NOT include a different OPMC's DRAFT payment. Body: " + adminBody);
        assertTrue(containsPaymentId(superAdminBody, own.getId())
                && containsPaymentId(superAdminBody, foreign.getId()),
            "Super Admin's pending queue must remain unscoped, including both OPMCs. Body: "
                + superAdminBody);
    }

    @Test
    void all_adminScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        Payment own = newPayment(REAL_OPMC_ID, Payment.PaymentStatus.FINAL);
        Payment foreign = newPayment(other.getId(), Payment.PaymentStatus.FINAL);
        userRepo.flush();
        paymentRepo.flush();

        JsonNode adminBody = json.readTree(mvc.perform(get("/api/payments/all")
                .header("Authorization", bearer(admin.getId(), "ADMIN", REAL_OPMC_ID)))
            .andReturn().getResponse().getContentAsString());
        JsonNode superAdminBody = json.readTree(mvc.perform(get("/api/payments/all")
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN", REAL_OPMC_ID)))
            .andReturn().getResponse().getContentAsString());

        assertTrue(containsPaymentId(adminBody, own.getId()),
            "Admin's full history must include their own OPMC's payment. Body: " + adminBody);
        assertFalse(containsPaymentId(adminBody, foreign.getId()),
            "Admin's full history must NOT include a different OPMC's payment (History/Dispute tabs "
                + "both read this endpoint). Body: " + adminBody);
        assertTrue(containsPaymentId(superAdminBody, own.getId())
                && containsPaymentId(superAdminBody, foreign.getId()),
            "Super Admin's full history must remain unscoped, including both OPMCs. Body: "
                + superAdminBody);
    }

    private boolean containsPaymentId(JsonNode body, Long id) {
        for (JsonNode item : body) {
            if (id.equals(item.path("id").asLong())) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Group 2 — GET /api/payments/opmc/{opmcId} — the endpoint that looked scoped but wasn't
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void getByOpmc_adminCanQueryOwnOpmc_butNotAnotherOpmc() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        userRepo.flush();

        MvcResult own = mvc.perform(get("/api/payments/opmc/{opmcId}", REAL_OPMC_ID)
                .header("Authorization", bearer(admin.getId(), "ADMIN", REAL_OPMC_ID)))
            .andReturn();
        assertEquals(200, own.getResponse().getStatus(),
            "An ADMIN must be able to query their own OPMC's payment history. Body: "
                + own.getResponse().getContentAsString());

        MvcResult foreign = mvc.perform(get("/api/payments/opmc/{opmcId}", other.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", REAL_OPMC_ID)))
            .andReturn();
        assertEquals(403, foreign.getResponse().getStatus(),
            "An ADMIN passing a different OPMC's id must now be Forbidden — this is the exact gap "
                + "the investigation found: the endpoint took an arbitrary opmcId with no check it "
                + "matched the caller's own OPMC. Body: " + foreign.getResponse().getContentAsString());
    }

    @Test
    void getByOpmc_superAdminCanQueryAnyOpmc() throws Exception {
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        userRepo.flush();

        MvcResult result = mvc.perform(get("/api/payments/opmc/{opmcId}", other.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN", REAL_OPMC_ID)))
            .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
            "A SUPER_ADMIN must remain able to query any OPMC's payment history. Body: "
                + result.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Group 3 — GET /api/payments/{id} — single payment fetch, the only one of the five groups a
    // TEAM_LEAD is authorized to call at all
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void getById_adminScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        Payment foreign = newPayment(other.getId(), Payment.PaymentStatus.FINAL);
        paymentRepo.flush();
        userRepo.flush();

        MvcResult adminResult = mvc.perform(get("/api/payments/{id}", foreign.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", REAL_OPMC_ID)))
            .andReturn();
        assertEquals(403, adminResult.getResponse().getStatus(),
            "An ADMIN must be Forbidden from fetching a different OPMC's payment by id. Body: "
                + adminResult.getResponse().getContentAsString());

        MvcResult superAdminResult = mvc.perform(get("/api/payments/{id}", foreign.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN", REAL_OPMC_ID)))
            .andReturn();
        assertEquals(200, superAdminResult.getResponse().getStatus(),
            "A SUPER_ADMIN must still be able to fetch any OPMC's payment by id. Body: "
                + superAdminResult.getResponse().getContentAsString());
    }

    @Test
    void getById_teamLeadScopedToOwnOpmc() throws Exception {
        User teamLead = newUser(User.Role.TEAM_LEAD, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        Payment own = newPayment(REAL_OPMC_ID, Payment.PaymentStatus.FINAL);
        Payment foreign = newPayment(other.getId(), Payment.PaymentStatus.FINAL);
        paymentRepo.flush();
        userRepo.flush();

        MvcResult ownResult = mvc.perform(get("/api/payments/{id}", own.getId())
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD", REAL_OPMC_ID)))
            .andReturn();
        assertEquals(200, ownResult.getResponse().getStatus(),
            "A TEAM_LEAD must be able to fetch a payment belonging to their own OPMC. Body: "
                + ownResult.getResponse().getContentAsString());

        MvcResult foreignResult = mvc.perform(get("/api/payments/{id}", foreign.getId())
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD", REAL_OPMC_ID)))
            .andReturn();
        assertEquals(403, foreignResult.getResponse().getStatus(),
            "A TEAM_LEAD must be Forbidden from fetching a different OPMC's payment by id — "
                + "previously there was no boundary at all for this role on this endpoint. Body: "
                + foreignResult.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Group 4 — PATCH /api/payments/{id}/review — approve/reject/adjust. Previously had zero OPMC
    // boundary at all: an OPMC-A Admin could approve/reject/adjust an OPMC-B payment.
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void review_adminCannotReviewAnotherOpmcsPayment() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        Payment foreign = newPayment(other.getId(), Payment.PaymentStatus.DRAFT);
        paymentRepo.flush();
        userRepo.flush();

        MvcResult result = mvc.perform(patch("/api/payments/{id}/review", foreign.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", REAL_OPMC_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}"))
            .andReturn();
        assertEquals(403, result.getResponse().getStatus(),
            "An ADMIN must be Forbidden from approving/rejecting/adjusting a different OPMC's "
                + "payment — there was previously no check here at all. Body: "
                + result.getResponse().getContentAsString());

        Payment reloaded = paymentRepo.findById(foreign.getId()).orElseThrow();
        assertEquals(Payment.PaymentStatus.DRAFT, reloaded.getStatus(),
            "The out-of-scope payment must remain untouched (still DRAFT), not silently approved.");
    }

    @Test
    void review_adminCanReviewOwnOpmcsPayment_superAdminCanReviewAnyOpmcsPayment() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        Payment own = newPayment(REAL_OPMC_ID, Payment.PaymentStatus.DRAFT);
        Payment foreign = newPayment(other.getId(), Payment.PaymentStatus.DRAFT);
        paymentRepo.flush();
        userRepo.flush();

        MvcResult ownResult = mvc.perform(patch("/api/payments/{id}/review", own.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", REAL_OPMC_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}"))
            .andReturn();
        assertEquals(200, ownResult.getResponse().getStatus(),
            "An ADMIN must still be able to approve their own OPMC's payment. Body: "
                + ownResult.getResponse().getContentAsString());

        MvcResult superAdminResult = mvc.perform(patch("/api/payments/{id}/review", foreign.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN", REAL_OPMC_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}"))
            .andReturn();
        assertEquals(200, superAdminResult.getResponse().getStatus(),
            "A SUPER_ADMIN must still be able to review any OPMC's payment. Body: "
                + superAdminResult.getResponse().getContentAsString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Group 5 — PATCH /api/payments/{id}/amend and GET /api/payments/{id}/approvals. Same root
    // finding class as review — no boundary previously existed on either.
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void amend_adminCannotAmendAnotherOpmcsPayment_superAdminCan() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        Payment foreign = newPayment(other.getId(), Payment.PaymentStatus.DISPUTED);
        paymentRepo.flush();
        userRepo.flush();

        MvcResult adminResult = mvc.perform(patch("/api/payments/{id}/amend", foreign.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", REAL_OPMC_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"justification\":\"Cross-OPMC scoping test\"}"))
            .andReturn();
        assertEquals(403, adminResult.getResponse().getStatus(),
            "An ADMIN must be Forbidden from amending a different OPMC's disputed bill. Body: "
                + adminResult.getResponse().getContentAsString());

        MvcResult superAdminResult = mvc.perform(patch("/api/payments/{id}/amend", foreign.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN", REAL_OPMC_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"justification\":\"Cross-OPMC scoping test\"}"))
            .andReturn();
        assertEquals(200, superAdminResult.getResponse().getStatus(),
            "A SUPER_ADMIN must still be able to amend any OPMC's disputed bill. Body: "
                + superAdminResult.getResponse().getContentAsString());
    }

    @Test
    void approvals_adminCannotViewAnotherOpmcsAuditTrail_superAdminCan() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID);
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID);
        Opmc other = newOtherOpmc();
        Payment foreign = newPayment(other.getId(), Payment.PaymentStatus.FINAL);
        paymentRepo.flush();
        userRepo.flush();

        MvcResult adminResult = mvc.perform(get("/api/payments/{id}/approvals", foreign.getId())
                .header("Authorization", bearer(admin.getId(), "ADMIN", REAL_OPMC_ID)))
            .andReturn();
        assertEquals(403, adminResult.getResponse().getStatus(),
            "An ADMIN must be Forbidden from viewing a different OPMC's payment audit trail. Body: "
                + adminResult.getResponse().getContentAsString());

        MvcResult superAdminResult = mvc.perform(get("/api/payments/{id}/approvals", foreign.getId())
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN", REAL_OPMC_ID)))
            .andReturn();
        assertEquals(200, superAdminResult.getResponse().getStatus(),
            "A SUPER_ADMIN must still be able to view any OPMC's payment audit trail. Body: "
                + superAdminResult.getResponse().getContentAsString());
    }
}
