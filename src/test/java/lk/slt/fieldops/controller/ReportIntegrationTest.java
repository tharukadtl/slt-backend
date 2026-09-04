package lk.slt.fieldops.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * ANA-004 and ANA-011 (09_ANALYTICS, FR-24) — report generation: the fault summary report's
 * content, and whether the technician-performance report honours its filters.
 *
 * <p><b>Tool substitution.</b> REST Assured is not a dependency of this module; MockMvc through
 * the real filter chain against the real MySQL is the module's convention
 * ({@link KpiIntegrationTest}, {@link lk.slt.fieldops.service.ReportServiceAuditTrailTest}) and
 * drives the identical path. Requires {@code SPRING_PROFILES_ACTIVE=local}; {@code @Transactional}
 * so the fixtures roll back.</p>
 *
 * <p><b>Endpoint corrections against the sheet.</b></p>
 * <ul>
 *   <li>ANA-004 calls {@code GET /api/reports/fault-summary?month=2026-04}. There is no
 *       {@code /fault-summary} route and no {@code month} parameter anywhere in
 *       {@code ReportController}. The spec's REP-01 report is
 *       {@code GET /api/reports/daily-fault-summary}, whose window is expressed as
 *       {@code period=TODAY|THIS_WEEK|THIS_MONTH|LAST_MONTH|CUSTOM} plus
 *       {@code startDate}/{@code endDate}. April 2026 is therefore requested as
 *       {@code startDate=2026-04-01&endDate=2026-04-30}.</li>
 *   <li>The report body is {@code {reportType, reportTitle, period, startDate, endDate,
 *       generatedAt, totalRecords, data}} — the row's fields live under {@code data}.</li>
 *   <li>The row asks for {@code avgResolutionTime} "in minutes"; the DTO field is
 *       {@code avgResolutionTimeHours} and is genuinely in hours. Asserted as hours.</li>
 *   <li>The row's example categories are {@code BROADBAND, FIBER}. {@code Fault.FaultCategory} is
 *       {@code INTERNET, PHONE, FIBER, TV, OTHER} — BROADBAND is a mobile-client alias that
 *       {@code FaultController.normalizeMobileCategory} translates to INTERNET, never a stored
 *       value.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportIntegrationTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private JobRepository    jobRepo;
    @Autowired private ObjectMapper     json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_BRANCH_ID = 1L;

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

    private Fault newFault(User customer, Fault.FaultCategory category,
                           Fault.FaultStatus status) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-REP-" + n);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(category);
        f.setDescription("Report fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setLocationDistrict("Colombo");
        f.setLatitude(6.9271);
        f.setLongitude(79.8612);
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(status);
        return faultRepo.save(f);
    }

    /**
     * {@code Fault.reportedAt} / {@code createdAt} are {@code updatable = false} and stamped by
     * {@code @PrePersist}, so a historical fault can only be produced with a native UPDATE. The
     * report filters on {@code reportedAt}, and {@code completedAt} carries the resolution clock.
     */
    private void backdate(Fault f, LocalDateTime reportedAt, LocalDateTime completedAt) {
        em.createNativeQuery(
                "UPDATE faults SET reported_at = ?1, created_at = ?1, updated_at = ?1,"
                        + " completed_at = ?2 WHERE id = ?3")
            .setParameter(1, reportedAt)
            .setParameter(2, completedAt)
            .setParameter(3, f.getId())
            .executeUpdate();
    }

    private Job newJob(User technician, User teamLead, User customer, Fault fault,
                       Job.JobStatus status, LocalDateTime createdAt) {
        long n = uniq();
        Job j = new Job();
        j.setJobNumber("JOB-REP-" + n);
        j.setFaultId(fault.getId());
        j.setFaultNumber(fault.getFaultNumber());
        j.setCustomerId(customer.getId());
        j.setCustomerName(customer.getFullName());
        j.setTeamLeadId(teamLead.getId());
        j.setTeamLeadName(teamLead.getFullName());
        j.setTechnicianId(technician.getId());
        j.setTechnicianName(technician.getFullName());
        j.setPriority(Job.JobPriority.MEDIUM);
        j.setStatus(status);
        Job saved = jobRepo.save(j);
        jobRepo.flush();
        em.createNativeQuery("UPDATE jobs SET created_at = ?1 WHERE id = ?2")
            .setParameter(1, createdAt)
            .setParameter(2, saved.getId())
            .executeUpdate();
        return saved;
    }

    private void flushAndClear() {
        userRepo.flush();
        faultRepo.flush();
        jobRepo.flush();
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
    // ANA-004 — fault summary report content
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Steps 1-6. The report for a specific month carries its totals, both breakdowns and a
     *  resolution time derived from the faults inside that window. */
    @Test
    void faultSummaryReport_correctData() throws Exception {
        User admin    = newUser(User.Role.ADMIN,  "Report Admin");
        User customer = newUser(User.Role.CLIENT, "Report Client");
        String hdr    = bearer(admin.getId(), "ADMIN");

        final LocalDate aprilStart = LocalDate.of(2026, 4, 1);
        final LocalDate aprilEnd   = LocalDate.of(2026, 4, 30);

        // Baseline for April 2026 in the shared database, before this test's own rows.
        JsonNode base = getJson("/api/reports/daily-fault-summary?period=CUSTOM"
                + "&startDate=" + aprilStart + "&endDate=" + aprilEnd, hdr);
        long baseTotal = base.path("data").path("totalFaults").asLong();
        long baseTv    = categoryCount(base.path("data").path("byCategory"), "TV");

        // Two faults inside April 2026, one resolved in exactly 4 hours; one outside the window
        // so the date filter itself is exercised.
        Fault inWindowCompleted = newFault(customer, Fault.FaultCategory.FIBER,
                                           Fault.FaultStatus.COMPLETED);
        Fault inWindowOpen      = newFault(customer, Fault.FaultCategory.INTERNET,
                                           Fault.FaultStatus.ASSIGNED);
        Fault outOfWindow       = newFault(customer, Fault.FaultCategory.TV,
                                           Fault.FaultStatus.COMPLETED);
        faultRepo.flush();

        backdate(inWindowCompleted, LocalDateTime.of(2026, 4, 10, 9, 0),
                                    LocalDateTime.of(2026, 4, 10, 13, 0));   // 4h
        backdate(inWindowOpen,      LocalDateTime.of(2026, 4, 12, 8, 30), null);
        backdate(outOfWindow,       LocalDateTime.of(2026, 5, 3, 9, 0),
                                    LocalDateTime.of(2026, 5, 3, 10, 0));    // May — excluded
        flushAndClear();

        // Steps 1-2.
        JsonNode body = getJson("/api/reports/daily-fault-summary?period=CUSTOM"
                + "&startDate=" + aprilStart + "&endDate=" + aprilEnd, hdr);

        assertEquals("DAILY_FAULT_SUMMARY", body.path("reportType").asText(),
            "Report body: " + body);
        assertEquals(aprilStart.toString(), body.path("startDate").asText(),
            "The report must echo the requested window. Body: " + body);
        assertEquals(aprilEnd.toString(), body.path("endDate").asText(),
            "The report must echo the requested window. Body: " + body);

        JsonNode data = body.path("data");
        assertFalse(data.isMissingNode(), "The report must carry a data block. Body: " + body);

        // Step 3 — totalFaults present, and counting exactly the two April faults added here.
        assertTrue(data.path("totalFaults").isIntegralNumber(),
            "totalFaults must be present and numeric. data: " + data);
        assertEquals(baseTotal + 2, data.path("totalFaults").asLong(),
            "The April window must pick up exactly the 2 faults reported in April and exclude the"
                + " May one. data: " + data);

        // Step 4 — breakdown by category.
        JsonNode byCategory = data.path("byCategory");
        assertTrue(byCategory.isArray() && byCategory.size() > 0,
            "The report must break faults down by category. data: " + data);
        List<String> categories = new ArrayList<>();
        for (JsonNode c : byCategory) {
            categories.add(c.path("category").asText());
            assertTrue(c.path("count").isIntegralNumber(),
                "Each category bucket must carry a count. Bucket: " + c);
        }
        assertTrue(categories.contains("FIBER"),
            "A FIBER fault reported in April must appear in the category breakdown. Buckets: "
                + byCategory);
        assertTrue(categories.contains("INTERNET"),
            "An INTERNET fault reported in April must appear in the category breakdown"
                + " (the sheet's BROADBAND is a mobile alias FaultController translates to"
                + " INTERNET; it is never stored). Buckets: " + byCategory);
        // The May TV fault must not leak into an April report. The shared database already holds
        // April TV faults of its own, so the assertion is on the delta, not on TV's absence.
        assertEquals(baseTv, categoryCount(byCategory, "TV"),
            "The May TV fault must NOT leak into an April report — the TV bucket must be unchanged"
                + " at " + baseTv + ". Buckets: " + byCategory);

        // Step 5 — breakdown by status.
        JsonNode byStatus = data.path("byStatus");
        assertTrue(byStatus.isArray() && byStatus.size() > 0,
            "The report must break faults down by status. data: " + data);
        List<String> statuses = new ArrayList<>();
        for (JsonNode s : byStatus) statuses.add(s.path("category").asText());
        assertTrue(statuses.contains("COMPLETED") && statuses.contains("ASSIGNED"),
            "Both a COMPLETED and an ASSIGNED April fault must be represented in the status"
                + " breakdown. Buckets: " + byStatus);

        // Step 6 — average resolution time, and the Expected Result's "> 0".
        JsonNode avg = data.path("avgResolutionTimeHours");
        assertTrue(avg.isNumber(),
            "The report must carry an average resolution time (the sheet calls it"
                + " avgResolutionTime in minutes; the real field is avgResolutionTimeHours and is"
                + " genuinely in hours). data: " + data);
        assertTrue(avg.asDouble() > 0,
            "Average resolution time must be greater than 0 when the window contains a resolved"
                + " fault. Got " + avg.asDouble() + ". data: " + data);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // ANA-011 — technician performance report filters
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Steps 1-5. With {@code technicianId} supplied the report must contain only that technician;
     * without it, every technician. The date range must be honoured either way.
     */
    @Test
    void techPerformance_technicianIdFilter() throws Exception {
        User admin    = newUser(User.Role.ADMIN,      "Filter Admin");
        User customer = newUser(User.Role.CLIENT,     "Filter Client");
        User teamLead = newUser(User.Role.TEAM_LEAD,  "Filter Team Lead");
        User target   = newUser(User.Role.TECHNICIAN, "Filter Target Tech");
        User other    = newUser(User.Role.TECHNICIAN, "Filter Other Tech");
        String hdr    = bearer(admin.getId(), "ADMIN");

        Fault fault = newFault(customer, Fault.FaultCategory.INTERNET, Fault.FaultStatus.COMPLETED);
        faultRepo.flush();

        final LocalDate windowStart = LocalDate.of(2026, 4, 1);
        final LocalDate windowEnd   = LocalDate.of(2026, 4, 30);

        // Two jobs inside the window for the target technician, one for somebody else, and one
        // for the target OUTSIDE the window so the date filter is exercised too.
        newJob(target, teamLead, customer, fault, Job.JobStatus.COMPLETED,
               LocalDateTime.of(2026, 4, 5, 9, 0));
        newJob(target, teamLead, customer, fault, Job.JobStatus.COMPLETED,
               LocalDateTime.of(2026, 4, 20, 9, 0));
        newJob(other,  teamLead, customer, fault, Job.JobStatus.COMPLETED,
               LocalDateTime.of(2026, 4, 8, 9, 0));
        newJob(target, teamLead, customer, fault, Job.JobStatus.COMPLETED,
               LocalDateTime.of(2026, 7, 8, 9, 0));   // outside April
        flushAndClear();

        String window = "&startDate=" + windowStart + "&endDate=" + windowEnd + "&period=CUSTOM";

        // Step 5 first — without technicianId, every technician is returned. This establishes that
        // the fixture is visible at all before the filter is judged.
        JsonNode unfiltered = getJson("/api/reports/technician-performance?" + window.substring(1),
                                     hdr);
        JsonNode allRows = unfiltered.path("data");
        assertTrue(allRows.isArray() && allRows.size() > 0,
            "Without a technicianId the report must return every technician. Body: " + unfiltered);
        assertTrue(namesIn(allRows).contains("Filter Target Tech")
                && namesIn(allRows).contains("Filter Other Tech"),
            "Both fixture technicians must appear in the unfiltered report. Rows: "
                + namesIn(allRows));

        // Steps 1-3 — with technicianId, only that technician.
        JsonNode filtered = getJson("/api/reports/technician-performance?technicianId="
                + target.getId() + window, hdr);
        JsonNode rows = filtered.path("data");
        assertTrue(rows.isArray(), "Report data must be an array. Body: " + filtered);

        List<String> foreign = new ArrayList<>();
        for (JsonNode r : rows) {
            if (!String.valueOf(target.getId()).equals(r.path("technicianId").asText())) {
                foreign.add(r.path("technicianName").asText() + "#"
                        + r.path("technicianId").asText());
            }
        }
        List<String> gaps = new ArrayList<>();
        if (!foreign.isEmpty()) {
            gaps.add("technicianId filter ignored: ?technicianId=" + target.getId()
                + " must scope the report to that technician alone, but " + foreign.size()
                + " other technicians came back: " + foreign
                + " — ReportController.getTechnicianPerformance accepts the technicianId request"
                + " parameter and then calls reportService.getTechnicianPerformance(period,"
                + " startDate, endDate) without it, so the filter is silently discarded.");
        }

        // Step 4 — the date range is honoured: only the 2 April jobs, not the July one.
        JsonNode targetRow = null;
        for (JsonNode r : rows) {
            if (String.valueOf(target.getId()).equals(r.path("technicianId").asText())) {
                targetRow = r;
                break;
            }
        }
        assertNotNull(targetRow,
            "The requested technician must be present in the report. Rows: " + rows);
        if (targetRow.path("totalJobsAssigned").asLong() != 2) {
            gaps.add("date range ignored: only the 2 jobs inside 2026-04-01..2026-04-30 may be"
                + " counted, but totalJobsAssigned=" + targetRow.path("totalJobsAssigned").asLong()
                + " (the July job is included) — ReportService.getTechnicianPerformance never"
                + " resolves or applies a date range at all: it does not call resolveDateRange,"
                + " and its per-technician job list is jobRepository.findAll() filtered by"
                + " technicianId only, so startDate/endDate reach the method signature and are"
                + " then discarded exactly as technicianId is. Row: " + targetRow);
        }

        assertTrue(gaps.isEmpty(),
            "Technician performance report filter gaps:\n  - " + String.join("\n  - ", gaps));
    }

    /** How many faults the report's category breakdown attributes to {@code category}. */
    private long categoryCount(JsonNode byCategory, String category) {
        for (JsonNode c : byCategory) {
            if (category.equals(c.path("category").asText())) return c.path("count").asLong();
        }
        return 0L;
    }

    private List<String> namesIn(JsonNode rows) {
        List<String> names = new ArrayList<>();
        for (JsonNode r : rows) names.add(r.path("technicianName").asText());
        return names;
    }
}
