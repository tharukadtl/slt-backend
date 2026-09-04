package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.ReportRequestDTO;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.UserAuditLog;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.PaymentRepository;
import lk.slt.fieldops.repository.UserAuditLogRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Stage F #2/#3 (QA_Compliance_Consolidated_Report.md) — the {@code ReportController} family had
 * zero OPMC boundary on any of its 12 report types plus 3 export formats, for any role — not a
 * deliberate Super-Admin-only design, simply never built. REP-10 (audit-trail) shared the identical
 * root cause. Both now thread {@code OpmcAccessGuard.resolveOpmcFilter} through
 * {@code ReportRequestDTO.callerOpmcId}, reaching the GET endpoints and the export dispatch
 * ({@code generateReportData}) alike.
 *
 * <p><b>Tool/placement.</b> MockMvc through the real filter chain, matching
 * {@code ReportIntegrationTest} (ANA-004/ANA-011) and {@code ReportServiceAuditTrailTest} (REP-10) in
 * this module: real JWT filter, real {@code @PreAuthorize}, real MySQL, {@code @Transactional}
 * rollback.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportOpmcScopingTest {

    @Autowired private MockMvc               mvc;
    @Autowired private JwtTokenProvider      jwt;
    @Autowired private UserRepository        userRepo;
    @Autowired private OpmcRepository        opmcRepo;
    @Autowired private FaultRepository       faultRepo;
    @Autowired private PaymentRepository     paymentRepo;
    @Autowired private UserAuditLogRepository auditLogRepo;
    @Autowired private ObjectMapper          json;

    private static final Long REAL_OPMC_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_OPMC_ID);
    }

    private User newUser(User.Role role, Long opmcId, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("ros" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private Opmc newOtherOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("ROS Other OPMC " + n);
        o.setCode("ROS" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private Fault newFault(Long opmcId, User customer) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-ROS-" + n);
        f.setOpmcId(opmcId);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("Report OPMC scoping fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.REPORTED);
        return faultRepo.save(f);
    }

    private Payment newPayment(Long opmcId, Long customerId) {
        long n = uniq();
        Payment p = new Payment();
        p.setPaymentNumber("PAY-ROS-" + n);
        p.setPaymentReference("PAY-ROS-" + n);
        p.setJobId(7L);
        p.setJobNumber("JOB-ROS-" + n);
        p.setOpmcId(opmcId);
        p.setCustomerId(customerId);
        p.setCustomerName("ROS Client " + n);
        p.setTeamLeadId(500L);
        p.setTeamLeadName("ROS Test TL");
        p.setMaterialsFocTotal(new BigDecimal("50.00"));
        p.setMaterialsChargeableTotal(new BigDecimal("1000.00"));
        p.setLabourCharge(new BigDecimal("200.00"));
        p.setTotalAmount(new BigDecimal("1250.00"));
        p.setApprovedAmount(new BigDecimal("1250.00"));
        p.setStatus(Payment.PaymentStatus.FINAL);
        return paymentRepo.save(p);
    }

    private void newUserAuditEntry(User target, String actorName) {
        UserAuditLog log = new UserAuditLog();
        log.setTargetUserId(target.getId());
        log.setTargetUserName(target.getFullName());
        log.setAction(UserAuditLog.Action.DETAILS_UPDATED);
        log.setPerformedByName(actorName);
        log.setPerformedByRole("SUPER_ADMIN");
        auditLogRepo.save(log);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // fault-trends — Bucket A, Fault.opmcId direct
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void faultTrends_adminScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        User admin      = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Fault Trends Admin");
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID, "Fault Trends Super Admin");
        User customer   = newUser(User.Role.CLIENT, REAL_OPMC_ID, "Fault Trends Client");
        Opmc other       = newOtherOpmc();
        newFault(REAL_OPMC_ID, customer);
        newFault(other.getId(), customer);
        userRepo.flush();
        faultRepo.flush();

        JsonNode adminBody = json.readTree(mvc.perform(get("/api/reports/fault-trends")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn().getResponse().getContentAsString());
        JsonNode superAdminBody = json.readTree(mvc.perform(get("/api/reports/fault-trends")
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN")))
            .andReturn().getResponse().getContentAsString());

        long adminTotal = totalFromSummaryStats(adminBody, "Total Faults");
        long superAdminTotal = totalFromSummaryStats(superAdminBody, "Total Faults");

        assertTrue(superAdminTotal >= adminTotal + 1,
            "Super Admin (unscoped) must see at least one more fault than a same-window Admin "
                + "scoped to a single OPMC. Admin total: " + adminTotal
                + ", Super Admin total: " + superAdminTotal);
    }

    private long totalFromSummaryStats(JsonNode body, String label) {
        for (JsonNode s : body.path("summaryStats")) {
            if (label.equals(s.path("label").asText())) {
                return Long.parseLong(s.path("value").asText());
            }
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // financial-summary — Bucket A (resolved in scope), Payment.opmcId direct
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void financialSummary_adminScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        User admin      = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Financial Admin");
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID, "Financial Super Admin");
        User customer   = newUser(User.Role.CLIENT, REAL_OPMC_ID, "Financial Client");
        Opmc other       = newOtherOpmc();
        newPayment(REAL_OPMC_ID, customer.getId());
        newPayment(other.getId(), customer.getId());
        userRepo.flush();
        paymentRepo.flush();

        JsonNode adminBody = json.readTree(mvc.perform(get("/api/reports/financial-summary")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn().getResponse().getContentAsString());
        JsonNode superAdminBody = json.readTree(mvc.perform(get("/api/reports/financial-summary")
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN")))
            .andReturn().getResponse().getContentAsString());

        int adminSubmitted = adminBody.path("data").path("totalPaymentsSubmitted").asInt();
        int superAdminSubmitted = superAdminBody.path("data").path("totalPaymentsSubmitted").asInt();

        assertTrue(superAdminSubmitted >= adminSubmitted + 1,
            "Super Admin (unscoped) must see at least one more payment than a same-window Admin "
                + "scoped to a single OPMC. Admin: " + adminSubmitted
                + ", Super Admin: " + superAdminSubmitted + ". Admin body: " + adminBody);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // attendance — Bucket A, previously a client-supplied opmcId, now caller-derived
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void attendance_adminSeesOnlyOwnOpmcTechnicians() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Attendance Admin");
        Opmc other = newOtherOpmc();
        User ownTech     = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Attendance Own Tech " + uniq());
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Attendance Foreign Tech " + uniq());
        userRepo.flush();

        JsonNode body = json.readTree(mvc.perform(get("/api/reports/attendance")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn().getResponse().getContentAsString());

        boolean hasOwn = false, hasForeign = false;
        for (JsonNode s : body.path("summaryStats")) {
            String label = s.path("label").asText();
            if (label.equals(ownTech.getFullName())) hasOwn = true;
            if (label.equals(foreignTech.getFullName())) hasForeign = true;
        }

        assertTrue(hasOwn, "An Admin's attendance report must include their own OPMC's technician. "
            + "Body: " + body);
        assertFalse(hasForeign, "An Admin's attendance report must not include a different OPMC's "
            + "technician — the opmcId filter used to be a client-supplied param, now caller-derived. "
            + "Body: " + body);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // audit-trail (REP-10, Stage F #3) — same root cause as Stage F #2
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void auditTrail_adminScopedToOwnOpmc_superAdminUnscoped() throws Exception {
        User admin      = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Audit Trail Admin");
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID, "Audit Trail Super Admin");
        Opmc other       = newOtherOpmc();
        User ownTarget     = newUser(User.Role.CLIENT, REAL_OPMC_ID, "Audit Own Target " + uniq());
        User foreignTarget = newUser(User.Role.CLIENT, other.getId(), "Audit Foreign Target " + uniq());
        userRepo.flush();
        newUserAuditEntry(ownTarget, "Fixture Actor");
        newUserAuditEntry(foreignTarget, "Fixture Actor");
        auditLogRepo.flush();

        JsonNode adminBody = json.readTree(mvc.perform(get("/api/reports/audit-trail")
                .param("entityType", "USER")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn().getResponse().getContentAsString());
        JsonNode superAdminBody = json.readTree(mvc.perform(get("/api/reports/audit-trail")
                .param("entityType", "USER")
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN")))
            .andReturn().getResponse().getContentAsString());

        assertAll("REP-10 is scoped for Admin, unscoped for Super Admin",
            () -> assertTrue(containsEntityId(adminBody, ownTarget.getFullName()),
                "Admin's audit trail must include their own OPMC's USER entry. Body: " + adminBody),
            () -> assertFalse(containsEntityId(adminBody, foreignTarget.getFullName()),
                "Admin's audit trail must not include a different OPMC's USER entry. Body: "
                    + adminBody),
            () -> assertTrue(containsEntityId(superAdminBody, ownTarget.getFullName())
                    && containsEntityId(superAdminBody, foreignTarget.getFullName()),
                "Super Admin's audit trail must be unscoped, including both OPMCs' entries. Body: "
                    + superAdminBody)
        );
    }

    private boolean containsEntityId(JsonNode body, String entityId) {
        for (JsonNode item : body.path("data")) {
            if (entityId.equals(item.path("entityId").asText())) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // kpi-performance (REP-08) — reaches the same leaderboard scoping via the report endpoint
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void kpiPerformanceReport_adminScopedToOwnOpmc() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Kpi Report Admin");
        Opmc other = newOtherOpmc();
        User ownTech     = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Kpi Report Own Tech " + uniq());
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Kpi Report Foreign Tech " + uniq());
        userRepo.flush();

        JsonNode body = json.readTree(mvc.perform(get("/api/reports/kpi-performance")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn().getResponse().getContentAsString());

        boolean hasOwn = false, hasForeign = false;
        for (JsonNode e : body.path("data")) {
            if (ownTech.getFullName().equals(e.path("technicianName").asText())) hasOwn = true;
            if (foreignTech.getFullName().equals(e.path("technicianName").asText())) hasForeign = true;
        }
        assertTrue(hasOwn, "REP-08 must include the Admin's own OPMC technician. Body: " + body);
        assertFalse(hasForeign, "REP-08 must not include a different OPMC's technician. Body: " + body);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Export shared-dispatch parity — CSV export must scope identically to the GET endpoint,
    // proving generateReportData's shared dispatch carries callerOpmcId through, not just the
    // 12 GET convenience endpoints individually.
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void csvExport_technicianPerformance_scopedSameAsGetEndpoint() throws Exception {
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Export Admin");
        Opmc other = newOtherOpmc();
        User ownTech     = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Export Own Tech " + uniq());
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Export Foreign Tech " + uniq());
        userRepo.flush();

        ReportRequestDTO exportReq = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_TECHNICIAN_PERFORMANCE)
                .period(ReportRequestDTO.PERIOD_THIS_MONTH)
                .format(ReportRequestDTO.FORMAT_CSV)
                .build();

        MvcResult res = mvc.perform(post("/api/reports/export/csv")
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(exportReq)))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "CSV export must succeed. Body: " + res.getResponse().getContentAsString());

        String csv = res.getResponse().getContentAsString();

        assertTrue(csv.contains(ownTech.getFullName()),
            "The CSV export must contain the Admin's own OPMC technician, same as the GET endpoint "
                + "would. CSV: " + csv);
        assertFalse(csv.contains(foreignTech.getFullName()),
            "The CSV export must NOT contain a different OPMC's technician — proving "
                + "generateReportData's shared dispatch carries callerOpmcId through to the export "
                + "path too, not just the 12 GET endpoints individually. CSV: " + csv);
    }

    @Test
    void csvExport_superAdminUnscoped() throws Exception {
        User superAdmin = newUser(User.Role.SUPER_ADMIN, REAL_OPMC_ID, "Export Super Admin");
        Opmc other = newOtherOpmc();
        User ownTech     = newUser(User.Role.TECHNICIAN, REAL_OPMC_ID, "Export SA Own Tech " + uniq());
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Export SA Foreign Tech " + uniq());
        userRepo.flush();

        ReportRequestDTO exportReq = ReportRequestDTO.builder()
                .reportType(ReportRequestDTO.TYPE_TECHNICIAN_PERFORMANCE)
                .period(ReportRequestDTO.PERIOD_THIS_MONTH)
                .format(ReportRequestDTO.FORMAT_CSV)
                .build();

        MvcResult res = mvc.perform(post("/api/reports/export/csv")
                .header("Authorization", bearer(superAdmin.getId(), "SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(exportReq)))
            .andReturn();

        String csv = res.getResponse().getContentAsString();

        assertEquals(200, res.getResponse().getStatus(), "CSV export must succeed. Body: " + csv);
        assertTrue(csv.contains(ownTech.getFullName()) && csv.contains(foreignTech.getFullName()),
            "A Super Admin's CSV export must remain unscoped, containing technicians from every "
                + "OPMC. CSV: " + csv);
    }

    @Test
    void csvExport_ignoresClientSuppliedCallerOpmcId() throws Exception {
        // Security check: callerOpmcId is a field on ReportRequestDTO, which exportCsv binds
        // straight from the client's JSON body. A malicious/naive client setting it directly in
        // the request must not be trusted — the controller always overwrites it server-side.
        User admin = newUser(User.Role.ADMIN, REAL_OPMC_ID, "Export Spoof Admin");
        Opmc other = newOtherOpmc();
        User foreignTech = newUser(User.Role.TECHNICIAN, other.getId(), "Export Spoof Foreign Tech " + uniq());
        userRepo.flush();

        String spoofedBody = "{"
            + "\"reportType\":\"TECHNICIAN_PERFORMANCE\","
            + "\"period\":\"THIS_MONTH\","
            + "\"format\":\"CSV\","
            + "\"callerOpmcId\":null"
            + "}";

        MvcResult res = mvc.perform(post("/api/reports/export/csv")
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(spoofedBody))
            .andReturn();

        String csv = res.getResponse().getContentAsString();

        assertEquals(200, res.getResponse().getStatus(), "CSV export must succeed. Body: " + csv);
        assertFalse(csv.contains(foreignTech.getFullName()),
            "A client-supplied callerOpmcId=null (spoofing \"unscoped\") in the export request body "
                + "must be ignored — the controller must overwrite it with the server-resolved value "
                + "regardless of what the client sent. CSV: " + csv);
    }
}
