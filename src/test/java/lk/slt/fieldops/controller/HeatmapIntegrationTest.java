package lk.slt.fieldops.controller;

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
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * ANA-008 (09_ANALYTICS, FR-25) — the geographic heat map's data feed: a set of GPS points with a
 * density/intensity weight, every one of them inside Sri Lanka.
 *
 * <p><b>Tool substitution.</b> REST Assured is not a dependency of this module; MockMvc through
 * the real filter chain against the real MySQL is the module's convention. Requires
 * {@code SPRING_PROFILES_ACTIVE=local}; {@code @Transactional} so the fixtures roll back.</p>
 *
 * <p><b>Endpoint corrections against the sheet.</b> There is no {@code GET /api/dashboard/heatmap}
 * and no {@code resolution} parameter anywhere in {@code DashboardController}. The heat map the
 * Admin portal actually renders is fed by {@code GET /api/dashboard/geographic-data}
 * ({@code DashboardPage.js} lines ~295-901: {@code geoData.faultHeatMap} becomes react-leaflet
 * {@code CircleMarker}s whose radius is scaled by each point's {@code intensity}). Its body is
 * {@code {faultHeatMap, technicianLocations, regions}}, and each point is a
 * {@code DashboardDTO.GeoPointDTO} carrying {@code latitude}/{@code longitude} — not the row's
 * {@code lat}/{@code lng}. There is no server-side grid aggregation at all: one point is emitted
 * per fault that has coordinates, and the "density" is expressed as {@code intensity} (1.0 open,
 * 0.7 in progress, 0.3 completed) plus a separate {@code regions} array carrying a per-region
 * {@code density}. Both are asserted.</p>
 *
 * <p>The Sri Lanka bounds asserted here (lat 5.9–9.9, lng 79.5–81.9) are exactly the row's, and
 * exactly the constants {@code LocationService.validateSriLankaCoords} enforces on the technician
 * GPS path (FAULT-018, resolved 2026-08-06) and {@code slt-ai-module}'s {@code Config} uses.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HeatmapIntegrationTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository   userRepo;
    @Autowired private FaultRepository  faultRepo;
    @Autowired private ObjectMapper     json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final Long REAL_BRANCH_ID = 1L;

    private static final double SL_LAT_MIN = 5.9,  SL_LAT_MAX = 9.9;
    private static final double SL_LNG_MIN = 79.5, SL_LNG_MAX = 81.9;

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

    private Fault newGeoFault(User customer, double lat, double lng,
                              Fault.FaultStatus status, String address) {
        long n = uniq();
        Fault f = new Fault();
        f.setFaultNumber("FLT-GEO-" + n);
        f.setOpmcId(REAL_BRANCH_ID);
        f.setCustomerId(customer.getId());
        f.setCustomerName(customer.getFullName());
        f.setCategory(Fault.FaultCategory.FIBER);
        f.setDescription("Heat map fixture " + n);
        f.setLocationAddress(address);
        f.setLocationDistrict("Colombo");
        f.setLatitude(lat);
        f.setLongitude(lng);
        f.setPriority(Fault.FaultPriority.MEDIUM);
        f.setStatus(status);
        return faultRepo.save(f);
    }

    private void flushAndClear() {
        userRepo.flush();
        faultRepo.flush();
        em.flush();
        em.clear();
    }

    /** Steps 1-6. */
    @Test
    void heatmap_gpsDensityWithinSLBounds() throws Exception {
        User admin    = newUser(User.Role.ADMIN,  "Heatmap Admin");
        User customer = newUser(User.Role.CLIENT, "Heatmap Client");
        String hdr    = bearer(admin.getId(), "ADMIN");

        // Three real Sri Lankan fault locations across three statuses, so the intensity weighting
        // has something to weight.
        newGeoFault(customer, 6.9271, 79.8612, Fault.FaultStatus.REPORTED,    "Colombo 03");
        newGeoFault(customer, 7.2906, 80.6337, Fault.FaultStatus.IN_PROGRESS, "Kandy");
        newGeoFault(customer, 6.0535, 80.2210, Fault.FaultStatus.COMPLETED,   "Galle");
        flushAndClear();

        // Steps 1-2.
        MvcResult res = mvc.perform(get("/api/dashboard/geographic-data")
                .header("Authorization", hdr)).andReturn();
        String raw = res.getResponse().getContentAsString();
        assertEquals(200, res.getResponse().getStatus(),
            "GET /api/dashboard/geographic-data must answer 200. Body: " + raw);

        JsonNode body = json.readTree(raw);

        // Step 3 — an array of GPS points carrying an intensity weight.
        JsonNode heatMap = body.path("faultHeatMap");
        assertTrue(heatMap.isArray(),
            "faultHeatMap must be an array of geo points. Body: " + body);
        assertTrue(heatMap.size() >= 3,
            "The 3 seeded faults with coordinates must appear on the heat map. Got "
                + heatMap.size() + " points.");

        // Steps 4-6, checked over every point the map would actually plot.
        List<String> outOfBounds  = new ArrayList<>();
        List<String> badIntensity = new ArrayList<>();

        for (JsonNode p : heatMap) {
            assertTrue(p.path("latitude").isNumber() && p.path("longitude").isNumber(),
                "Every heat map point must carry numeric latitude and longitude (the sheet calls"
                    + " them lat/lng). Point: " + p);
            assertTrue(p.path("intensity").isNumber(),
                "Every heat map point must carry an intensity. Point: " + p);

            double lat = p.path("latitude").asDouble();
            double lng = p.path("longitude").asDouble();

            if (lat < SL_LAT_MIN || lat > SL_LAT_MAX || lng < SL_LNG_MIN || lng > SL_LNG_MAX) {
                outOfBounds.add("faultId=" + p.path("faultId").asText()
                        + " (" + lat + ", " + lng + ")");
            }
            if (p.path("intensity").asDouble() < 0) {
                badIntensity.add("faultId=" + p.path("faultId").asText()
                        + " intensity=" + p.path("intensity").asDouble());
            }
        }

        assertTrue(badIntensity.isEmpty(),
            "Every heat map intensity must be >= 0. Offenders: " + badIntensity);

        assertTrue(outOfBounds.isEmpty(),
            "Every fault plotted on the Sri Lanka heat map must sit inside Sri Lanka"
                + " (lat " + SL_LAT_MIN + "-" + SL_LAT_MAX + ", lng " + SL_LNG_MIN + "-"
                + SL_LNG_MAX + "). " + outOfBounds.size() + " point(s) outside: " + outOfBounds);

        // The density weighting must genuinely vary with status rather than being a constant —
        // that is what makes it a heat map rather than a pin map.
        boolean sawOpenWeight      = false;   // REPORTED/ASSIGNED etc.
        boolean sawCompletedWeight = false;
        for (JsonNode p : heatMap) {
            if ("COMPLETED".equals(p.path("status").asText())) {
                sawCompletedWeight = true;
                assertEquals(0.3, p.path("intensity").asDouble(), 0.001,
                    "A completed fault must carry the lowest heat weight. Point: " + p);
            }
            if ("IN_PROGRESS".equals(p.path("status").asText())) {
                sawOpenWeight = true;
                assertEquals(0.7, p.path("intensity").asDouble(), 0.001,
                    "An in-progress fault must carry the middle heat weight. Point: " + p);
            }
        }
        assertTrue(sawCompletedWeight && sawOpenWeight,
            "The seeded COMPLETED and IN_PROGRESS faults must both be on the map so the intensity"
                + " weighting can be judged. Points: " + heatMap);

        // The row's "density grid": this API expresses it as per-region density rather than a
        // resolution=50 grid, so that is asserted where it really lives.
        JsonNode regions = body.path("regions");
        assertTrue(regions.isArray() && regions.size() > 0,
            "The geographic feed must carry per-region density buckets. Body: " + body);
        for (JsonNode r : regions) {
            assertTrue(r.path("density").isNumber() && r.path("density").asDouble() >= 0,
                "Every region's density must be a non-negative number. Region: " + r);
            assertFalse(r.path("regionName").asText().isBlank(),
                "Every region must be named. Region: " + r);
            List<double[]> ignored = new ArrayList<>();   // shape check only
            assertTrue(r.path("coordinates").isArray() && r.path("coordinates").size() > 0,
                "Every region must carry a boundary polygon. Region: " + r + ignored);
        }
    }
}
