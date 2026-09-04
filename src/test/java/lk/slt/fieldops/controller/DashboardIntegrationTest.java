package lk.slt.fieldops.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Payment;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.PaymentRepository;
import lk.slt.fieldops.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ANA-001, ANA-002 and ANA-012 (09_ANALYTICS, FR-23) — the Admin dashboard's analytics surface:
 * the KPI cards, the fault-trend series behind the line chart, and how fresh the numbers are.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module ({@code pom.xml} carries only {@code spring-boot-starter-test} and
 * {@code spring-security-test}) and adding a test framework is a project decision, not this
 * suite's. MockMvc through the real filter chain is the module's established convention (see
 * {@link KpiIntegrationTest}, {@link JobIntegrationTest}) and drives the identical path: real JWT
 * filter, real {@code @PreAuthorize}, real service, real MySQL. Requires
 * {@code SPRING_PROFILES_ACTIVE=local}. Every test is {@code @Transactional} so its rows roll
 * back.</p>
 *
 * <p><b>Payload corrections against the sheet.</b></p>
 * <ul>
 *   <li>ANA-001/ANA-012 read {@code totalFaults} etc. off the root of
 *       {@code GET /api/dashboard/summary}. That route exists but is a <i>composite</i> — its body
 *       is {@code {kpiSummary, faultDistribution, faultTrends, technicianPerformance,
 *       recentActivity, geographicData}} — so the four KPI values live under
 *       {@code kpiSummary}. (The portal itself never calls {@code /summary}; {@code DashboardPage.js}
 *       fetches the six sub-routes in parallel. Both shapes are asserted here.)</li>
 *   <li>ANA-002 calls {@code /api/dashboard/fault-trend} (singular) and expects
 *       {@code {"data":[…]}} with a {@code count} field per point. The real route is
 *       {@code /api/dashboard/fault-trends} (plural), it returns a <b>bare JSON array</b>, and the
 *       per-day counter is {@code total} ({@code DashboardDTO.FaultTrendPointDTO} —
 *       {@code date, dayOfWeek, total, opened, completed, cancelled, avgResolutionHours}).</li>
 *   <li>ANA-012 asserts a {@code lastUpdated} field. The DTO's generation stamp is
 *       {@code kpiSummary.generatedAt}; there is no cache anywhere in {@code DashboardService}, so
 *       the "≤60s stale" window is asserted against that stamp.</li>
 *   <li>The Pre-Conditions name absolute figures (247 faults / 38 open / 12 completed today).
 *       These tests run against the shared {@code slt_fieldops_db}, so every assertion is written
 *       as a <b>delta</b> against a baseline measured in the same test, which is strictly stronger
 *       than a fixed number — it proves the counter tracks the rows rather than that the database
 *       happens to hold a particular total.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DashboardIntegrationTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private PaymentRepository paymentRepo;
    @Autowired private ObjectMapper     json;
    @Autowired private jakarta.persistence.EntityManager em;

    /** Existing row every branch-scoped FK in this schema resolves against. */
    private static final Long REAL_BRANCH_ID = 1L;
    private static final Long REAL_JOB_ID    = 7L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_BRANCH_ID);
    }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("u" + n);
        u.setPasswordHash("x");
        u.setFirstName("First");
        u.setLastName("Last");
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_BRANCH_ID);
        u.setOpmcName("Colombo Central");
        return userRepo.save(u);
    }

    private Fault newFault(User customer, Fault.FaultStatus status) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-ANA-" + n);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("Analytics dashboard fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setLocationDistrict("Colombo");
        f.setLatitude(6.9271);
        f.setLongitude(79.8612);
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(status);
        return faultRepo.save(f);
    }

    /**
     * A bill awaiting admin review. {@code DRAFT} is this system's real "pending" status —
     * {@code PaymentService.getPendingPayments()} literally queries
     * {@code findByStatusOrderBySubmittedAtAsc(DRAFT)}, and the Admin portal labels DRAFT
     * "Pending Review" (both established in the resolved 2026-07-25 PaymentStatus finding).
     */
    private Payment newPendingPayment(User customer) {
        long n = uniq();
        Payment p = new Payment();
        p.setPaymentNumber("PAY-ANA-" + n);
        p.setPaymentReference("PAY-ANA-" + n);
        p.setJobId(REAL_JOB_ID);
        p.setJobNumber("JOB-ANA-" + n);
        p.setOpmcId(REAL_BRANCH_ID);
        p.setCustomerId(customer.getId());
        p.setCustomerName(customer.getFullName());
        p.setTeamLeadId(500L);
        p.setTeamLeadName("Test TL");
        p.setMaterialsFocTotal(new BigDecimal("0.00"));
        p.setMaterialsChargeableTotal(new BigDecimal("2000.00"));
        p.setLabourCharge(new BigDecimal("500.00"));
        p.setTotalAmount(new BigDecimal("2500.00"));
        p.setStatus(Payment.PaymentStatus.DRAFT);
        return paymentRepo.save(p);
    }

    private void flushAndClear() {
        userRepo.flush();
        faultRepo.flush();
        paymentRepo.flush();
        em.flush();
        em.clear();
    }

    private JsonNode getJson(String url, String auth) throws Exception {
        MvcResult res = mvc.perform(get(url).header("Authorization", auth)).andReturn();
        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(),
            "GET " + url + " must answer 200. Body: " + body);
        return json.readTree(body);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // ANA-001 — the four KPI cards
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Steps 1-6. The four headline KPI values must be present, non-negative, and — the part that
     * matters — actually derived from the rows in the database.
     *
     * <p>Each is asserted as a delta so the test does not depend on the shared database's contents:
     * a baseline is read, a known set of rows is seeded (3 unresolved faults, 1 fault completed
     * today, 1 bill awaiting review), and the summary is read again.</p>
     */
    @Test
    void summary_returns4KpiValues() throws Exception {
        User admin    = newUser(User.Role.ADMIN,  "Analytics Admin");
        User customer = newUser(User.Role.CLIENT, "Analytics Client");
        String hdr    = bearer(admin.getId(), "ADMIN");
        flushAndClear();

        // ── Baseline ────────────────────────────────────────────────────────────
        JsonNode before = getJson("/api/dashboard/summary", hdr).path("kpiSummary");
        assertFalse(before.isMissingNode(),
            "GET /api/dashboard/summary must carry a kpiSummary block. Got: " + before);

        long baseTotal     = before.path("totalFaults").asLong();
        long baseOpen      = before.path("openFaults").asLong();
        long baseCompToday = before.path("completedToday").asLong();
        long basePending   = before.path("pendingPayments").asLong();

        // ── Seed a known, deterministic delta ───────────────────────────────────
        // 3 faults nobody has resolved: one straight off the client's app, one handed to a Team
        // Lead, one parked. All three are "open" in any sense a dashboard card could mean.
        newFault(customer, Fault.FaultStatus.REPORTED);
        newFault(customer, Fault.FaultStatus.ASSIGNED);
        newFault(customer, Fault.FaultStatus.HOLD);
        // 1 fault completed today (updatedAt is stamped now by @PreUpdate/@PrePersist).
        newFault(customer, Fault.FaultStatus.COMPLETED);
        // 1 bill sitting in the admin's review queue.
        newPendingPayment(customer);
        flushAndClear();

        JsonNode after = getJson("/api/dashboard/summary", hdr).path("kpiSummary");

        // ── Step 2 already asserted (200). Steps 3-6 ────────────────────────────
        List<String> gaps = new ArrayList<>();

        // Every value must be a non-negative integer — the row's explicit requirement for
        // pendingPayments, applied to all four.
        for (String field : List.of("totalFaults", "openFaults", "completedToday",
                                    "pendingPayments")) {
            JsonNode v = after.path(field);
            assertTrue(v.isIntegralNumber(),
                "kpiSummary." + field + " must be an integral number. Got: " + v);
            assertTrue(v.asLong() >= 0,
                "kpiSummary." + field + " must be non-negative. Got: " + v.asLong());
        }

        // Step 3 — totalFaults tracks the fault table.
        if (after.path("totalFaults").asLong() != baseTotal + 4) {
            gaps.add("totalFaults: expected " + (baseTotal + 4) + " after seeding 4 faults, got "
                + after.path("totalFaults").asLong());
        }

        // Step 5 — completedToday tracks a fault completed today.
        if (after.path("completedToday").asLong() != baseCompToday + 1) {
            gaps.add("completedToday: expected " + (baseCompToday + 1)
                + " after completing 1 fault today, got "
                + after.path("completedToday").asLong());
        }

        // Step 4 — openFaults tracks unresolved faults.
        if (after.path("openFaults").asLong() != baseOpen + 3) {
            gaps.add("openFaults: expected " + (baseOpen + 3)
                + " after seeding 3 unresolved faults (REPORTED/ASSIGNED/HOLD), got "
                + after.path("openFaults").asLong()
                + " — DashboardService.getKpiSummary counts f.getStatus().name().equals(\"OPEN\"),"
                + " and Fault.FaultStatus has no OPEN constant"
                + " (REPORTED, ASSIGNED, IN_PROGRESS, HOLD, COMPLETED, CANCELLED)");
        }

        // Step 6 — pendingPayments tracks bills awaiting review.
        if (after.path("pendingPayments").asLong() != basePending + 1) {
            gaps.add("pendingPayments: expected " + (basePending + 1)
                + " after seeding 1 bill awaiting review (DRAFT), got "
                + after.path("pendingPayments").asLong()
                + " — DashboardService.getKpiSummary counts p.getStatus().name().equals(\"PENDING\"),"
                + " and Payment.PaymentStatus has no PENDING constant"
                + " (DRAFT, FINAL, NOT_APPROVED, CLARIFICATION_REQUESTED, DISPUTED,"
                + " PENDING_CLIENT_REVIEW, CLIENT_ACCEPTED)");
        }

        assertTrue(gaps.isEmpty(),
            "Dashboard KPI cards must reflect the rows behind them. Gaps found:\n  - "
                + String.join("\n  - ", gaps));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // ANA-002 — the 30-day fault trend series
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Steps 1-6. Thirty points, one per day, strictly sequential, none in the future. */
    @Test
    void faultTrend_30DayPoints() throws Exception {
        User admin = newUser(User.Role.ADMIN, "Trend Admin");
        String hdr = bearer(admin.getId(), "ADMIN");
        flushAndClear();

        JsonNode body = getJson("/api/dashboard/fault-trends?days=30", hdr);

        // Step 3 — 30 items. The response is a bare array, not {"data":[…]}.
        assertTrue(body.isArray(),
            "GET /api/dashboard/fault-trends must return a JSON array. Got: " + body);
        assertEquals(30, body.size(),
            "days=30 must yield exactly 30 daily points. Got " + body.size() + ": " + body);

        // Steps 4-6.
        LocalDate today    = LocalDate.now();
        LocalDate previous = null;
        for (JsonNode point : body) {
            assertFalse(point.path("date").asText().isBlank(),
                "Every trend point must carry a date. Point: " + point);
            assertTrue(point.path("total").isIntegralNumber(),
                "Every trend point must carry an integral per-day count on `total`"
                    + " (the sheet calls it `count`). Point: " + point);
            assertTrue(point.path("total").asLong() >= 0,
                "A per-day fault count cannot be negative. Point: " + point);

            LocalDate date = LocalDate.parse(point.path("date").asText());

            // Step 6 — no date in the future.
            assertFalse(date.isAfter(today),
                "No trend point may be dated in the future. Point: " + point);

            // Step 5 — sequential, no gaps and no duplicates.
            if (previous != null) {
                assertEquals(previous.plusDays(1), date,
                    "Trend dates must be consecutive with no gaps. " + previous + " was followed"
                        + " by " + date + ". Series: " + body);
            }
            previous = date;
        }

        // The window must actually end today and start 29 days ago.
        assertEquals(today, previous,
            "The 30-day window must end on today. Series: " + body);
        assertEquals(today.minusDays(29),
            LocalDate.parse(body.get(0).path("date").asText()),
            "The 30-day window must start 29 days back. Series: " + body);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // ANA-012 — data freshness
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Steps 1-4. A fault reported through the real client endpoint must be visible on the very
     * next dashboard read, and the summary must carry a generation stamp no more than 60s old.
     */
    @Test
    void lastUpdated_freshness_within60s() throws Exception {
        User admin    = newUser(User.Role.ADMIN,  "Freshness Admin");
        User customer = newUser(User.Role.CLIENT, "Freshness Client");
        String adminHdr = bearer(admin.getId(), "ADMIN");
        flushAndClear();

        long before = getJson("/api/dashboard/summary", adminHdr)
                .path("kpiSummary").path("totalFaults").asLong();

        // Step 1 — a genuinely new fault, created the way a customer creates one.
        String payload = json.writeValueAsString(java.util.Map.of(
                "category",        "INTERNET",
                "description",     "No broadband since this morning, dashboard freshness check",
                "locationAddress", "No. 12, Galle Road, Colombo 03",
                "locationCity",    "Colombo",
                "locationDistrict","Colombo",
                "latitude",        6.9271,
                "longitude",       79.8612,
                "opmcId",          REAL_BRANCH_ID,
                "priority",        "MEDIUM"));

        MvcResult created = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(customer.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andReturn();
        assertTrue(created.getResponse().getStatus() == 200
                || created.getResponse().getStatus() == 201,
            "Reporting a fault must succeed before freshness can be judged. Status "
                + created.getResponse().getStatus() + ", body: "
                + created.getResponse().getContentAsString());
        flushAndClear();

        // Step 2 — read the dashboard immediately.
        java.time.LocalDateTime readAt = java.time.LocalDateTime.now();
        JsonNode kpi = getJson("/api/dashboard/summary", adminHdr).path("kpiSummary");

        // Step 3 — the new fault is already counted (there is no cache in DashboardService at all,
        // so the 60s cache-window escape hatch the row allows is not needed).
        assertEquals(before + 1, kpi.path("totalFaults").asLong(),
            "A fault reported a moment ago must be reflected in the dashboard immediately."
                + " kpiSummary: " + kpi);

        // Step 4 — the generation stamp is present and fresh. The row calls this `lastUpdated`;
        // the real field on KpiSummaryDTO is `generatedAt`.
        String stamp = kpi.path("generatedAt").asText(null);
        assertNotNull(stamp,
            "The dashboard summary must carry a generation timestamp (KpiSummaryDTO.generatedAt;"
                + " the sheet calls it lastUpdated). kpiSummary: " + kpi);

        java.time.LocalDateTime generatedAt = java.time.LocalDateTime.parse(stamp);
        long ageSeconds = java.time.Duration.between(generatedAt, readAt).abs().getSeconds();
        assertTrue(ageSeconds <= 60,
            "The dashboard's data must be at most 60s stale. generatedAt=" + generatedAt
                + " read at " + readAt + " (" + ageSeconds + "s).");
    }
}
