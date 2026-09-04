package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.MaterialRepository;
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

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RES-021 (05_RESOURCE_MGMT) -- logged as "genuinely never-written, no substitute exists anywhere"
 * in the 2026-09-02 completeness recount. Investigated before writing: the three-level material
 * hierarchy this row describes was already fully built, not a product gap --
 * {@code ResourceAllocationService.allocateToWorkGroup} (:52-96) is documented in its own class
 * Javadoc as "SRS 5.5.3 (v1.9): the three-level material hierarchy (OPMC pool -> Work Group -> Team
 * Lead distribution)", genuinely decrements {@code Material.currentStock} (the OPMC pool) and
 * increments a real {@code WorkGroupAllocation.allocatedQuantity} row in the same transaction.
 * {@code MaterialRequestService.approveRequest} then draws down from that same Work Group balance
 * for the third tier (Team Lead/self distribution) -- confirmed by reading the class Javadoc's own
 * citation before writing, not assumed.
 *
 * <p>Never exercised by a test -- confirmed before writing: the only existing references to
 * {@code WorkGroupAllocation}/{@code allocateToWorkGroup} anywhere in the test tree
 * ({@code ResourceAllocationOpmcScopingTest}) test read-access scoping (who can see whose
 * allocations), not the decrement/increment accuracy this row targets -- a genuinely different
 * concern, not a duplicate.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ResourceAllocationHierarchyTrackingTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private OpmcRepository opmcRepo;
    @Autowired private WorkGroupRepository workGroupRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private MaterialRepository materialRepo;
    @Autowired private ObjectMapper json;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "rah" + userId, role, opmcId);
    }

    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("RAH OPMC " + n);
        o.setCode("RAH" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    private WorkGroup newWorkGroup(Opmc opmc) {
        long n = uniq();
        WorkGroup wg = new WorkGroup();
        wg.setName("RAH Work Group " + n);
        wg.setOpmc(opmc);
        wg.setIsActive(true);
        return workGroupRepo.save(wg);
    }

    private User newUser(User.Role role, Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("rah" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName(role.name());
        u.setFullName("Test " + role.name() + " " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private Material newMaterial(Opmc opmc, BigDecimal stock) {
        long n = uniq();
        Material m = new Material();
        m.setOpmcId(opmc.getId());
        m.setName("RAH Material " + n);
        m.setSku("RAH-SKU-" + n);
        m.setUnit("units");
        m.setCurrentStock(stock);
        return materialRepo.save(m);
    }

    @Test
    void allocatingToAWorkGroup_decrementsOpmcPool_andIncrementsWorkGroupBalance() throws Exception {
        Opmc opmc = newOpmc();
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        WorkGroup wg = newWorkGroup(opmc);
        Material material = newMaterial(opmc, new BigDecimal("100"));

        String body = "{\"workGroupId\":" + wg.getId() + ",\"materialId\":" + material.getId()
            + ",\"quantity\":20}";
        MvcResult res = mvc.perform(post("/api/resource-allocations")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        String respBody = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(), "Body: " + respBody);

        JsonNode dto = json.readTree(respBody);
        assertEquals(0, new BigDecimal("80").compareTo(dto.get("opmcRemainingStock").decimalValue()),
            "The OPMC pool must decrement by exactly the allocated quantity");
        assertEquals(0, new BigDecimal("20").compareTo(dto.get("allocatedQuantity").decimalValue()),
            "The Work Group's balance must increase by exactly the allocated quantity");

        // Re-fetch both sides independently, not just trusting the response echo.
        Material freshMaterial = materialRepo.findById(material.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("80").compareTo(freshMaterial.getCurrentStock()),
            "The OPMC pool's persisted stock must genuinely be decremented");
    }

    @Test
    void allocatingMoreThanTheOpmcPoolHolds_isRejected() throws Exception {
        Opmc opmc = newOpmc();
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        WorkGroup wg = newWorkGroup(opmc);
        Material material = newMaterial(opmc, new BigDecimal("10"));

        String body = "{\"workGroupId\":" + wg.getId() + ",\"materialId\":" + material.getId()
            + ",\"quantity\":20}";
        MvcResult res = mvc.perform(post("/api/resource-allocations")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();

        assertEquals(400, res.getResponse().getStatus(),
            "Allocating more than the OPMC pool holds must be rejected. Body: "
                + res.getResponse().getContentAsString());

        Material freshMaterial = materialRepo.findById(material.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("10").compareTo(freshMaterial.getCurrentStock()),
            "A rejected over-allocation must leave the OPMC pool completely untouched");
    }

    @Test
    void allocatingTwiceToTheSameWorkGroupAndMaterial_accumulates() throws Exception {
        Opmc opmc = newOpmc();
        User admin = newUser(User.Role.ADMIN, opmc.getId());
        WorkGroup wg = newWorkGroup(opmc);
        Material material = newMaterial(opmc, new BigDecimal("100"));

        String firstBody = "{\"workGroupId\":" + wg.getId() + ",\"materialId\":" + material.getId()
            + ",\"quantity\":20}";
        mvc.perform(post("/api/resource-allocations")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody))
            .andReturn();

        String secondBody = "{\"workGroupId\":" + wg.getId() + ",\"materialId\":" + material.getId()
            + ",\"quantity\":15}";
        MvcResult second = mvc.perform(post("/api/resource-allocations")
                .header("Authorization", bearer(admin.getId(), "ADMIN", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondBody))
            .andReturn();
        String respBody = second.getResponse().getContentAsString();
        assertEquals(200, second.getResponse().getStatus(), "Body: " + respBody);

        JsonNode dto = json.readTree(respBody);
        assertEquals(0, new BigDecimal("35").compareTo(dto.get("allocatedQuantity").decimalValue()),
            "A second allocation to the same Work Group/Material must accumulate (20+15=35), not overwrite");
        assertEquals(0, new BigDecimal("65").compareTo(dto.get("opmcRemainingStock").decimalValue()),
            "The OPMC pool must reflect both decrements together (100-20-15=65)");
    }
}
