package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.DaySession;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.DaySessionRepository;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA_Compliance_Consolidated_Report.md — {@code JobService.logJobRoutingHistory} and
 * {@code FaultAssignmentService.saveHistoryEvent} both build {@code FaultHistory} rows without
 * calling {@code .ipAddress(...)}, unlike every other {@code FaultHistory}/audit-row builder in the
 * codebase ({@code FaultService.attachCircuit}/{@code attachCause}/{@code cancelFault},
 * {@code PaymentService}, {@code UserService}, {@code StockManagementService}, etc.), which all read
 * {@code RequestContext.getClientIp()} — populated once per request by {@code SecurityConfig}'s
 * {@code jwtAuthFilter} from {@code X-Forwarded-For} (falling back to {@code request.getRemoteAddr()}).
 * SRS 7.1.4 requires "actor ID, role, timestamp, IP address, entity type, previous/new values" on
 * every logged audit event — every event routed through these two methods was silently missing the
 * one required field neither builder ever set.
 *
 * <p><b>Real filter chain, not a direct service call.</b> {@code RequestContext} is a request-scoped
 * {@code ThreadLocal} populated by the real servlet filter — a plain {@code @Transactional} test that
 * calls the service method directly (no HTTP request in flight) would never exercise that wiring and
 * could pass even if the filter itself were broken. {@code @AutoConfigureMockMvc} through the real
 * JWT filter chain is this module's established convention for exactly this reason ({@code
 * JobServiceEodRoutingTest}, {@code FaultWorkGroupAssignmentIntegrationTest}).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditTrailIpAddressTest {

    @Autowired private MockMvc                mvc;
    @Autowired private JwtTokenProvider       jwt;
    @Autowired private UserRepository         userRepo;
    @Autowired private FaultRepository        faultRepo;
    @Autowired private JobRepository          jobRepo;
    @Autowired private DaySessionRepository   sessionRepo;
    @Autowired private WorkGroupRepository    workGroupRepo;
    @Autowired private OpmcRepository         opmcRepo;
    @Autowired private FaultHistoryRepository historyRepo;
    @Autowired private ObjectMapper           json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "atip" + userId, role, opmcId);
    }

    private User newUser(User.Role role, Long opmcId, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("atip" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        u.setIsActive(true);
        return userRepo.save(u);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private List<FaultHistory> historyFor(Long faultId) {
        return historyRepo.findByFaultId(faultId);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // FaultAssignmentService.saveHistoryEvent — via POST /api/faults/{id}/assign
    // ═══════════════════════════════════════════════════════════════════════════════════════

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("ATIP OPMC " + n);
        o.setCode("ATIP" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private WorkGroup newWorkGroup(Opmc opmc) {
        long n = uniq();
        WorkGroup wg = new WorkGroup();
        wg.setName("ATIP Work Group " + n);
        wg.setOpmc(opmc);
        wg.setIsActive(true);
        return workGroupRepo.save(wg);
    }

    private Long reportFaultAs(Long opmcId) throws Exception {
        User client = newUser(User.Role.CLIENT, opmcId, "ATIP Client");
        String body = "{"
            + "\"category\":\"INTERNET\","
            + "\"description\":\"Audit-trail IP fixture\","
            + "\"locationAddress\":\"Test address\","
            + "\"latitude\":6.9271,"
            + "\"longitude\":79.8612,"
            + "\"opmcId\":" + opmcId + ","
            + "\"priority\":\"MEDIUM\"}";
        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT", opmcId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus(),
            "Fault setup POST failed. Body: " + res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void assignFault_faultHistory_capturesCallerIpFromForwardedForHeader() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wg = newWorkGroup(opmc);
        User admin = newUser(User.Role.ADMIN, opmc.getId(), "ATIP Admin");
        Long faultId = reportFaultAs(opmc.getId());
        flushAndClear();

        String body = "{\"workGroupId\":" + wg.getId() + ",\"notifyTeamLead\":false,"
            + "\"notifyCustomer\":false}";
        MvcResult res = mvc.perform(post("/api/faults/{id}/assign", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .header("X-Forwarded-For", "203.0.113.7")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
            "Body: " + res.getResponse().getContentAsString());
        flushAndClear();

        FaultHistory row = historyFor(faultId).stream()
            .filter(h -> "FAULT_ASSIGNED_TO_WORK_GROUP".equals(h.getEventType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no FAULT_ASSIGNED_TO_WORK_GROUP row to inspect"));

        assertEquals("203.0.113.7", row.getIpAddress(),
            "FaultAssignmentService.saveHistoryEvent must now record the caller's real IP "
                + "(from X-Forwarded-For, resolved by SecurityConfig's jwtAuthFilter into "
                + "RequestContext), matching every other audit-row builder in the codebase. Was "
                + row.getIpAddress());
    }

    @Test
    void assignFault_faultHistory_fallsBackToRemoteAddr_whenNoForwardedForHeader() throws Exception {
        Opmc opmc = newOpmc();
        WorkGroup wg = newWorkGroup(opmc);
        User admin = newUser(User.Role.ADMIN, opmc.getId(), "ATIP Admin NoXff");
        Long faultId = reportFaultAs(opmc.getId());
        flushAndClear();

        String body = "{\"workGroupId\":" + wg.getId() + ",\"notifyTeamLead\":false,"
            + "\"notifyCustomer\":false}";
        MvcResult res = mvc.perform(post("/api/faults/{id}/assign", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        assertEquals(200, res.getResponse().getStatus());
        flushAndClear();

        FaultHistory row = historyFor(faultId).stream()
            .filter(h -> "FAULT_ASSIGNED_TO_WORK_GROUP".equals(h.getEventType()))
            .findFirst()
            .orElseThrow();

        assertNotNull(row.getIpAddress(),
            "With no X-Forwarded-For header, SecurityConfig.resolveClientIp falls back to "
                + "request.getRemoteAddr() — the row must still carry SOME IP, not null, exactly "
                + "as every other audit builder in the codebase already does for this same case.");
        assertFalse(row.getIpAddress().isBlank());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // JobService.logJobRoutingHistory — via POST /api/jobs/eod (CARRY_OVER routing option)
    // ═══════════════════════════════════════════════════════════════════════════════════════

    private DaySession newActiveSession(User lead) {
        DaySession s = new DaySession();
        s.setTeamLeadId(lead.getId());
        s.setSessionDate(LocalDate.now());
        s.setStatus(DaySession.SessionStatus.ACTIVE);
        s.setBodTime(LocalDateTime.now().minusHours(8));
        s.setBodOdometer(15200);
        return sessionRepo.save(s);
    }

    private Fault newAssignedFault(User client, User lead, Long opmcId) {
        Fault f = new Fault();
        f.setFaultNumber("FLT-ATIP-" + uniq());
        f.setCustomerId(client.getId());
        f.setCustomerName(client.getFullName());
        f.setCategory(Fault.FaultCategory.INTERNET);
        f.setDescription("EOD audit-trail IP fixture");
        f.setLocationAddress("No. 5 Main Street, Colombo 03");
        f.setLatitude(6.9271);
        f.setLongitude(79.8612);
        f.setOpmcId(opmcId);
        f.setPriority(Fault.FaultPriority.HIGH);
        f.setStatus(Fault.FaultStatus.IN_PROGRESS);
        f.setAssignedTeamLeadId(lead.getId());
        f.setAssignedTeamLeadName(lead.getFullName());
        f.setAssignedAt(LocalDateTime.now().minusHours(4));
        return faultRepo.save(f);
    }

    private Job newOpenJob(DaySession session, User lead, User tech, Fault fault, User client) {
        Job job = new Job();
        job.setJobNumber("JOB-ATIP-" + uniq());
        job.setFaultId(fault.getId());
        job.setFaultNumber(fault.getFaultNumber());
        job.setSessionId(session.getId());
        job.setTeamLeadId(lead.getId());
        job.setTeamLeadName(lead.getFullName());
        job.setTechnicianId(tech.getId());
        job.setTechnicianName(tech.getFullName());
        job.setCustomerId(client.getId());
        job.setCustomerName(client.getFullName());
        job.setStatus(Job.JobStatus.IN_PROGRESS);
        job.setPriority(Job.JobPriority.HIGH);
        job.setScheduledDate(LocalDate.now());
        return jobRepo.save(job);
    }

    @Test
    void eodCarryOver_faultHistory_capturesCallerIpFromForwardedForHeader() throws Exception {
        User client = newUser(User.Role.CLIENT,     REAL_BRANCH_ID, "ATIP EOD Client");
        User lead   = newUser(User.Role.TEAM_LEAD,  REAL_BRANCH_ID, "ATIP EOD Lead");
        User tech   = newUser(User.Role.TECHNICIAN, REAL_BRANCH_ID, "ATIP EOD Tech");
        DaySession session = newActiveSession(lead);
        Fault fault = newAssignedFault(client, lead, REAL_BRANCH_ID);
        newOpenJob(session, lead, tech, fault, client);
        flushAndClear();

        MvcResult res = mvc.perform(post("/api/jobs/eod")
                .header("Authorization", bearer(lead.getId(), "TEAM_LEAD", REAL_BRANCH_ID))
                .header("X-Forwarded-For", "198.51.100.23")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"odometerEnd\":15350,\"notes\":\"Finishing tomorrow\","
                    + "\"routingOption\":\"CARRY_OVER\"}"))
            .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
            "Body: " + res.getResponse().getContentAsString());
        flushAndClear();

        FaultHistory row = historyFor(fault.getId()).stream()
            .filter(h -> "EOD_CARRY_OVER".equals(h.getEventType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no EOD_CARRY_OVER row to inspect"));

        assertEquals("198.51.100.23", row.getIpAddress(),
            "JobService.logJobRoutingHistory must now record the caller's real IP, matching "
                + "every other audit-row builder in the codebase. Was " + row.getIpAddress());
    }

    @Test
    void eodCarryOver_faultHistory_fallsBackToRemoteAddr_whenNoForwardedForHeader() throws Exception {
        User client = newUser(User.Role.CLIENT,     REAL_BRANCH_ID, "ATIP EOD Client NoXff");
        User lead   = newUser(User.Role.TEAM_LEAD,  REAL_BRANCH_ID, "ATIP EOD Lead NoXff");
        User tech   = newUser(User.Role.TECHNICIAN, REAL_BRANCH_ID, "ATIP EOD Tech NoXff");
        DaySession session = newActiveSession(lead);
        Fault fault = newAssignedFault(client, lead, REAL_BRANCH_ID);
        newOpenJob(session, lead, tech, fault, client);
        flushAndClear();

        MvcResult res = mvc.perform(post("/api/jobs/eod")
                .header("Authorization", bearer(lead.getId(), "TEAM_LEAD", REAL_BRANCH_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"odometerEnd\":15350,\"notes\":\"Finishing tomorrow\","
                    + "\"routingOption\":\"CARRY_OVER\"}"))
            .andReturn();
        assertEquals(200, res.getResponse().getStatus());
        flushAndClear();

        FaultHistory row = historyFor(fault.getId()).stream()
            .filter(h -> "EOD_CARRY_OVER".equals(h.getEventType()))
            .findFirst()
            .orElseThrow();

        assertNotNull(row.getIpAddress(),
            "With no X-Forwarded-For header, the row must still carry SOME IP (the servlet "
                + "container's remote addr), not null.");
        assertFalse(row.getIpAddress().isBlank());
    }
}
