package lk.slt.fieldops.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.KpiTarget;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.KpiTargetRepository;
import lk.slt.fieldops.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * KPI-002, KPI-003, KPI-005 and KPI-011 (06_KPI_PERFORMANCE, FR-16/FR-17/FR-18) — the KPI REST
 * surface: monthly aggregation from real job data, assigning a target to a technician, the
 * leaderboard ordering, and the period filter.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module ({@code pom.xml} carries only {@code spring-boot-starter-test} and
 * {@code spring-security-test}) and adding a test framework is a project decision, not this
 * suite's. MockMvc through the real filter chain is the module's established convention (see
 * {@link JobIntegrationTest}, {@link VehicleIntegrationTest}) and drives the identical path: real
 * JWT filter, real {@code @PreAuthorize}, real service, real MySQL. Every test is
 * {@code @Transactional} so its rows roll back.</p>
 *
 * <p><b>Endpoint corrections against the sheet.</b></p>
 * <ul>
 *   <li>KPI-002 calls {@code POST /api/kpi/calculate?technicianId=5&period=MONTHLY}. No such
 *       endpoint exists — {@code KpiController} exposes no {@code /calculate} route at all, and
 *       nothing in the module recalculates KPI on a schedule. The KPI figures are derived on read
 *       by {@code GET /api/kpi/score/{userId}?period=MONTHLY}
 *       ({@code KpiCalculationService.getPersonalKpi}), which is the same aggregation the row is
 *       describing, so that is what is driven here. (The Admin portal's dead
 *       {@code frontend-admin/src/api/kpi.js} also advertises {@code POST /kpi/calculate}; it has
 *       no importer and its paths do not match the real controller.)</li>
 *   <li>KPI-003 calls {@code POST /api/kpi/targets}. The real route is
 *       {@code POST /api/kpi/targets/assign}.</li>
 *   <li>The response field the sheet calls {@code jobsAssigned} is {@code totalJobs} on
 *       {@code KpiDTO.PersonalKpiDTO}; {@code target.assignedById} is not exposed on
 *       {@code TargetResponseDTO} at all (only {@code assignedBy}, a display name), so the assignee
 *       is verified against the persisted {@code kpi_targets} row instead.</li>
 *   <li>Technician id 5 in the Test Data column is a shared-database id; every test here creates
 *       its own users and jobs so the assertions do not depend on whatever else is in
 *       {@code slt_fieldops_db}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class KpiIntegrationTest {

    @Autowired private MockMvc             mvc;
    @Autowired private JwtTokenProvider    jwt;
    @Autowired private UserRepository      userRepo;
    @Autowired private JobRepository       jobRepo;
    @Autowired private FaultRepository     faultRepo;
    @Autowired private KpiTargetRepository targetRepo;
    @Autowired private ObjectMapper        json;
    @Autowired private jakarta.persistence.EntityManager em;

    /** Existing row every branch-scoped FK in this schema resolves against. */
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

    /** A Fault for jobs to hang off — {@code jobs.fault_id} is NOT NULL. */
    private Fault newFault(User customer) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-KPI-" + n);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("KPI fixture " + n);
        f.setLocationAddress("Colombo 03");
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(Fault.FaultStatus.COMPLETED);
        return faultRepo.save(f);
    }

    /**
     * {@code total} jobs for {@code technician}, of which {@code completed} are COMPLETED and the
     * rest IN_PROGRESS. Created now, so they fall inside every period window the API offers.
     */
    private List<Job> newJobsFor(User technician, User teamLead, User customer, Fault fault,
                                 int total, int completed) {
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            long n = uniq();
            Job j = new Job();
            j.setJobNumber("JOB-KPI-" + n);
            j.setFaultId(fault.getId());
            j.setFaultNumber(fault.getFaultNumber());
            j.setCustomerId(customer.getId());
            j.setCustomerName(customer.getFullName());
            j.setTeamLeadId(teamLead.getId());            // jobs.team_lead_id is NOT NULL
            j.setTeamLeadName(teamLead.getFullName());
            j.setTechnicianId(technician.getId());
            j.setTechnicianName(technician.getFullName());
            j.setPriority(Job.JobPriority.MEDIUM);
            j.setStatus(i < completed ? Job.JobStatus.COMPLETED : Job.JobStatus.IN_PROGRESS);
            jobs.add(jobRepo.save(j));
        }
        return jobs;
    }

    private void flushAndClear() {
        userRepo.flush();
        faultRepo.flush();
        jobRepo.flush();
        em.flush();
        em.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // KPI-002 — monthly KPI aggregated from the technician's real job rows
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void monthlyAggregation_returns200() throws Exception {
        // ── Arrange: one technician with exactly 10 jobs this month, 7 of them completed ─────
        User admin    = newUser(User.Role.ADMIN,      "Admin Amelia");
        User teamLead = newUser(User.Role.TEAM_LEAD,  "TL Tharindu");
        User customer = newUser(User.Role.CLIENT,     "Client Chathura");
        User tech     = newUser(User.Role.TECHNICIAN, "Tech Kasun");
        Fault fault   = newFault(customer);
        newJobsFor(tech, teamLead, customer, fault, 10, 7);
        flushAndClear();

        // ── Act: step 1 ──────────────────────────────────────────────────────────────────────
        MvcResult res = mvc.perform(get("/api/kpi/score/" + tech.getId() + "?period=MONTHLY")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        String body = res.getResponse().getContentAsString();

        // ── Step 2 ───────────────────────────────────────────────────────────────────────────
        assertEquals(200, res.getResponse().getStatus(),
            "Reading a technician's monthly KPI must return 200. Body: " + body);

        JsonNode kpi = json.readTree(body);

        assertAll("the monthly scorecard is aggregated from the technician's real jobs",

            () -> assertEquals(tech.getId().longValue(), kpi.path("technicianId").asLong(),
                "The scorecard must be for the technician requested, body: " + body),
            () -> assertEquals("MONTHLY", kpi.path("period").asText(),
                "The scorecard must report the period it was asked for"),

            // ── Step 3: jobs assigned (the sheet's jobsAssigned is totalJobs here) ──────────
            () -> assertEquals(10, kpi.path("totalJobs").asInt(),
                "All 10 jobs created for this technician this month must be counted, body: "
                    + body),
            () -> assertEquals(7, kpi.path("completedJobs").asInt(),
                "Exactly the 7 COMPLETED jobs must be counted as completed"),
            () -> assertEquals(3, kpi.path("inProgressJobs").asInt(),
                "The 3 IN_PROGRESS jobs must be counted as in progress"),

            // ── Step 4: completion rate is completed / total ────────────────────────────────
            () -> assertEquals(70.0, kpi.path("completionRate").asDouble(), 0.05,
                "completionRate must be 7/10 expressed as a percentage, was "
                    + kpi.path("completionRate").asDouble()),

            // ── Step 5: the overall score is a percentage ───────────────────────────────────
            () -> {
                double score = kpi.path("overallScore").asDouble();
                assertTrue(score >= 0.0 && score <= 100.0,
                    "overallScore must be inside [0, 100], was " + score);
            },
            () -> assertFalse(kpi.path("performanceLevel").asText().isEmpty(),
                "The scorecard must carry a performance band for display")
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // KPI-003 — an Admin assigns a KPI target to a technician
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * <p><b>Deviations from the row as written.</b></p>
     * <ol>
     *   <li><b>201 vs 200.</b> {@code KpiController.assignTarget} answers
     *       {@code ResponseEntity.ok(...)}. The row expects 201. Every other create in this API
     *       ({@code /api/jobs}, {@code /api/faults}, {@code /api/vehicles}) does return 201, so
     *       this is an inconsistency — but a cosmetic one, and the same treatment the 200-on-create
     *       of {@code POST /api/inventory/material-request} was given on 05_RESOURCE_MGMT: the
     *       assertion accepts either 200 or 201 and the deviation is documented, not failed on.</li>
     *   <li><b>{@code status == 'ACTIVE'}.</b> There is no {@code ACTIVE} constant in this domain.
     *       {@code KpiDTO} defines exactly four target statuses — {@code ACHIEVED},
     *       {@code ON_TRACK}, {@code AT_RISK}, {@code BEHIND} — and {@code assignTarget} explicitly
     *       stores {@code STATUS_ON_TRACK} on the new row. "Live/active" in the entity's own terms
     *       is {@code is_active = 1}, which is asserted separately. The row's status assertion is
     *       therefore read as "a newly assigned target must not be reported as failing", i.e.
     *       {@code ON_TRACK} — the value the service itself just wrote.</li>
     * </ol>
     *
     * <p><b>Two requests are sent, not one.</b> The first is the exact body the Admin portal's
     * Assign Target modal posts ({@code KpiPage.js}, including its {@code isGroupTarget} flag); the
     * second is the row's own body, which omits that flag. They fail in two different places, and
     * separating them keeps both visible instead of the first masking the second.</p>
     */
    @Test
    void adminAssignsTarget_returns201Active() throws Exception {
        // ── Arrange ──────────────────────────────────────────────────────────────────────────
        User admin = newUser(User.Role.ADMIN,      "Admin Amelia");
        User tech  = newUser(User.Role.TECHNICIAN, "Tech Kasun");
        flushAndClear();

        LocalDate dueDate = LocalDate.now().plusDays(20);

        // The row's body, plus the three fields AssignTargetRequest marks mandatory (unit,
        // category, dueDate) which the row leaves out.
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("technicianId", tech.getId());
        request.put("title",        "Monthly Completion Rate");
        request.put("description",  "Complete 90% of assigned jobs this month");
        request.put("targetValue",  90.0);
        request.put("unit",         "%");
        request.put("period",       "MONTHLY");
        request.put("category",     "JOBS");
        request.put("dueDate",      dueDate.toString());

        // Exactly what frontend-admin/src/pages/KPI/KpiPage.js sends.
        Map<String, Object> portalRequest = new LinkedHashMap<>(request);
        portalRequest.put("isGroupTarget", false);

        // ── Act: step 1 ──────────────────────────────────────────────────────────────────────
        Result portalRes = assignTarget(admin.getId(), portalRequest);
        int portalStatus  = portalRes.status;
        String portalBody = portalRes.body;

        Result res  = assignTarget(admin.getId(), request);
        int status  = res.status;
        String body = res.body;

        // ── Step 2 ───────────────────────────────────────────────────────────────────────────
        assertAll("POST /api/kpi/targets/assign creates the target",

            () -> assertTrue(status == 200 || status == 201,
                "Assigning a KPI target must succeed (the row expects 201; the controller returns "
                    + "ResponseEntity.ok, so either is accepted here). "
                    + "KpiCalculationService.assignTarget builds a KpiTarget that leaves "
                    + "min_jobs_per_day, target_customer_rating, target_month, "
                    + "target_sla_compliance and target_year null, and the KpiTarget entity does "
                    + "not map kpi_targets.period_year at all - yet all six of those columns are "
                    + "NOT NULL with no default in slt_fieldops_db, and the server runs under "
                    + "STRICT_TRANS_TABLES. The INSERT therefore cannot succeed for any payload, "
                    + "so no KPI target can be assigned to anyone through the API at all. "
                    + "Status was " + status + ", body: " + body),

            () -> assertTrue(portalStatus == 200 || portalStatus == 201,
                "The exact body the Admin portal's Assign Target modal posts must be accepted. "
                    + "KpiDTO.AssignTargetRequest declares the flag as `boolean isGroupTarget`, "
                    + "which Lombok exposes to Jackson as the property `groupTarget`, so the "
                    + "portal's `isGroupTarget` key is an unknown field. application.yml sets "
                    + "spring.jackson.deserialization.fail-on-unknown-properties=false, but "
                    + "AppConfig declares a @Primary ObjectMapper built with `new ObjectMapper()`, "
                    + "which ignores every spring.jackson.* property - so the setting has no "
                    + "effect and the request is rejected before it reaches the service. "
                    + "Status was " + portalStatus + ", body: " + portalBody)
        );

        // Everything below is only reachable once the target can actually be created; it states
        // what the row expects of a successfully assigned target.
        JsonNode dto = json.readTree(body);
        long targetId = dto.path("id").asLong();
        assertTrue(targetId > 0, "The created target must come back with an id, body: " + body);

        flushAndClear();
        KpiTarget saved = targetRepo.findById(targetId).orElseThrow(
            () -> new AssertionError("The assigned target must be persisted, body: " + body));

        assertAll("the target is created against the technician, by this admin, and is live",

            () -> assertEquals(tech.getId().longValue(), dto.path("technicianId").asLong(),
                "The target must belong to the technician it was assigned to"),
            () -> assertEquals("Monthly Completion Rate", dto.path("title").asText(),
                "The target title must round-trip"),
            () -> assertEquals(90.0, dto.path("targetValue").asDouble(), 0.001,
                "The target value must round-trip"),
            () -> assertEquals(0.0, dto.path("currentValue").asDouble(), 0.001,
                "A brand-new target starts at zero progress"),

            // ── Step 3: assigned by this admin (checked on the row — the DTO exposes only a
            //            display name, never assignedById) ──────────────────────────────────
            () -> assertNotNull(saved.getAssignedBy(),
                "kpi_targets.assigned_by_id must record who set the target"),
            () -> assertEquals(admin.getId(), saved.getAssignedBy().getId(),
                "The target must be attributed to the admin that assigned it"),
            () -> assertEquals(admin.getFullName(), dto.path("assignedBy").asText(),
                "The response must name the assigning admin for display"),
            () -> assertEquals(tech.getId(), saved.getUser().getId(),
                "kpi_targets.user_id must be the technician"),

            // ── Step 4: the target is live ("ACTIVE" in the entity's own vocabulary) ───────
            () -> assertEquals(Boolean.TRUE, saved.getIsActive(),
                "A newly assigned target must be active"),
            () -> assertEquals("ON_TRACK", saved.getStatus(),
                "assignTarget writes KpiDTO.STATUS_ON_TRACK onto the new row"),

            // ── Step 4 continued: and the API must say so too ──────────────────────────────
            () -> assertEquals("ON_TRACK", dto.path("status").asText(),
                "A target assigned today, with a due date " + dueDate + " and zero progress so "
                    + "far, must not be reported to the admin and the technician as already "
                    + "failing. KpiCalculationService.assignTarget stores status=ON_TRACK, but the "
                    + "read path never uses that column: mapTargetToDTO recomputes the status from "
                    + "progress alone via calculateTargetStatus(progress, dueDate), which returns "
                    + "BEHIND for anything under 50% however much time is left. Status was: "
                    + dto.path("status").asText())
        );
    }

    /** The HTTP status and body of one assign-target attempt. */
    private static final class Result {
        private final int status;
        private final String body;
        private Result(int status, String body) { this.status = status; this.body = body; }
    }

    /**
     * Posts an assign-target body. A failed INSERT can surface as a
     * {@code DataIntegrityViolationException} thrown out of the dispatcher rather than as a
     * response, so it is caught and reported as a synthetic 500 whose body carries the root cause
     * — otherwise the test would error out before it could explain itself.
     */
    private Result assignTarget(Long adminId, Map<String, Object> body) throws Exception {
        try {
            MvcResult res = mvc.perform(post("/api/kpi/targets/assign")
                    .header("Authorization", bearer(adminId, "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
                .andReturn();
            return new Result(res.getResponse().getStatus(),
                res.getResponse().getContentAsString());
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return new Result(500, root.getClass().getName() + ": " + root.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // KPI-005 — the leaderboard is ordered by overall score, descending, and ranked
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void leaderboard_sortedByScoreDesc() throws Exception {
        // ── Arrange: 5 technicians on 5 distinct completion rates (100/80/60/40/20%) ─────────
        User admin    = newUser(User.Role.ADMIN,     "Admin Amelia");
        User teamLead = newUser(User.Role.TEAM_LEAD, "TL Tharindu");
        User customer = newUser(User.Role.CLIENT,    "Client Chathura");
        Fault fault   = newFault(customer);

        List<User> techs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User t = newUser(User.Role.TECHNICIAN, "Leaderboard Tech " + (i + 1));
            newJobsFor(t, teamLead, customer, fault, 5, 5 - i);   // 5/5, 4/5, 3/5, 2/5, 1/5
            techs.add(t);
        }
        flushAndClear();

        // ── Act: step 1 ──────────────────────────────────────────────────────────────────────
        MvcResult res = mvc.perform(get("/api/kpi/leaderboard?period=MONTHLY")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        String body = res.getResponse().getContentAsString();

        // ── Step 2 ───────────────────────────────────────────────────────────────────────────
        assertEquals(200, res.getResponse().getStatus(),
            "The leaderboard must return 200. Body: " + body);

        JsonNode board = json.readTree(body);
        assertTrue(board.isArray(), "The leaderboard must be a JSON array, body: " + body);

        // ── Step 3: every seeded technician is on the board ─────────────────────────────────
        // The board covers every TECHNICIAN/TEAM_LEAD in the shared database, not only the five
        // seeded here, so their presence and relative order is what is asserted rather than a
        // fixed array length.
        Map<Long, Integer> positionOf = new LinkedHashMap<>();
        List<Double> scores = new ArrayList<>();
        List<Integer> ranks = new ArrayList<>();
        for (int i = 0; i < board.size(); i++) {
            JsonNode entry = board.get(i);
            positionOf.put(entry.path("technicianId").asLong(), i);
            scores.add(entry.path("overallScore").asDouble());
            ranks.add(entry.path("rank").asInt());
        }

        assertAll("the leaderboard is ordered by score and ranked from 1",

            () -> {
                for (User t : techs) {
                    assertTrue(positionOf.containsKey(t.getId()),
                        "Technician " + t.getFullName() + " (id " + t.getId()
                            + ") must appear on the leaderboard");
                }
            },

            // ── Step 4: sorted by overallScore descending ───────────────────────────────────
            () -> {
                for (int i = 0; i < scores.size() - 1; i++) {
                    assertTrue(scores.get(i) >= scores.get(i + 1),
                        "The leaderboard must be sorted by overallScore descending, but entry "
                            + i + " scored " + scores.get(i) + " and entry " + (i + 1)
                            + " scored " + scores.get(i + 1));
                }
            },

            // The five seeded technicians have strictly decreasing completion rates, so their
            // relative order on the board must follow.
            () -> {
                for (int i = 0; i < techs.size() - 1; i++) {
                    int higher = positionOf.get(techs.get(i).getId());
                    int lower  = positionOf.get(techs.get(i + 1).getId());
                    assertTrue(higher < lower,
                        techs.get(i).getFullName() + " completed more of its jobs than "
                            + techs.get(i + 1).getFullName()
                            + " and must be listed above it, positions were " + higher + " and "
                            + lower);
                }
            },

            // ── Step 5: rank is 1, 2, 3, ... in board order ─────────────────────────────────
            () -> {
                for (int i = 0; i < ranks.size(); i++) {
                    assertEquals(i + 1, ranks.get(i),
                        "Entry " + i + " must carry rank " + (i + 1) + ", was " + ranks.get(i));
                }
            }
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // KPI-011 — period filter: DAILY, WEEKLY, MONTHLY are accepted, anything else is rejected
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void periodFilter_daily_weekly_monthly() throws Exception {
        User admin = newUser(User.Role.ADMIN, "Admin Amelia");
        flushAndClear();

        MvcResult daily   = leaderboard(admin.getId(), "DAILY");
        MvcResult weekly  = leaderboard(admin.getId(), "WEEKLY");
        MvcResult monthly = leaderboard(admin.getId(), "MONTHLY");
        MvcResult invalid = leaderboard(admin.getId(), "INVALID");

        assertAll("only the three documented periods are accepted",

            // ── Steps 1-3: the three valid periods ──────────────────────────────────────────
            () -> assertEquals(200, daily.getResponse().getStatus(),
                "period=DAILY must return 200. Body: "
                    + daily.getResponse().getContentAsString()),
            () -> assertEquals(200, weekly.getResponse().getStatus(),
                "period=WEEKLY must return 200. Body: "
                    + weekly.getResponse().getContentAsString()),
            () -> assertEquals(200, monthly.getResponse().getStatus(),
                "period=MONTHLY must return 200. Body: "
                    + monthly.getResponse().getContentAsString()),

            // ── Step 4: an unrecognised period is a client error ────────────────────────────
            () -> assertEquals(400, invalid.getResponse().getStatus(),
                "period=INVALID must be rejected with 400. KpiCalculationService.getDateRange "
                    + "switches on the period string and its default branch silently falls back "
                    + "to the MONTHLY window, so an unrecognised (or misspelt) period returns a "
                    + "full 200 with a month of data the "
                    + "caller never asked for and no indication that the filter was ignored. "
                    + "Nothing validates the parameter against KpiDTO's PERIOD_DAILY / "
                    + "PERIOD_WEEKLY / PERIOD_MONTHLY constants. Status was: "
                    + invalid.getResponse().getStatus())
        );
    }

    private MvcResult leaderboard(Long adminId, String period) throws Exception {
        return mvc.perform(get("/api/kpi/leaderboard?period=" + period)
                .header("Authorization", bearer(adminId, "ADMIN")))
            .andReturn();
    }
}
