package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.dto.ReportFaultRequest;
import lk.slt.fieldops.entity.Exchange;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.ExchangeRepository;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * H1b — auto-derive the nearest geocoded Exchange from a Fault's GPS, only as a fallback when no
 * Circuit is attached. Live, real-backend proof through the real POST /api/faults endpoint (real
 * JWT filter chain, real ExchangeService, real MySQL) — not a Mockito unit test, per this
 * project's "prove it live" standard applied to every other H1a/H1b piece.
 *
 * Each test is @Transactional and rolls back — no row created here persists.
 *
 * <p><b>2026-09-03, CI-portable-database fix.</b> Originally looked up the real MTK Exchange
 * (geocoded during the H1a master-data pass) by code and used {@code REAL_OPMC_ID = 1L} for every
 * caller — both real rows that only existed because this suite ran against the same long-lived
 * local dev database all session, and both entirely incidental to what this class actually tests
 * (nearest-by-distance ranking, not any specific Exchange's identity). Fixed by creating a real,
 * per-test {@code Opmc}/{@code Exchange} pair via {@code newOpmc()}/{@code newExchange()} — same
 * established self-contained-fixture pattern as ~40 sibling files — placed at the same real-world
 * coordinates MTK previously supplied, so the distance/confidence math under test is unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class H1bNearestExchangeIntegrationTest {

    @Autowired private MockMvc            mvc;
    @Autowired private JwtTokenProvider    jwt;
    @Autowired private FaultRepository     faultRepo;
    @Autowired private UserRepository      userRepo;
    @Autowired private OpmcRepository      opmcRepo;
    @Autowired private ExchangeRepository  exchangeRepo;
    @Autowired private ObjectMapper        json;
    @Autowired private jakarta.persistence.EntityManager em;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role, Long opmcId) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, opmcId);
    }

    /** A fresh, genuinely persisted OPMC — no test may assume any OPMC id pre-exists. */
    private Opmc newOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("H1b Test OPMC " + n);
        o.setCode("H1B" + n);
        o.setAddress("123 Test Road");
        return opmcRepo.save(o);
    }

    /** A fresh, genuinely persisted, geocoded Exchange — no test may assume real master data exists. */
    private Exchange newExchange(Opmc opmc, double lat, double lon) {
        long n = uniq();
        Exchange e = new Exchange();
        e.setName("H1b Test Exchange " + n);
        e.setCode("H1BEX" + n);
        e.setOpmc(opmc);
        e.setLatitude(lat);
        e.setLongitude(lon);
        e.setIsActive(true);
        return exchangeRepo.save(e);
    }

    private User newClient(Long opmcId) {
        long n = uniq();
        User u = new User();
        u.setUsername("h1btest" + n);
        u.setPasswordHash("x");
        u.setFirstName("H1b");
        u.setLastName("Test");
        u.setFullName("H1b Test Client");
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(User.Role.CLIENT);
        u.setOpmcId(opmcId);
        return userRepo.save(u);
    }

    private ReportFaultRequest requestAt(double lat, double lon, Long opmcId) {
        ReportFaultRequest req = new ReportFaultRequest();
        req.setCategory("BROADBAND");
        req.setDescription("H1b nearest-exchange test fault");
        req.setLocationAddress("Test address");
        req.setLatitude(lat);
        req.setLongitude(lon);
        req.setOpmcId(opmcId);
        req.setPriority("MEDIUM");
        return req;
    }

    @Test
    void reportFault_nearRealResolvedExchange_derivesHighConfidenceMatch() throws Exception {
        // A fixture Exchange placed at MTK "Mattakkuliya"'s real, H1a-confirmed geocoded
        // coordinates (6.9732207, 79.8765779) -- the coordinates matter for the distance math
        // under test, the specific Exchange identity behind them does not. A point 200m away
        // should resolve to it with a small, high-confidence distance.
        Opmc opmc = newOpmc();
        Exchange exchange = newExchange(opmc, 6.9732207, 79.8765779);
        User client = newClient(opmc.getId());
        em.flush(); em.clear();

        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(requestAt(6.9750, 79.8770, opmc.getId()))))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(201, res.getResponse().getStatus(), "Fault report must succeed. Body: " + body);
        JsonNode dto = json.readTree(body);

        assertNotNull(dto.get("nearestExchangeId"), "A nearby Exchange must be found. Body: " + body);
        assertFalse(dto.get("nearestExchangeId").isNull(), "nearestExchangeId must not be null. Body: " + body);
        assertEquals(exchange.getId(), dto.get("nearestExchangeId").asLong(),
            "The one fixture Exchange, genuinely closest, must be the match. Body: " + body);
        assertTrue(dto.get("nearestExchangeDistanceKm").asDouble() < 5.0,
            "A point 200m from the fixture Exchange's coordinates must match within a few km, was: " + body);
        assertFalse(dto.get("nearestExchangeLowConfidence").asBoolean(),
            "A close match must NOT be flagged low-confidence. Body: " + body);

        // Verify against the database directly, not just the API response.
        em.flush(); em.clear();
        Long faultId = dto.get("id").asLong();
        Fault persisted = faultRepo.findById(faultId).orElseThrow();
        assertNotNull(persisted.getNearestExchangeId());
        assertNotNull(persisted.getNearestExchangeDistanceKm());
        assertTrue(persisted.getNearestExchangeDistanceKm() < 5.0);
    }

    @Test
    void reportFault_farFromAnyResolvedExchange_flagsLowConfidence() throws Exception {
        // A remote coordinate (Yala / far southeast coast) far from the single fixture Exchange
        // placed near Colombo -- the real point of this test: even far away, SOME Exchange
        // (whichever one happens to be nearest) is still returned, but flagged low-confidence
        // rather than presented with the same certainty as the case above.
        Opmc opmc = newOpmc();
        newExchange(opmc, 6.9732207, 79.8765779);
        User client = newClient(opmc.getId());
        em.flush(); em.clear();

        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(requestAt(6.3728, 81.5183, opmc.getId()))))
            .andReturn();

        String body = res.getResponse().getContentAsString();
        assertEquals(201, res.getResponse().getStatus(), "Fault report must succeed. Body: " + body);
        JsonNode dto = json.readTree(body);

        // Some match must still be returned (never silently omitted just because it's far).
        assertNotNull(dto.get("nearestExchangeId"));
        assertFalse(dto.get("nearestExchangeId").isNull(),
            "A match must still be returned even when far away -- never silently omitted. Body: " + body);

        double distanceKm = dto.get("nearestExchangeDistanceKm").asDouble();
        boolean lowConfidence = dto.get("nearestExchangeLowConfidence").asBoolean();
        assertEquals(distanceKm > 20.0, lowConfidence,
            "lowConfidence must reflect the real 20km threshold exactly, distance was " + distanceKm + "km. Body: " + body);
    }

    @Test
    void reportFault_noGps_rejectedByValidation_beforeAnyMatchIsAttempted() throws Exception {
        // Discovered writing this test: ReportFaultRequest's latitude/longitude are @NotNull, so
        // "report a fault with no GPS" is not actually reachable through the real endpoint -- the
        // request is rejected by validation (400) before FaultService.reportFault ever runs.
        // FaultServiceTest.reportFault_nullLatLon_neverCallsExchangeService covers the defensive
        // null-check inside reportFault itself at the unit level, where controller validation
        // doesn't apply -- this test instead confirms what actually happens at this endpoint: a
        // fault genuinely cannot be created without GPS, so that branch, while correct and safe
        // to keep, is unreachable via this entry point today.
        Opmc opmc = newOpmc();
        User client = newClient(opmc.getId());
        ReportFaultRequest req = requestAt(0, 0, opmc.getId());
        req.setLatitude(null);
        req.setLongitude(null);

        long faultsBefore = faultRepo.count();
        MvcResult res = mvc.perform(post("/api/faults")
                .header("Authorization", bearer(client.getId(), "CLIENT", opmc.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andReturn();

        assertEquals(400, res.getResponse().getStatus(),
            "GPS is mandatory on this request -- confirms the defensive null-check inside "
            + "reportFault is unreachable via this endpoint, not that it's untested (see "
            + "FaultServiceTest for the unit-level coverage of that branch). Body: "
            + res.getResponse().getContentAsString());

        assertEquals(faultsBefore, faultRepo.count(), "No fault row must be created on a rejected request");
    }
}
