package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.AttachCauseRequest;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.CauseCategory;
import lk.slt.fieldops.entity.CauseOfFault;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.FaultHistory;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.TypeOfFault;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.CauseCategoryRepository;
import lk.slt.fieldops.repository.CauseOfFaultRepository;
import lk.slt.fieldops.repository.FaultHistoryRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.TypeOfFaultRepository;
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
 * <p><b>2026-09-03, CI-portable-database fix.</b> Originally used {@code REAL_OPMC_ID = 1L} for
 * every caller, and looked up two real {@code CauseOfFault} rows live from the 869-row
 * {@code CAUSEOFFAULT.csv} import rather than hardcoding their ids — better than the OPMC/Circuit
 * pattern elsewhere, but still a real dependency on a real, one-time master-data import that only
 * ever ran against this suite's long-lived local dev database, and entirely incidental to what
 * every test here actually exercises (role gating, 404-on-nonexistent, history recording,
 * hierarchy-filter correctness) — none of it depends on the hierarchy being real, imported data
 * rather than fresh fixture rows. Fixed by creating a real, per-test {@code TypeOfFault}/
 * {@code CauseCategory}/{@code CauseOfFault} chain via {@code newTypeOfFault()}/
 * {@code newCauseCategory()}/{@code newCauseOfFault()} — same established self-contained-fixture
 * pattern as ~40 sibling files. The two hierarchy-size assertions that pinned the real import's
 * exact row counts (13 TypeOfFault rows, 85 CauseCategory rows) now assert against the fixture
 * rows this test itself created instead — same "returns everything, not a truncated/filtered
 * subset" behavior under test, without depending on any specific real count.
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
    @Autowired private OpmcRepository          opmcRepo;
    @Autowired private TypeOfFaultRepository   typeOfFaultRepo;
    @Autowired private CauseCategoryRepository causeCategoryRepo;
    @Autowired private CauseOfFaultRepository  causeOfFaultRepo;
    @Autowired private ObjectMapper            json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }
    /** Short (<=6 char) unique suffix — type_code/cause_category_code/cause_code are VARCHAR(10). */
    private long shortUniq() { return uniq() % 90_000L; }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "s2" + userId, role, opmcId);
    }

    /** A fresh, genuinely persisted OPMC — no test may assume any OPMC id pre-exists. */
    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("Stage2 Test OPMC " + n);
        o.setCode("S2O" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private TypeOfFault newTypeOfFault() {
        TypeOfFault t = new TypeOfFault();
        t.setTypeCode("T" + shortUniq());
        t.setDescription("Stage2 Test Type");
        return typeOfFaultRepo.save(t);
    }

    private CauseCategory newCauseCategory(Long typeOfFaultId) {
        CauseCategory c = new CauseCategory();
        c.setCauseCategoryCode("C" + shortUniq());
        c.setDescription("Stage2 Test Category");
        c.setTypeOfFaultId(typeOfFaultId);
        return causeCategoryRepo.save(c);
    }

    private CauseOfFault newCauseOfFault(Long causeCategoryId) {
        CauseOfFault c = new CauseOfFault();
        c.setCauseCode("F" + shortUniq());
        c.setDescription("Stage2 Test Cause");
        c.setCauseCategoryId(causeCategoryId);
        c.setAppliesCopper(true);
        c.setAppliesFtth(true);
        c.setAppliesLte(true);
        return causeOfFaultRepo.save(c);
    }

    /** The full TypeOfFault -> CauseCategory -> CauseOfFault chain, in one call. */
    private CauseOfFault newCauseOfFault() {
        return newCauseOfFault(newCauseCategory(newTypeOfFault().getId()).getId());
    }

    private User newUser(User.Role role, String fullName, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("s2" + n);
        u.setPasswordHash("x");
        u.setFirstName("Stage2");
        u.setLastName("Test");
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private Long reportFault(User client, Long opmcId) throws Exception {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");
        req.setDescription("Stage 2 attach-cause test fault");
        req.setLocationAddress("Test address");
        req.setLatitude(6.9271);
        req.setLongitude(79.8612);
        req.setOpmcId(opmcId);
        req.setPriority("MEDIUM");

        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT", opmcId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();
        assertEquals(201, res.getResponse().getStatus());
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // PATCH /api/faults/{id}/cause
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void attachCause_asAdmin_persistsCauseIdAndCodeAndWritesHistory() throws Exception {
        Opmc opmc = newOpmc();
        User client = newUser(User.Role.CLIENT, "Stage2 Client", opmc.getId());
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin", opmc.getId());
        Long faultId = reportFault(client, opmc.getId());
        CauseOfFault cause = newCauseOfFault();
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());

        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
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
        Opmc opmc = newOpmc();
        User client   = newUser(User.Role.CLIENT, "Stage2 Client 2", opmc.getId());
        User teamLead = newUser(User.Role.TEAM_LEAD, "Stage2 Team Lead", opmc.getId());
        Long faultId  = reportFault(client, opmc.getId());
        CauseOfFault cause = newCauseOfFault();
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());

        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(teamLead.getId(), "TEAM_LEAD", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "TEAM_LEAD must be allowed to attach a Cause. Body: " + res.getResponse().getContentAsString());
    }

    @Test
    void attachCause_asTechnician_forbidden() throws Exception {
        Opmc opmc = newOpmc();
        User client     = newUser(User.Role.CLIENT, "Stage2 Client 3", opmc.getId());
        User technician = newUser(User.Role.TECHNICIAN, "Stage2 Tech", opmc.getId());
        Long faultId    = reportFault(client, opmc.getId());
        CauseOfFault cause = newCauseOfFault();
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());

        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(403, res.getResponse().getStatus(),
            "TECHNICIAN must not be able to attach a Cause to a Fault -- classification is "
                + "Admin/Team-Lead post-hoc review, per the Stage 2 design.");
    }

    @Test
    void attachCause_nonexistentCause_returns404NotSilentSuccess() throws Exception {
        Opmc opmc = newOpmc();
        User client = newUser(User.Role.CLIENT, "Stage2 Client 4", opmc.getId());
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin 2", opmc.getId());
        Long faultId = reportFault(client, opmc.getId());
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(999_999_999L);

        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
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
        Opmc opmc = newOpmc();
        User client = newUser(User.Role.CLIENT, "Stage2 Client 5", opmc.getId());
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin 3", opmc.getId());
        Long faultId = reportFault(client, opmc.getId());
        CauseCategory category = newCauseCategory(newTypeOfFault().getId());
        CauseOfFault cause1 = newCauseOfFault(category.getId());
        CauseOfFault cause2 = newCauseOfFault(category.getId());
        em.flush(); em.clear();

        AttachCauseRequest req1 = new AttachCauseRequest();
        req1.setCauseId(cause1.getId());
        mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req1)))
            .andReturn();
        em.flush(); em.clear();

        AttachCauseRequest req2 = new AttachCauseRequest();
        req2.setCauseId(cause2.getId());
        MvcResult res2 = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req2)))
            .andReturn();
        assertEquals(200, res2.getResponse().getStatus());

        em.flush(); em.clear();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertEquals(cause2.getId(), persisted.getCauseId());
        assertEquals(cause2.getCauseCode(), persisted.getCauseCode());

        List<FaultHistory> history = historyRepo.findAll().stream()
            .filter(h -> h.getFault() != null && faultId.equals(h.getFault().getId()))
            .filter(h -> "CAUSE_CLASSIFIED".equals(h.getEventType()))
            .toList();
        assertEquals(2, history.size(), "Both classification events must be recorded");
        FaultHistory second = history.stream()
            .filter(h -> cause2.getCauseCode().equals(h.getNewValue())).findFirst().orElseThrow();
        assertEquals(cause1.getCauseCode(), second.getPreviousValue(),
            "Second classification must record the prior cause code");
    }

    @Test
    void attachCause_onCompletedFault_allowed() throws Exception {
        // Deliberately the OPPOSITE assertion from H1cAttachCircuitIntegrationTest's
        // attachCircuit_onCompletedFault_rejected -- cause classification's entire premise is a
        // reviewer reading a COMPLETED fault's real diagnostic input after the fact (Stage 2 design
        // investigation). Blocking COMPLETED here would forbid the primary intended use case.
        Opmc opmc = newOpmc();
        User client = newUser(User.Role.CLIENT, "Stage2 Client 6", opmc.getId());
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin 4", opmc.getId());
        Long faultId = reportFault(client, opmc.getId());
        CauseOfFault cause = newCauseOfFault();
        em.flush();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setStatus(Fault.FaultStatus.COMPLETED);
        faultRepo.save(fault);
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());
        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "A COMPLETED fault must accept a Cause classification -- that is the primary intended "
                + "use case, unlike Circuit attachment. Body: " + res.getResponse().getContentAsString());
    }

    @Test
    void attachCause_onCancelledFault_rejected() throws Exception {
        Opmc opmc = newOpmc();
        User client = newUser(User.Role.CLIENT, "Stage2 Client 7", opmc.getId());
        User admin  = newUser(User.Role.ADMIN,  "Stage2 Admin 5", opmc.getId());
        Long faultId = reportFault(client, opmc.getId());
        CauseOfFault cause = newCauseOfFault();
        em.flush();

        Fault fault = faultRepo.findById(faultId).orElseThrow();
        fault.setStatus(Fault.FaultStatus.CANCELLED);
        faultRepo.save(fault);
        em.flush(); em.clear();

        AttachCauseRequest req = new AttachCauseRequest();
        req.setCauseId(cause.getId());
        MvcResult res = mvc.perform(patch("/api/faults/{id}/cause", faultId)
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
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
    void getAllTypesOfFault_returnsAllRowsNotATruncatedSubset() throws Exception {
        Opmc opmc = newOpmc();
        User admin = newUser(User.Role.ADMIN, "Stage2 Hierarchy Admin", opmc.getId());
        newTypeOfFault();
        newTypeOfFault();
        newTypeOfFault();
        em.flush(); em.clear();

        MvcResult res = mvc.perform(get("/api/type-of-faults")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId())))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        JsonNode list = json.readTree(body);
        assertEquals(typeOfFaultRepo.count(), list.size(),
            "GET must return every TypeOfFault row, not a truncated/filtered subset. Body: " + body);
        assertTrue(list.size() >= 3, "Expected at least the 3 fixture rows this test created. Body: " + body);
    }

    @Test
    void getCauseCategories_filteredByTypeOfFaultId_returnsOnlyThatType() throws Exception {
        Opmc opmc = newOpmc();
        User admin = newUser(User.Role.ADMIN, "Stage2 Hierarchy Admin 2", opmc.getId());
        TypeOfFault typeA = newTypeOfFault();
        TypeOfFault typeB = newTypeOfFault();
        newCauseCategory(typeA.getId());
        newCauseCategory(typeA.getId());
        newCauseCategory(typeB.getId());
        em.flush(); em.clear();

        long typeId = typeA.getId();

        MvcResult res = mvc.perform(get("/api/cause-categories")
                .param("typeOfFaultId", String.valueOf(typeId))
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId())))
            .andReturn();
        String body = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + body);
        JsonNode categories = json.readTree(body);
        assertTrue(categories.isArray(), "Body: " + body);
        assertEquals(2, categories.size(), "Exactly the 2 fixture categories under typeA. Body: " + body);
        for (JsonNode c : categories) {
            assertEquals(typeId, c.path("typeOfFaultId").asLong(),
                "Every returned CauseCategory must belong to the requested TypeOfFault. Body: " + body);
        }

        // Unfiltered call must return every row -- confirms the filter genuinely narrows, not
        // that the endpoint just always returns everything.
        MvcResult allRes = mvc.perform(get("/api/cause-categories")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId())))
            .andReturn();
        JsonNode all = json.readTree(allRes.getResponse().getContentAsString());
        assertEquals(causeCategoryRepo.count(), all.size(), "Unfiltered must return every real row");
        assertTrue(categories.size() < all.size(),
            "The typeOfFaultId-filtered list must be smaller than the unfiltered list");
    }

    @Test
    void getCausesOfFault_filteredByCauseCategoryId_returnsOnlyThatCategory() throws Exception {
        Opmc opmc = newOpmc();
        User admin = newUser(User.Role.ADMIN, "Stage2 Hierarchy Admin 3", opmc.getId());
        CauseCategory category = newCauseCategory(newTypeOfFault().getId());
        CauseOfFault sample = newCauseOfFault(category.getId());
        newCauseOfFault(category.getId());
        em.flush(); em.clear();

        MvcResult res = mvc.perform(get("/api/cause-of-faults")
                .param("causeCategoryId", String.valueOf(sample.getCauseCategoryId()))
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId())))
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
        assertTrue(foundSample, "The known fixture cause used as the filter sample must appear in its own category's list");
    }

    @Test
    void causeHierarchy_technicianForbidden() throws Exception {
        Opmc opmc = newOpmc();
        User technician = newUser(User.Role.TECHNICIAN, "Stage2 Hierarchy Tech", opmc.getId());

        MvcResult res = mvc.perform(get("/api/type-of-faults")
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN", opmc.getId())))
            .andReturn();
        assertEquals(403, res.getResponse().getStatus(),
            "TECHNICIAN must not read the classification hierarchy -- classification is "
                + "Admin/Team-Lead post-hoc review only.");
    }
}
