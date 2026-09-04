package lk.slt.fieldops.shared;

import lk.slt.fieldops.entity.Opmc;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H1 (Province/HPCODE resolution) — validates {@link HpCodeProvinceMapping} against the REAL, complete
 * OPMC export (62 rows), not a hand-picked subset or hardcoded duplicate of it. Reading the actual
 * persisted file (rather than re-typing its rows into the test) means this test breaks the moment the
 * two drift apart, instead of silently validating a copy that's stopped matching the real source of
 * truth.
 *
 * <p><b>2026-09-03, CI-portability fix.</b> Originally read {@code ../docs/master-data/OPMC.csv} — a
 * path outside this repository entirely. {@code fieldops} is its own standalone git repository
 * (confirmed: no enclosing monorepo, {@code docs/} lives in a sibling directory on the machine this
 * was developed on, not part of {@code fieldops} at all), so a real CI checkout of just this repo never
 * has that path — confirmed by actually building and running {@code .github/workflows/test.yml}
 * against a fresh checkout, not assumed. Fixed by committing an identical byte-for-byte copy to
 * {@code src/test/resources/master-data/OPMC.csv} and reading it off the classpath instead — still the
 * real, complete file, just no longer reaching outside this repository's own boundary to find it.</p>
 *
 * <p>Plain JUnit, no Spring context — this is pure data/lookup logic with no DB, no HTTP, matching the
 * "unit test for a pure function" convention already used elsewhere in {@code shared/}.
 */
class HpCodeProvinceMappingTest {

    private static final String CSV_CLASSPATH_RESOURCE = "master-data/OPMC.csv";

    private record Row(String opmcCode, String description, String hpCode, String province) {}

    private List<Row> readRealFile() throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CSV_CLASSPATH_RESOURCE)) {
            assertNotNull(in, CSV_CLASSPATH_RESOURCE + " must be on the test classpath");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
        }
        assertTrue(lines.size() > 1, "OPMC.csv must have a header plus data rows");

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            // No quoted/comma-containing fields in this export — a plain split is exact and honest,
            // not a shortcut that would silently mis-parse a field this file doesn't actually have.
            String[] cols = line.split(",", -1);
            assertEquals(4, cols.length, "Malformed row (expected 4 columns): " + line);
            rows.add(new Row(cols[0].trim(), cols[1].trim(), cols[2].trim(), cols[3].trim()));
        }
        return rows;
    }

    @Test
    void realFileHasExactlySixtyTwoDataRows() throws IOException {
        assertEquals(62, readRealFile().size(),
            "docs/master-data/OPMC.csv must have exactly 62 real OPMC rows — "
                + "if this fails, the persisted file no longer matches the confirmed real export");
    }

    @Test
    void everyRealRowIsAccountedForByExactlyOneCategory() throws IOException {
        List<String> unaccounted = new ArrayList<>();

        for (Row row : readRealFile()) {
            HpCodeProvinceMapping.Resolution res =
                HpCodeProvinceMapping.resolve(row.opmcCode(), row.hpCode());
            if (res.getKind() == HpCodeProvinceMapping.Resolution.Kind.UNKNOWN_HPCODE) {
                unaccounted.add(row.opmcCode() + " -> unrecognised HPCODE '" + row.hpCode() + "'");
            }
        }

        assertTrue(unaccounted.isEmpty(),
            "Every real OPMC row must resolve, be flagged, be excluded as non-geographic, or be "
                + "excluded as an ambiguous HPCODE — never silently fall through. Unaccounted: "
                + unaccounted);
    }

    @Test
    void exactlyElevenNonGeographicCodesMatchTheConfirmedList() throws IOException {
        // Blank-HPCODE rows that are neither flagged for confirmation (SIERRA) nor resolved by real
        // town identity instead (HROP/HKOP, via PROVINCE_BY_OPMCCODE) must be exactly the confirmed
        // 11-code non-geographic list — checked via resolve()'s own NON_GEOGRAPHIC kind, not
        // re-derived by hand, so this stays consistent with what the mapping actually classifies each
        // row as.
        Set<String> nonGeographicRows = new HashSet<>();
        for (Row row : readRealFile()) {
            HpCodeProvinceMapping.Resolution res =
                HpCodeProvinceMapping.resolve(row.opmcCode(), row.hpCode());
            if (res.getKind() == HpCodeProvinceMapping.Resolution.Kind.NON_GEOGRAPHIC) {
                nonGeographicRows.add(row.opmcCode());
            }
        }
        assertEquals(HpCodeProvinceMapping.NON_GEOGRAPHIC_OPMC_CODES, nonGeographicRows,
            "The set of rows resolving NON_GEOGRAPHIC must match exactly the confirmed 11-code "
                + "non-geographic exclusion list");
        assertEquals(11, HpCodeProvinceMapping.NON_GEOGRAPHIC_OPMC_CODES.size());
    }

    @Test
    void flaggedForHumanConfirmationIsNowEmpty() throws IOException {
        // HROP/HKOP resolved 2026-08-26 (real, unambiguous Western Province towns); SIERRA reclassified
        // the same day as a confirmed third-party contractor, not a genuinely open data question. Every
        // code originally flagged is now closed one way or another — nothing sits at NEEDS_CONFIRMATION
        // for any real row today.
        assertEquals(Set.of(), HpCodeProvinceMapping.FLAGGED_FOR_HUMAN_CONFIRMATION);
        for (Row row : readRealFile()) {
            HpCodeProvinceMapping.Resolution res =
                HpCodeProvinceMapping.resolve(row.opmcCode(), row.hpCode());
            assertFalse(res.getKind() == HpCodeProvinceMapping.Resolution.Kind.NEEDS_CONFIRMATION,
                row.opmcCode() + " must not resolve NEEDS_CONFIRMATION — every real row is now closed");
        }
    }

    @Test
    void sierraResolvesAsThirdPartyContractorNotFlaggedNotMapped() throws IOException {
        // Confirmed 2026-08-26: SIERRA is a real third-party contractor entity, not a geographic OPMC —
        // a different category from NON_GEOGRAPHIC_OPMC_CODES (real internal SLT units) and from
        // FLAGGED_FOR_HUMAN_CONFIRMATION (a genuinely open question). It must resolve distinctly, not
        // silently fall into either of those, and not resolve a Province despite its populated
        // (misleading) HPCODE.
        for (Row row : readRealFile()) {
            if (!row.opmcCode().equals("SIERRA")) continue;
            HpCodeProvinceMapping.Resolution res =
                HpCodeProvinceMapping.resolve(row.opmcCode(), row.hpCode());
            assertEquals(HpCodeProvinceMapping.Resolution.Kind.THIRD_PARTY_CONTRACTOR, res.getKind(),
                "SIERRA must resolve THIRD_PARTY_CONTRACTOR, not NEEDS_CONFIRMATION, NON_GEOGRAPHIC, "
                    + "or a Province via its populated HPCODE");
        }
        assertEquals(Set.of("SIERRA"), HpCodeProvinceMapping.THIRD_PARTY_CONTRACTOR_CODES);
        assertFalse(HpCodeProvinceMapping.NON_GEOGRAPHIC_OPMC_CODES.contains("SIERRA"));
        assertFalse(HpCodeProvinceMapping.FLAGGED_FOR_HUMAN_CONFIRMATION.contains("SIERRA"));
    }

    @Test
    void hropAndHkopResolveToWesternByPublicGeographyNotSlrInternalKnowledge() throws IOException {
        // Horana (Kalutara District) and Havelock Town (Colombo) are both real, unambiguous Western
        // Province towns — resolved directly, the same public-geography reasoning already applied to
        // the HPN/E/HPNW/NCP per-town split, not an SLT-internal judgment call.
        for (Row row : readRealFile()) {
            if (row.opmcCode().equals("HROP") || row.opmcCode().equals("HKOP")) {
                HpCodeProvinceMapping.Resolution res =
                    HpCodeProvinceMapping.resolve(row.opmcCode(), row.hpCode());
                assertEquals(HpCodeProvinceMapping.Resolution.Kind.RESOLVED, res.getKind(),
                    row.opmcCode() + " must now resolve, not sit at NEEDS_CONFIRMATION");
                assertEquals(Opmc.Province.WESTERN, res.getProvince(),
                    row.opmcCode() + " must resolve WESTERN");
            }
        }
        assertFalse(HpCodeProvinceMapping.FLAGGED_FOR_HUMAN_CONFIRMATION.contains("HROP"));
        assertFalse(HpCodeProvinceMapping.FLAGGED_FOR_HUMAN_CONFIRMATION.contains("HKOP"));
    }

    @Test
    void sierraHasAPopulatedHpCodeUnlikeItsNonGeographicSiblings() throws IOException {
        Row sierra = readRealFile().stream()
            .filter(r -> r.opmcCode().equals("SIERRA")).findFirst().orElseThrow();
        assertEquals("DHRFM/CM", sierra.hpCode(),
            "SIERRA's HPCODE must be populated (unlike SLHQ/CSCT/etc.) — that's exactly why it's "
                + "resolved via THIRD_PARTY_CONTRACTOR_CODES (checked before any HPCODE-based path) "
                + "rather than treated as non-geographic");
    }

    @Test
    void hropAndHkopHaveGenuinelyBlankHpCode() throws IOException {
        for (Row row : readRealFile()) {
            if (row.opmcCode().equals("HROP") || row.opmcCode().equals("HKOP")) {
                assertTrue(row.hpCode().isEmpty(),
                    row.opmcCode() + "'s HPCODE must be genuinely blank in the real export");
            }
        }
    }

    @Test
    void hpnESplitsIntoNorthernAndEasternPerRealProvincialGeography() throws IOException {
        Set<String> northern = Set.of("JFOP", "KOOP", "MLOP", "VAOP", "MBOP");
        Set<String> eastern  = Set.of("TROP", "BTOP", "APOP", "KLOP");

        Set<String> hpnERows = new HashSet<>();
        for (Row row : readRealFile()) {
            if (row.hpCode().equals("HPN/E")) hpnERows.add(row.opmcCode());
        }
        assertEquals(9, hpnERows.size(), "HPN/E row count in the real file");
        assertEquals(northern, hpnERows.stream().filter(northern::contains).collect(java.util.stream.Collectors.toSet()));

        for (String opmcCode : northern) {
            assertEquals(Opmc.Province.NORTHERN, HpCodeProvinceMapping.resolve(opmcCode, "HPN/E").getProvince(),
                opmcCode + " must resolve NORTHERN");
        }
        for (String opmcCode : eastern) {
            assertEquals(Opmc.Province.EASTERN, HpCodeProvinceMapping.resolve(opmcCode, "HPN/E").getProvince(),
                opmcCode + " must resolve EASTERN");
        }
        assertEquals(java.util.stream.Stream.concat(northern.stream(), eastern.stream())
                .collect(java.util.stream.Collectors.toSet()), hpnERows,
            "The named NORTHERN+EASTERN split must cover every real HPN/E row exactly, no more no less");
    }

    @Test
    void hpNwNcpSplitsIntoNorthWesternAndNorthCentralPerRealProvincialGeography() throws IOException {
        Set<String> northWestern = Set.of("KUOP", "CWOP", "KPOP");
        Set<String> northCentral = Set.of("ADOP", "PROP");

        Set<String> hpNwNcpRows = new HashSet<>();
        for (Row row : readRealFile()) {
            if (row.hpCode().equals("HPNW/NCP")) hpNwNcpRows.add(row.opmcCode());
        }
        assertEquals(5, hpNwNcpRows.size(), "HPNW/NCP row count in the real file");

        for (String opmcCode : northWestern) {
            assertEquals(Opmc.Province.NORTH_WESTERN, HpCodeProvinceMapping.resolve(opmcCode, "HPNW/NCP").getProvince(),
                opmcCode + " must resolve NORTH_WESTERN");
        }
        for (String opmcCode : northCentral) {
            assertEquals(Opmc.Province.NORTH_CENTRAL, HpCodeProvinceMapping.resolve(opmcCode, "HPNW/NCP").getProvince(),
                opmcCode + " must resolve NORTH_CENTRAL");
        }
        assertEquals(java.util.stream.Stream.concat(northWestern.stream(), northCentral.stream())
                .collect(java.util.stream.Collectors.toSet()), hpNwNcpRows,
            "The named NORTH_WESTERN+NORTH_CENTRAL split must cover every real HPNW/NCP row exactly, no more no less");
    }

    @Test
    void ambiguousHpCodesStillCatchAFutureUnlistedOpmcAsASafetyNet() {
        // A hypothetical OPMCCODE NOT in PROVINCE_BY_OPMCCODE, sharing one of the ambiguous HPCODEs —
        // must still resolve AMBIGUOUS_HPCODE, not silently fall through to UNKNOWN_HPCODE or a guess.
        assertEquals(HpCodeProvinceMapping.Resolution.Kind.AMBIGUOUS_HPCODE,
            HpCodeProvinceMapping.resolve("ZZOP-NOT-A-REAL-CODE", "HPN/E").getKind());
        assertEquals(HpCodeProvinceMapping.Resolution.Kind.AMBIGUOUS_HPCODE,
            HpCodeProvinceMapping.resolve("ZZOP-NOT-A-REAL-CODE", "HPNW/NCP").getKind());
    }

    @Test
    void ktopsTypoInFreeTextDoesNotAffectHpCodeResolution() throws IOException {
        Row ktop = readRealFile().stream()
            .filter(r -> r.opmcCode().equals("KTOP")).findFirst().orElseThrow();
        assertEquals("Wetern", ktop.province(),
            "Pinning the real typo this test exists to route around — if this ever reads "
                + "'Western', the source file was corrected and this test (not the mapping) should "
                + "be updated");

        HpCodeProvinceMapping.Resolution res = HpCodeProvinceMapping.resolve("KTOP", ktop.hpCode());
        assertEquals(Opmc.Province.WESTERN, res.getProvince(),
            "HPCODE (HPWESS), not the typo'd free-text Province cell, must resolve KTOP correctly");
    }

    @Test
    void allThreeWesternHpCodesCollapseToTheSameProvince() throws IOException {
        assertEquals(Opmc.Province.WESTERN, HpCodeProvinceMapping.resolve("x", "DHRFM/CM").getProvince());
        assertEquals(Opmc.Province.WESTERN, HpCodeProvinceMapping.resolve("x", "HPWESN").getProvince());
        assertEquals(Opmc.Province.WESTERN, HpCodeProvinceMapping.resolve("x", "HPWESS").getProvince());
    }

    @Test
    void sampleRealRowsResolveToTheCorrectProvince() throws IOException {
        assertEquals(Opmc.Province.CENTRAL,       HpCodeProvinceMapping.resolve("KYOP", "HPCP").getProvince());
        assertEquals(Opmc.Province.SOUTHERN,      HpCodeProvinceMapping.resolve("MTOP", "HPSOUTH").getProvince());
        assertEquals(Opmc.Province.SABARAGAMUWA,  HpCodeProvinceMapping.resolve("AWOP", "HPSAB").getProvince());
        assertEquals(Opmc.Province.UVA,           HpCodeProvinceMapping.resolve("BDOP", "HPUVA").getProvince());
    }

    @Test
    void resolvedRowCountPlusExclusionsAccountForAllSixtyTwoRows() throws IOException {
        int resolved = 0, nonGeographic = 0, thirdPartyContractor = 0, needsConfirmation = 0, ambiguous = 0;
        for (Row row : readRealFile()) {
            HpCodeProvinceMapping.Resolution res =
                HpCodeProvinceMapping.resolve(row.opmcCode(), row.hpCode());
            switch (res.getKind()) {
                case RESOLVED               -> resolved++;
                case NON_GEOGRAPHIC         -> nonGeographic++;
                case THIRD_PARTY_CONTRACTOR -> thirdPartyContractor++;
                case NEEDS_CONFIRMATION     -> needsConfirmation++;
                case AMBIGUOUS_HPCODE       -> ambiguous++;
                case UNKNOWN_HPCODE         -> throw new AssertionError("Unaccounted row: " + row);
            }
        }
        assertEquals(50, resolved,          "cleanly-resolved rows (34 via PROVINCE_BY_HPCODE + 14 via "
                                                 + "PROVINCE_BY_OPMCCODE's HPN/E and HPNW/NCP split + "
                                                 + "2 via PROVINCE_BY_OPMCCODE's HROP/HKOP, resolved "
                                                 + "2026-08-26)");
        assertEquals(11, nonGeographic,     "non-geographic excluded rows");
        assertEquals(1,  thirdPartyContractor, "third-party contractor rows (SIERRA, reclassified "
                                                 + "2026-08-26 — confirmed by SLT, not a geographic OPMC "
                                                 + "at all)");
        assertEquals(0,  needsConfirmation, "flagged-for-confirmation rows — none remain: HROP/HKOP "
                                                 + "resolved and SIERRA reclassified, both 2026-08-26");
        assertEquals(0,  ambiguous,         "every real row under HPN/E and HPNW/NCP is now named in "
                                                 + "PROVINCE_BY_OPMCCODE — the ambiguous bucket is a "
                                                 + "safety net for future data, not any current row");
        assertEquals(62, resolved + nonGeographic + thirdPartyContractor + needsConfirmation + ambiguous);
    }
}
