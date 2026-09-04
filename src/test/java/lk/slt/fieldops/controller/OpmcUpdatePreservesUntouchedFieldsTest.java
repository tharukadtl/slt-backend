package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Opmc;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA_Compliance_Consolidated_Report.md — CRITICAL, 2026-09-02: {@code PUT /api/opmcs/{id}} silently
 * nulled {@code city}/{@code district}/{@code postalCode}/{@code latitude}/{@code longitude} (and,
 * confirmed by the same root cause during this fix, {@code coverageDistricts}/{@code coverageCities}
 * and — via a different mechanism, a hardcoded "-"/default fallback — {@code address}/{@code workingDays})
 * on every real edit, because {@code OpmcController.update} reuses {@code CreateOpmcRequest} (the same
 * DTO as POST) and {@code OpmcService.mapRequestToEntity} unconditionally overwrote those fields from
 * whatever the request carried. The Admin edit form ({@code frontend-admin/src/pages/Opmcs/OpmcsPage.js},
 * {@code OpmcModal}) only ever sends {@code name/code/address/province/phone/email} — every other field
 * arrived here as {@code null} and silently clobbered the real, already-geocoded column value.
 *
 * <p><b>Live-data check performed before this fix (see the QA report entry for full detail):</b> of the
 * 62 real imported OPMCs (all created 2026-08-20, geocoded by H1a shortly after), only one
 * ({@code SIERRA}) was ever edited after the geocoding pass — and it was on H1a's own "skipped, flagged
 * for human review" list, so it never had coordinates to lose in the first place. No real geocoded
 * OPMC/Exchange coordinate data was destroyed by this bug before the fix landed — it was live and
 * imminent, not yet triggered on a row that mattered.</p>
 *
 * <p><b>Fix.</b> {@code mapRequestToEntity} now only overwrites a field when the incoming request
 * actually carries a non-null value; fields absent from the request preserve the entity's existing
 * value. Behaviorally identical on {@code POST /api/opmcs} (a fresh {@code Opmc} already has every
 * field null, so "skip when absent" and "set null" produce the same result there) and additive-only on
 * {@code PUT /api/opmcs/{id}} — this test proves the update path specifically.</p>
 *
 * <p><b>Harness.</b> MockMvc through the real filter chain, real MySQL, {@code @Transactional} rollback
 * — matching {@code OpmcWriteActionsRoleRestrictionTest}'s established convention for this controller.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpmcUpdatePreservesUntouchedFieldsTest {

    @Autowired private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserRepository userRepo;
    @Autowired private OpmcRepository opmcRepo;

    private static final Long REAL_OPMC_ID = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String superAdminBearer() {
        User u = new User();
        long n = uniq();
        u.setUsername("opud" + n);
        u.setPasswordHash("x");
        u.setFirstName("Test");
        u.setLastName("SuperAdmin");
        u.setFullName("Test SuperAdmin " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(User.Role.SUPER_ADMIN);
        u.setOpmcId(REAL_OPMC_ID);
        User saved = userRepo.save(u);
        userRepo.flush();
        return "Bearer " + jwt.createAccessToken(saved.getId(), saved.getUsername(), "SUPER_ADMIN", REAL_OPMC_ID);
    }

    /** A fully-geocoded, fully-populated OPMC, mirroring what a real imported+H1a-geocoded row looks like. */
    private Opmc newFullyPopulatedOpmc() {
        long n = uniq();
        Opmc o = new Opmc();
        o.setName("OPUD Test OPMC " + n);
        o.setCode("OU" + n);
        o.setAddress("No. 12, Galle Road");
        o.setCity("Colombo");
        o.setDistrict("Colombo");
        o.setProvince(Opmc.Province.WESTERN);
        o.setPostalCode("00300");
        o.setPhone("0112233445");
        o.setEmail("opud" + n + "@slt.lk");
        o.setLatitude(6.9271);
        o.setLongitude(79.8612);
        o.setCoverageDistricts("Colombo,Gampaha");
        o.setCoverageCities("Colombo,Negombo");
        Opmc saved = opmcRepo.save(o);
        opmcRepo.flush();
        return saved;
    }

    @Test
    void editingOnlyNameAddressProvincePhoneEmail_preservesCityDistrictPostalLatLng() throws Exception {
        Opmc target = newFullyPopulatedOpmc();
        String bearer = superAdminBearer();

        // Exactly the shape the real Admin edit form (OpmcModal) sends — no city/district/
        // postalCode/latitude/longitude/coverageDistricts/coverageCities in the body at all.
        String editFormBody = "{"
                + "\"name\":\"OPUD Renamed\","
                + "\"code\":\"" + target.getCode() + "\","
                + "\"address\":\"No. 99, New Road\","
                + "\"province\":\"WESTERN\","
                + "\"phone\":\"0119998888\","
                + "\"email\":\"renamed@slt.lk\""
                + "}";

        MvcResult result = mvc.perform(put("/api/opmcs/{id}", target.getId())
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(editFormBody))
            .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
            "Update must succeed. Body: " + result.getResponse().getContentAsString());

        JsonNode dto = JSON.readTree(result.getResponse().getContentAsString());

        // The fields the form DID send are genuinely updated.
        assertEquals("OPUD Renamed", dto.get("name").asText());
        assertEquals("No. 99, New Road", dto.get("address").asText());
        assertEquals("0119998888", dto.get("phone").asText());
        assertEquals("renamed@slt.lk", dto.get("email").asText());

        // The fields the form did NOT send must survive untouched — this is the fix under test.
        assertEquals("Colombo", dto.get("city").asText(),
            "city must be preserved, not nulled, when absent from the update request");
        assertEquals("Colombo", dto.get("district").asText(),
            "district must be preserved, not nulled, when absent from the update request");
        assertEquals("00300", dto.get("postalCode").asText(),
            "postalCode must be preserved, not nulled, when absent from the update request");
        assertEquals(6.9271, dto.get("latitude").asDouble(), 0.0001,
            "latitude must be preserved, not nulled, when absent from the update request");
        assertEquals(79.8612, dto.get("longitude").asDouble(), 0.0001,
            "longitude must be preserved, not nulled, when absent from the update request");
        assertEquals("Colombo,Gampaha", dto.get("coverageDistricts").asText(),
            "coverageDistricts must be preserved, not nulled, when absent from the update request");
        assertEquals("Colombo,Negombo", dto.get("coverageCities").asText(),
            "coverageCities must be preserved, not nulled, when absent from the update request");

        // Re-fetch from the DB directly, not just trusting the echoed response.
        Opmc reloaded = opmcRepo.findById(target.getId()).orElseThrow();
        assertEquals("Colombo", reloaded.getCity());
        assertEquals("Colombo", reloaded.getDistrict());
        assertEquals("00300", reloaded.getPostalCode());
        assertNotNull(reloaded.getLatitude());
        assertEquals(6.9271, reloaded.getLatitude(), 0.0001);
        assertNotNull(reloaded.getLongitude());
        assertEquals(79.8612, reloaded.getLongitude(), 0.0001);
    }

    @Test
    void sendingANewValueForAPreservedField_stillGenuinelyUpdatesIt() throws Exception {
        // Proves the fix doesn't over-correct into "these fields can never change again" —
        // a request that DOES carry a value must still overwrite the existing one.
        Opmc target = newFullyPopulatedOpmc();
        String bearer = superAdminBearer();

        String bodyWithNewCityAndCoords = "{"
                + "\"name\":\"" + target.getName() + "\","
                + "\"code\":\"" + target.getCode() + "\","
                + "\"city\":\"Kandy\","
                + "\"district\":\"Kandy\","
                + "\"latitude\":7.2906,"
                + "\"longitude\":80.6337"
                + "}";

        MvcResult result = mvc.perform(put("/api/opmcs/{id}", target.getId())
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithNewCityAndCoords))
            .andReturn();
        assertEquals(200, result.getResponse().getStatus());

        Opmc reloaded = opmcRepo.findById(target.getId()).orElseThrow();
        assertEquals("Kandy", reloaded.getCity());
        assertEquals("Kandy", reloaded.getDistrict());
        assertEquals(7.2906, reloaded.getLatitude(), 0.0001);
        assertEquals(80.6337, reloaded.getLongitude(), 0.0001);
        // Fields not sent in THIS request still preserve their prior value.
        assertEquals("00300", reloaded.getPostalCode());
    }

    @Test
    void code_cannotBeChangedByAnUpdate_regardlessOfThisFix() throws Exception {
        Opmc target = newFullyPopulatedOpmc();
        String originalCode = target.getCode();
        String bearer = superAdminBearer();

        String body = "{\"name\":\"" + target.getName() + "\",\"code\":\"SOMETHING-ELSE\"}";

        mvc.perform(put("/api/opmcs/{id}", target.getId())
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();

        Opmc reloaded = opmcRepo.findById(target.getId()).orElseThrow();
        assertEquals(originalCode, reloaded.getCode());
    }
}
