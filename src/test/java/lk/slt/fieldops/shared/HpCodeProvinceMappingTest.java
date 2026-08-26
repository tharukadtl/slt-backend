package lk.slt.fieldops.shared;

import lk.slt.fieldops.entity.Opmc;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H1 (Province/HPCODE resolution) — validates {@link HpCodeProvinceMapping} against the REAL, complete
 * {@code docs/master-data/OPMC.csv} (62 rows), not a hand-picked subset or hardcoded duplicate of it.
 * Reading the actual persisted file (rather than re-typing its rows into the test) means this test
 * breaks the moment the two drift apart, instead of silently validating a copy that's stopped matching
 * the real source of truth.
 *
 * <p>Plain JUnit, no Spring context — this is pure data/lookup logic with no DB, no HTTP, matching the
 * "unit test for a pure function" convention already used elsewhere in {@code shared/}.
 */
class HpCodeProvinceMappingTest {

    private static final Path CSV_PATH = Paths.get("..", "docs", "master-data", "OPMC.csv");

    private record Row(String opmcCode, String description, String hpCode, String province) {}

    private List<Row> readRealFile() throws IOException {
        List<String> lines = Files.readAllLines(CSV_PATH, StandardCharsets.UTF_8);
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
    void sierraIsFlaggedNotMappedNotSilentlyExcluded() throws IOException {
        for (Row row : readRealFile()) {
            if (!HpCodeProvinceMapping.FLAGGED_FOR_HUMAN_CONFIRMATION.contains(row.opmcCode())) continue;

            HpCodeProvinceMapping.Resolution res =
                HpCodeProvinceMapping.resolve(row.opmcCode(), row.hpCode());
            assertEquals(HpCodeProvinceMapping.Resolution.Kind.NEEDS_CONFIRMATION, res.getKind(),
                row.opmcCode() + " must resolve as NEEDS_CONFIRMATION, not silently mapped or excluded");
        }
        // HROP/HKOP resolved 2026-08-26 (real, unambiguous Western Province towns) and moved out —
        // SIERRA is the one genuinely open item left: it isn't a place name, only SLT confirming what
        // it actually is can resolve it honestly.
        assertEquals(Set.of("SIERRA"), HpCodeProvinceMapping.FLAGGED_FOR_HUMAN_CONFIRMATION);
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
                + "flagged for confirmation rather than treated as non-geographic");
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
        int resolved = 0, nonGeographic = 0, needsConfirmation = 0, ambiguous = 0;
        for (Row row : readRealFile()) {
            HpCodeProvinceMapping.Resolution res =
                HpCodeProvinceMapping.resolve(row.opmcCode(), row.hpCode());
            switch (res.getKind()) {
                case RESOLVED             -> resolved++;
                case NON_GEOGRAPHIC       -> nonGeographic++;
                case NEEDS_CONFIRMATION   -> needsConfirmation++;
                case AMBIGUOUS_HPCODE     -> ambiguous++;
                case UNKNOWN_HPCODE       -> throw new AssertionError("Unaccounted row: " + row);
            }
        }
        assertEquals(50, resolved,          "cleanly-resolved rows (34 via PROVINCE_BY_HPCODE + 14 via "
                                                 + "PROVINCE_BY_OPMCCODE's HPN/E and HPNW/NCP split + "
                                                 + "2 via PROVINCE_BY_OPMCCODE's HROP/HKOP, resolved "
                                                 + "2026-08-26)");
        assertEquals(11, nonGeographic,     "non-geographic excluded rows");
        assertEquals(1,  needsConfirmation, "flagged-for-confirmation rows (SIERRA only — HROP/HKOP "
                                                 + "resolved 2026-08-26)");
        assertEquals(0,  ambiguous,         "every real row under HPN/E and HPNW/NCP is now named in "
                                                 + "PROVINCE_BY_OPMCCODE — the ambiguous bucket is a "
                                                 + "safety net for future data, not any current row");
        assertEquals(62, resolved + nonGeographic + needsConfirmation + ambiguous);
    }
}
