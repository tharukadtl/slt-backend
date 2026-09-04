package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.MaterialCategory;
import lk.slt.fieldops.repository.MaterialCategoryRepository;
import lk.slt.fieldops.repository.MaterialRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * RES-001 (05_RESOURCE_MGMT, FR-13) — browsing the material catalogue with a category filter and
 * with a FOC filter.
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module ({@code pom.xml} carries only {@code spring-boot-starter-test} and
 * {@code spring-security-test}) and adding a test framework is a project decision, not this suite's.
 * MockMvc through the real filter chain is the module's established convention (see
 * {@link JobIntegrationTest}, {@link BillDisputeAmendmentIntegrationTest}) and drives the identical
 * path: real JWT filter, real {@code @PreAuthorize}, real service, real MySQL. The test is
 * {@code @Transactional} so its rows roll back.</p>
 *
 * <p><b>Endpoint vs. the sheet.</b> The sheet calls {@code GET /api/materials?category=Cables}.
 * There is no {@code /api/materials} resource. The implemented inventory browser is
 * {@code GET /api/inventory/materials/search} ({@code InventoryController}), taking {@code search}
 * and {@code categoryId} (an id, not a category name) and returning
 * {@code List<StockDTO.StockLevelDTO>} — a bare JSON array, not a paged {@code content} envelope.
 * Each item carries a resolved {@code category} name and an {@code isFOC} flag. The category half of
 * the row is asserted against that real endpoint.</p>
 *
 * <p><b>The FOC half has no implementation to assert against.</b> No endpoint anywhere accepts an
 * {@code isFoc} filter. Spring silently ignores an undeclared query parameter, so
 * {@code ?isFoc=true} returns the <i>whole</i> catalogue — chargeable items included — with a 200,
 * rather than 400-ing or filtering. That sub-check is written to state the expectation the row sets
 * and is expected to be red until a server-side FOC filter exists; it deliberately does not assert
 * the absence of the filter, which would lock the gap in as correct behaviour.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InventoryIntegrationTest {

    @Autowired private MockMvc                    mvc;
    @Autowired private JwtTokenProvider           jwt;
    @Autowired private MaterialRepository         materialRepo;
    @Autowired private MaterialCategoryRepository categoryRepo;
    @Autowired private ObjectMapper               json;

    /** Existing row every branch-scoped FK in this schema resolves against. */
    private static final Long REAL_BRANCH_ID = 1L;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_BRANCH_ID);
    }

    private MaterialCategory newCategory(String name) {
        MaterialCategory c = new MaterialCategory();
        c.setName(name);
        c.setDescription("Created by InventoryIntegrationTest");
        c.setIsActive(true);
        return categoryRepo.save(c);
    }

    private Material newMaterial(String name, Long categoryId, Material.ChargeType chargeType) {
        Material m = new Material();
        m.setName(name);
        m.setSku("SKU-" + uniq());
        m.setUnit("m");
        m.setUnitPrice(BigDecimal.valueOf(120));
        m.setCurrentStock(BigDecimal.valueOf(40));
        m.setMinimumThreshold(BigDecimal.TEN);
        m.setCategoryId(categoryId);
        m.setChargeType(chargeType);
        m.setOpmcId(REAL_BRANCH_ID);
        m.setIsActive(true);
        return materialRepo.save(m);
    }

    private JsonNode getJson(String url, Long userId, String role) throws Exception {
        MvcResult res = mvc.perform(get(url).header("Authorization", bearer(userId, role)))
            .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
            "GET " + url + " must return 200. Body: " + res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString());
    }

    private static List<String> collect(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.hasNonNull(field) ? node.get(field).asText() : null));
        return out;
    }

    @Test
    void filterByCategory_returnsCorrectItems() throws Exception {
        // ── Arrange: a "Cables" category and an "Optics" category, two materials each ─────────
        MaterialCategory cables = newCategory("Cables-" + uniq());
        MaterialCategory optics = newCategory("Optics-" + uniq());

        Material focCable = newMaterial("Fibre Drop Cable", cables.getId(), Material.ChargeType.FOC);
        newMaterial("Copper Drop Cable", cables.getId(), Material.ChargeType.CHARGEABLE);
        newMaterial("ONT Splitter", optics.getId(), Material.ChargeType.CHARGEABLE);
        newMaterial("Patch Cord", optics.getId(), Material.ChargeType.FOC);
        materialRepo.flush();

        final Long techId = 4001L;

        assertAll("the inventory browser filters the catalogue as the row specifies",

            // ── Steps 1-3: category filter ───────────────────────────────────────────────────
            () -> {
                JsonNode filtered = getJson(
                    "/api/inventory/materials/search?categoryId=" + cables.getId(),
                    techId, "TECHNICIAN");

                assertTrue(filtered.isArray(), "The browser must return a list of materials");
                assertEquals(2, filtered.size(),
                    "Only the two Cables materials must come back, got: " + filtered);

                List<String> categories = collect(filtered, "category");
                assertTrue(categories.stream().allMatch(cables.getName()::equals),
                    "Every returned item must be in the requested category, got: " + categories);

                assertTrue(collect(filtered, "materialName").contains(focCable.getName()),
                    "The filter must not drop matching items, got: "
                        + collect(filtered, "materialName"));
            },

            // ── The unfiltered call must be a genuine superset, so the filter is real ────────
            () -> {
                JsonNode all = getJson("/api/inventory/materials/search", techId, "TECHNICIAN");
                assertTrue(all.size() >= 4,
                    "Without a filter the whole active catalogue must come back, got " + all.size());
            },

            // ── Steps 4-5: FOC filter ────────────────────────────────────────────────────────
            () -> {
                JsonNode focOnly = getJson(
                    "/api/inventory/materials/search?isFoc=true", techId, "TECHNICIAN");

                List<String> names = collect(focOnly, "materialName");
                boolean allFoc = true;
                for (JsonNode item : focOnly) {
                    if (!item.path("isFOC").asBoolean(false)) { allFoc = false; break; }
                }
                assertTrue(allFoc,
                    "?isFoc=true must return only FOC materials. No endpoint declares an isFoc "
                        + "parameter, so Spring ignores it and the whole catalogue — chargeable "
                        + "items included — comes back with a 200. Returned: " + names);
            }
        );
    }
}
