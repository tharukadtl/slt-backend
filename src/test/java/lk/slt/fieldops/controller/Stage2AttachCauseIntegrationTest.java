package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.AttachCauseRequest;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.CauseOfFault;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CauseOfFaultRepository;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Stage 2 (QA_Compliance_Consolidated_Report.md) — structured causeId resolution, Admin/Team-Lead
 * post-hoc, via PATCH /api/faults/{id}/cause and the read-only TypeOfFault -> CauseCategory ->
 * CauseOfFault hierarchy endpoints. Live, real-backend proof, same standard as
 * {@link H1cAttachCircuitIntegrationTest} (this class's direct template): real JWT filter chain,
 * real FaultService, real MySQL. Each test is @Transactional and rolls back.
 *
 * <p>Uses two real {@code CauseOfFault} rows looked up live via the repository rather than
 * hardcoded ids — the 869-row import is real master data (docs/master-data/CAUSEOFFAULT.csv), not
 * a fixture this test owns, so its ids shouldn't be guessed or pinned.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Stage2AttachCauseIntegrationTest {

    @Autowired private MockMvc                mvc;
    @Autowired private JwtTokenProvider        jwt;
    @Autowired private FaultRepository         faultRepo;
    @Autowired private FaultHistoryRepository  historyRepo;
    @Autowired private UserRepository          userRepo;
    @Autowired private CauseOfFaultRepository  causeOfFaultRepo;
    @Autowired private ObjectMapper            json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_OPMC_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "s2" + userId, role, REAL_OPMC_ID);
    }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("s2" + n);
        u.setPasswordHash("x");
        u.setFirstName("Stage2");
        u.setLastName("Test");
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_OPMC_ID);
        return userRepo.save(u);
    }

    private Long reportFault(User client) throws Exception {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");
        req.setDescription("Stage 2 attach-cause test fault");
        req.setLocationAddress("Test address");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(REAL_OPMC_ID);
        req.setPriority("MEDIUM");

        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus());
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    /** Two distinct, real CauseOfFault rows from the master-data import, looked up live. */
    private CauseOfFault[] realCauses() {
        List<CauseOfFault> all = causeOfFaultRepo.findAll();
        assertTrue(all.size() >= 2, "The real cause_of_fault import must have at least 2 rows to test with");
        return new CauseOfFault[] { all.get(0), all.get(1) };
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // PATCH /api/faults/{id}/cause
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void attachCause_asAdmin_persistsCauseIdAndCodeAndWritesHistory() throws Exception {
        User client = newUser(User.Role.CLIENT, "Stage2 Client");
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin");
        Long faultId = reportFault(client);
        CauseOfFault cause = realCauses()[0];
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());

        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Attach must succeed. Body: " + body);
        JsonNode dto = json.readTree(body);
        assertEquals(cause.getId(), dto.get("causeId").asLong());
        assertEquals(cause.getCauseCode(), dto.get("causeCode").asText());

        em.flush(); em.clear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertEquals(cause.getId(), persisted.getCauseId());
        assertEquals(cause.getCauseCode(), persisted.getCauseCode());

        List<FaultHistory> history = historyRepo.findAll().stream()
            .filter(h -> h.getFault() != null && faultId.equals(h.getFault().getId()))
            .filter(h -> "CAUSE_CLASSIFIED".equals(h.getEventType()))
            .toList();
        assertEquals(1, history.size(), "Exactly one CAUSE_CLASSIFIED history row must be written");
        assertEquals(cause.getCauseCode(), history.get(0).getNewValue());
        assertNull(history.get(0).getPreviousValue(), "No prior cause was attached");
    }

    @Test
    void attachCause_asTeamLead_succeeds() throws Exception {
        User client   = newUser(User.Role.CLIENT, "Stage2 Client 2");
        User teamLead = newUser(User.Role.TEAM_LEAD, "Stage2 Team Lead");
        Long faultId  = reportFault(client);
        CauseOfFault cause = realCauses()[0];
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());

        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "TEAM_LEAD must be allowed to attach a Cause. Body: " + res.getResponse().getContentAsString());
    }

    @Test
    void attachCause_asTechnician_forbidden() throws Exception {
        User client     = newUser(User.Role.CLIENT, "Stage2 Client 3");
        User technician = newUser(User.Role.TECHNICIAN, "Stage2 Tech");
        Long faultId    = reportFault(client);
        CauseOfFault cause = realCauses()[0];
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());

        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "TECHNICIAN must not be able to attach a Cause to a Fault -- classification is "
                + "Admin/Team-Lead post-hoc review, per the Stage 2 design.");
    }

    @Test
    void attachCause_nonexistentCause_returns404NotSilentSuccess() throws Exception {
        User client = newUser(User.Role.CLIENT, "Stage2 Client 4");
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin 2");
        Long faultId = reportFault(client);
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(999_999_999L);

        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertNotEquals(200, res.getResponse().getStatus(),
            "A nonexistent causeId must not silently succeed. Body: " + res.getResponse().getContentAsString());

        em.flush(); em.clear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertNull(persisted.getCauseId(), "causeId must remain unset after a rejected attach");
    }

    @Test
    void attachCause_changingAnAlreadyAttachedCause_recordsPreviousValue() throws Exception {
        User client = newUser(User.Role.CLIENT, "Stage2 Client 5");
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin 3");
        Long faultId = reportFault(client);
        CauseOfFault[] causes = realCauses();
        em.flush(); em.clear();

        AttachCauseRequest req1 = new AttachCauseRequest();
        req1.setCauseId(causes[0].getId());
        mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req1)))
            .andReturn();
        em.flush(); em.clear();

        AttachCauseRequest req2 = new AttachCauseRequest();
        req2.setCauseId(causes[1].getId());
        MvcResult res2 = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req2)))
            .andReturn();
        assertEquals(200, res2.getResponse().getStatus());

        em.flush(); em.clear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertEquals(causes[1].getId(), persisted.getCauseId());
        assertEquals(causes[1].getCauseCode(), persisted.getCauseCode());

        List<FaultHistory> history = historyRepo.findAll().stream()
            .filter(h -> h.getFault() != null && faultId.equals(h.getFault().getId()))
            .filter(h -> "CAUSE_CLASSIFIED".equals(h.getEventType()))
            .toList();
        assertEquals(2, history.size(), "Both classification events must be recorded");
        FaultHistory second = history.stream()
            .filter(h -> causes[1].getCauseCode().equals(h.getNewValue())).findFirst().orElseThrow();
        assertEquals(causes[0].getCauseCode(), second.getPreviousValue(),
            "Second classification must record the prior cause code");
    }

    @Test
    void attachCause_onCompletedFault_allowed() throws Exception {
        // Deliberately the OPPOSITE assertion from H1cAttachCircuitIntegrationTest's
        // attachCircuit_onCompletedFault_rejected -- cause classification's entire premise is a
        // reviewer reading a COMPLETED fault's real diagnostic input after the fact (Stage 2 design
        // investigation). Blocking COMPLETED here would forbid the primary intended use case.
        User client = newUser(User.Role.CLIENT, "Stage2 Client 6");
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin 4");
        Long faultId = reportFault(client);
        CauseOfFault cause = realCauses()[0];
        em.flush();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setStatus(Fault.FaultStatus.COMPLETED);
        faultRepo.save(fault);
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());
        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "A COMPLETED fault must accept a Cause classification -- that is the primary intended "
                + "use case, unlike Circuit attachment. Body: " + res.getResponse().getContentAsString());
    }

    @Test
    void attachCause_onCancelledFault_rejected() throws Exception {
        User client = newUser(User.Role.CLIENT, "Stage2 Client 7");
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin 5");
        Long faultId = reportFault(client);
        CauseOfFault cause = realCauses()[0];
        em.flush();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setStatus(Fault.FaultStatus.CANCELLED);
        faultRepo.save(fault);
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());
        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertNotEquals(200, res.getResponse().getStatus(),
            "A CANCELLED fault was never diagnosed -- must reject a Cause classification, not "
                + "silently accept it");
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Cause hierarchy GET endpoints — TypeOfFault -> CauseCategory -> CauseOfFault
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void getAllTypesOfFault_returnsAllThirteenRealRows() throws Exception {
        User admin = newUser(User.Role.ADMIN, "Stage2 Hierarchy Admin");

        MvcResult res = mvc.perform(get("/api/type-of-faults")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        JsonNode list = json.readTree(body);
        assertEquals(13, list.size(),
            "The real type_of_fault import has exactly 13 rows. Body: " + body);
    }

    @Test
    void getCauseCategories_filteredByTypeOfFaultId_returnsOnlyThatType() throws Exception {
        User admin = newUser(User.Role.ADMIN, "Stage2 Hierarchy Admin 2");

        MvcResult typesRes = mvc.perform(get("/api/type-of-faults")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        JsonNode types = json.readTree(typesRes.getResponse().getContentAsString());
        long typeId = types.get(0).path("id").asLong();

        MvcResult res = mvc.perform(get("/api/cause-categories")
                .param("typeOfFaultId", String.valueOf(typeId))
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        JsonNode categories = json.readTree(body);
        assertTrue(categories.isArray(), "Body: " + body);
        for (JsonNode c : categories) {
            assertEquals(typeId, c.path("typeOfFaultId").asLong(),
                "Every returned CauseCategory must belong to the requested TypeOfFault. Body: " + body);
        }

        // Unfiltered call must return the full 85 -- confirms the filter genuinely narrows, not
        // that the endpoint just always returns everything.
        MvcResult allRes = mvc.perform(get("/api/cause-categories")
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        JsonNode all = json.readTree(allRes.getResponse().getContentAsString());
        assertEquals(85, all.size(), "Unfiltered must return all 85 real rows");
        assertTrue(categories.size() < all.size(),
            "The typeOfFaultId-filtered list must be smaller than the unfiltered list");
    }

    @Test
    void getCausesOfFault_filteredByCauseCategoryId_returnsOnlyThatCategory() throws Exception {
        User admin = newUser(User.Role.ADMIN, "Stage2 Hierarchy Admin 3");
        CauseOfFault sample = realCauses()[0];

        MvcResult res = mvc.perform(get("/api/cause-of-faults")
                .param("causeCategoryId", String.valueOf(sample.getCauseCategoryId()))
                .header("Authorization", bearer(admin.getId(), "ADMIN")))
            .andReturn();
        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        JsonNode causes = json.readTree(body);
        assertTrue(causes.isArray() && causes.size() > 0, "Body: " + body);
        boolean foundSample = false;
        for (JsonNode c : causes) {
            assertEquals(sample.getCauseCategoryId(), c.path("causeCategoryId").asLong(),
                "Every returned CauseOfFault must belong to the requested CauseCategory. Body: " + body);
            if (sample.getId().equals(c.path("id").asLong())) foundSample = true;
        }
        assertTrue(foundSample, "The known real cause used as the filter sample must appear in its own category's list");
    }

    @Test
    void causeHierarchy_technicianForbidden() throws Exception {
        User technician = newUser(User.Role.TECHNICIAN, "Stage2 Hierarchy Tech");

        MvcResult res = mvc.perform(get("/api/type-of-faults")
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN")))
            .andReturn();
        assertEquals(403, res.getResponse().getStatus(),
            "TECHNICIAN must not read the classification hierarchy -- classification is "
                + "Admin/Team-Lead post-hoc review only.");
    }
}
