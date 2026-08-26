package lk.slt.fieldops.shared;

import lk.slt.fieldops.entity.Opmc;

import java.util.Map;
import java.util.Set;

/**
 * HpCodeProvinceMapping — HPCODE → {@link Opmc.Province}, built from SLT's real OPMC master-data
 * export ({@code docs/master-data/OPMC.csv}, 62 rows, confirmed against the real file 2026-08-20).
 * H1 (Stage H's Province/HPCODE resolution, folded into the Opmc-import step).
 *
 * <p><b>HPCODE is the authoritative key here, not the free-text Province column</b> in that export —
 * confirmed directly against the file, not assumed. Real inconsistencies in the free text that HPCODE
 * correctly resolves around:
 * <ul>
 *   <li>{@code KTOP} (Kaluthara)'s Province cell reads <i>"Wetern"</i> — a typo for "Western". Its
 *       HPCODE, {@code HPWESS}, is shared with {@code PNOP} (Panadura), whose Province cell is spelled
 *       correctly ("Western") — HPCODE resolves both to {@link Opmc.Province#WESTERN} regardless of
 *       which row's free text is trusted.</li>
 *   <li>Western province is split across three different HPCODEs, each with its own free-text
 *       sub-label — {@code DHRFM/CM}: "Western/ Colombo" (Colombo district), {@code HPWESN}:
 *       "Western/Gampaha" (Gampaha district), {@code HPWESS}: "Western" (Kalutara/other district).
 *       Superficially three different strings; HPCODE correctly collapses all three to the single
 *       {@link Opmc.Province#WESTERN} enum value.</li>
 * </ul>
 *
 * <p><b>{@code HPN/E} and {@code HPNW/NCP} are resolved per-OPMC, not per-HPCODE.</b> Each HPCODE
 * literally declares TWO of the 9 real provinces in its own name (North+East, North Western+North
 * Central) — the master data's own regional grouping is coarser than {@link Opmc.Province}'s 9 values
 * for exactly these two codes, so the HPCODE alone cannot resolve them. Split by real, publicly
 * verifiable Sri Lankan provincial administrative geography (confirmed explicitly, not SLT-internal
 * knowledge and not guessed — see {@link #PROVINCE_BY_OPMCCODE}): under {@code HPN/E}, Jaffna/
 * Kilinochchi/Mullaitivu/Vavuniya/Mannar are {@code NORTHERN} and Trincomalee/Batticaloa/Ampara/
 * Kalmunai are {@code EASTERN}; under {@code HPNW/NCP}, Kurunegala/Chilaw/Kuliyapitiya are
 * {@code NORTH_WESTERN} and Anuradhapura/Polonnaruwa are {@code NORTH_CENTRAL}. All 14 real rows under
 * these two HPCODEs are covered by {@link #PROVINCE_BY_OPMCCODE}; {@link #AMBIGUOUS_HPCODES} still
 * lists both codes as a safety net for any future/unlisted OPMC sharing one of them that isn't already
 * named in {@link #PROVINCE_BY_OPMCCODE} — such a row still resolves {@code AMBIGUOUS_HPCODE} rather
 * than silently falling through unresolved.
 *
 * <p><b>{@code HROP} and {@code HKOP} are resolved directly to {@link Opmc.Province#WESTERN}, by real
 * public geography, not SLT-internal knowledge or a guess.</b> Both have a genuinely blank HPCODE and
 * Province in the real export ({@code docs/master-data/OPMC.csv:60,62}) — confirmed directly against
 * the file — but their own DESCRIPTION names two real, unambiguous Sri Lankan towns: {@code HROP} is
 * Horana (Kalutara District), {@code HKOP} is Havelock Town (Colombo). Both are Western Province beyond
 * dispute, the same reasoning already applied to the {@code HPN/E}/{@code HPNW/NCP} per-town split below
 * — resolved 2026-08-26, moved out of {@link #FLAGGED_FOR_HUMAN_CONFIRMATION} into
 * {@link #PROVINCE_BY_OPMCCODE} alongside the 14 HPN/E/HPNW/NCP rows (same mechanism, second reason for
 * membership: not a shared/ambiguous HPCODE, but a blank one resolved by the OPMC's own identifiable
 * name instead).
 *
 * <p><b>Deliberately NOT resolved here — flagged instead, per explicit instruction not to guess.</b>
 * See {@code QA_Compliance_Consolidated_Report.md}'s H1 Province/HPCODE entries for the full writeup.
 * <ul>
 *   <li>{@code SIERRA} — has a populated HPCODE ({@code DHRFM/CM}) and Province ("Western/ Colombo")
 *       exactly like a real Colombo-district OPMC, but its own DESCRIPTION is "SIERRA" — not a real
 *       place name, the same family as {@code SLHQ}/{@code CSCT}/{@code CSCS}/{@code CSCI}/
 *       {@code CSCU}/{@code INTN}/{@code FLT1}/{@code NOLA}/{@code NOLC}/{@code NESF}, all of which are
 *       correctly excluded via a genuinely blank HPCODE (see {@link #NON_GEOGRAPHIC_OPMC_CODES}).
 *       SIERRA's HPCODE is NOT blank, unlike its siblings — flagged for confirmation with whoever owns
 *       this master data on whether it's a real geographic OPMC or a data-entry error that should have
 *       been blank like the others. Unlike {@code HROP}/{@code HKOP} above, "SIERRA" is not a place name
 *       at all, so there is no public-geography fact that can resolve it — only SLT confirming what it
 *       actually is can close this one honestly.</li>
 * </ul>
 *
 * <p><b>Nothing applied to real {@code opmcs} rows yet.</b> This dev database's only three Opmc rows
 * (codes {@code ABC-01}/{@code TES-10}/{@code NEG-18}) are placeholder/test data and match none of
 * this file's 62 real OPMCCODE values — the same disjoint-coding-scheme finding already logged for
 * OPMCCODE in the H3 work. There is nothing in this database today to apply this mapping to; it exists
 * so a real import (once real production OPMC rows exist) has a correct, tested lookup to call.
 */
public final class HpCodeProvinceMapping {

    private HpCodeProvinceMapping() {}

    /**
     * The 11 confirmed non-geographic OPMC codes — genuinely blank HPCODE in the real export,
     * correctly excluded from geographic import (not an oversight). Kept as an explicit set, not just
     * silently omitted, so a real importer can assert "every OPMC code is accounted for" (mapped,
     * here, flagged, or ambiguous) rather than silently dropping an unrecognized one.
     */
    public static final Set<String> NON_GEOGRAPHIC_OPMC_CODES = Set.of(
        "DEFXXX", "SLHQ", "CSCT", "CSCS", "CSCI", "CSCU", "INTN", "FLT1", "NOLA", "NOLC", "NESF"
    );

    /**
     * OPMC codes with a real, unresolved data question — deliberately absent from both
     * {@link #PROVINCE_BY_HPCODE} and {@link #NON_GEOGRAPHIC_OPMC_CODES}. See the class javadoc for
     * what's wrong with each. {@code HROP}/{@code HKOP} were resolved out of this set 2026-08-26 (see
     * {@link #PROVINCE_BY_OPMCCODE}) — {@code SIERRA} is the one genuinely open item left: it isn't a
     * place name at all, so nothing short of SLT confirming what it actually is can resolve it honestly.
     */
    public static final Set<String> FLAGGED_FOR_HUMAN_CONFIRMATION = Set.of(
        "SIERRA"
    );

    /**
     * HPCODEs whose own value spans more than one real Province and so cannot resolve to a single
     * {@link Opmc.Province} enum value from the HPCODE alone. Every currently-real row under either
     * code is resolved individually via {@link #PROVINCE_BY_OPMCCODE} instead (checked first in
     * {@link #resolve}); this set stays populated as a safety net so a future OPMC sharing one of
     * these codes that ISN'T already named in {@link #PROVINCE_BY_OPMCCODE} still resolves
     * {@code AMBIGUOUS_HPCODE} rather than silently falling through unresolved. See the class javadoc.
     */
    public static final Set<String> AMBIGUOUS_HPCODES = Set.of(
        "HPN/E", "HPNW/NCP"
    );

    /** HPCODE → {@link Opmc.Province}, for every HPCODE that resolves cleanly to exactly one province. */
    public static final Map<String, Opmc.Province> PROVINCE_BY_HPCODE = Map.of(
        "DHRFM/CM", Opmc.Province.WESTERN,        // Western province, Colombo district
        "HPWESN",   Opmc.Province.WESTERN,        // Western province, Gampaha district
        "HPWESS",   Opmc.Province.WESTERN,        // Western province, Kalutara/other district
        "HPSAB",    Opmc.Province.SABARAGAMUWA,
        "HPUVA",    Opmc.Province.UVA,
        "HPCP",     Opmc.Province.CENTRAL,
        "HPSOUTH",  Opmc.Province.SOUTHERN
    );

    /**
     * OPMCCODE → {@link Opmc.Province}, keyed by OPMCCODE directly rather than HPCODE because HPCODE
     * alone can't resolve these rows — for two different reasons, both closed by real, publicly
     * verifiable Sri Lankan geography, confirmed explicitly per row, never guessed:
     * <ul>
     *   <li>The 14 {@code HPN/E}/{@code HPNW/NCP} rows below, whose shared HPCODE names two provinces
     *       at once — each row resolved individually by its town's real province.</li>
     *   <li>{@code HROP} (Horana) and {@code HKOP} (Havelock Town), resolved 2026-08-26 — these have a
     *       genuinely blank HPCODE, not a shared/ambiguous one, but their own DESCRIPTION names a real,
     *       unambiguous Western Province town, so no HPCODE was ever needed to resolve them.</li>
     * </ul>
     * Checked before {@link #PROVINCE_BY_HPCODE}/{@link #AMBIGUOUS_HPCODES} in {@link #resolve}.
     */
    public static final Map<String, Opmc.Province> PROVINCE_BY_OPMCCODE = Map.ofEntries(
        // HPN/E split — NORTHERN
        Map.entry("JFOP", Opmc.Province.NORTHERN),   // Jaffna
        Map.entry("KOOP", Opmc.Province.NORTHERN),   // Kilinochchi
        Map.entry("MLOP", Opmc.Province.NORTHERN),   // Mullaitivu
        Map.entry("VAOP", Opmc.Province.NORTHERN),   // Vavuniya
        Map.entry("MBOP", Opmc.Province.NORTHERN),   // Mannar
        // HPN/E split — EASTERN
        Map.entry("TROP", Opmc.Province.EASTERN),    // Trincomalee
        Map.entry("BTOP", Opmc.Province.EASTERN),    // Batticaloa
        Map.entry("APOP", Opmc.Province.EASTERN),    // Ampara
        Map.entry("KLOP", Opmc.Province.EASTERN),    // Kalmunai
        // HPNW/NCP split — NORTH_WESTERN
        Map.entry("KUOP", Opmc.Province.NORTH_WESTERN),  // Kurunegala
        Map.entry("CWOP", Opmc.Province.NORTH_WESTERN),  // Chilaw
        Map.entry("KPOP", Opmc.Province.NORTH_WESTERN),  // Kuliyapitiya
        // HPNW/NCP split — NORTH_CENTRAL
        Map.entry("ADOP", Opmc.Province.NORTH_CENTRAL),  // Anuradhapura
        Map.entry("PROP", Opmc.Province.NORTH_CENTRAL),  // Polonnaruwa
        // Blank HPCODE, resolved by the OPMC's own real, unambiguous town name — not a shared HPCODE
        Map.entry("HROP", Opmc.Province.WESTERN),    // Horana, Kalutara District
        Map.entry("HKOP", Opmc.Province.WESTERN)     // Havelock Town, Colombo
    );

    /**
     * Resolve a single OPMC's Province from its OPMCCODE/HPCODE, or explain why it can't be resolved
     * yet.
     *
     * @param opmcCode the OPMCCODE (e.g. "KYOP") — checked first against
     *                 {@link #FLAGGED_FOR_HUMAN_CONFIRMATION} and {@link #PROVINCE_BY_OPMCCODE}, since
     *                 those two are per-OPMC rather than per-HPCODE decisions.
     * @param hpCode   the HPCODE cell for this OPMC (may be null/blank for the non-geographic set).
     */
    public static Resolution resolve(String opmcCode, String hpCode) {
        if (opmcCode != null && FLAGGED_FOR_HUMAN_CONFIRMATION.contains(opmcCode)) {
            return Resolution.needsConfirmation(opmcCode);
        }
        if (opmcCode != null && PROVINCE_BY_OPMCCODE.containsKey(opmcCode)) {
            return Resolution.resolved(PROVINCE_BY_OPMCCODE.get(opmcCode));
        }
        if (hpCode == null || hpCode.isBlank()) {
            if (opmcCode != null && NON_GEOGRAPHIC_OPMC_CODES.contains(opmcCode)) {
                return Resolution.nonGeographic(opmcCode);
            }
            return Resolution.needsConfirmation(opmcCode);
        }
        if (AMBIGUOUS_HPCODES.contains(hpCode)) {
            return Resolution.ambiguous(opmcCode, hpCode);
        }
        Opmc.Province province = PROVINCE_BY_HPCODE.get(hpCode);
        if (province == null) {
            return Resolution.unknownHpCode(opmcCode, hpCode);
        }
        return Resolution.resolved(province);
    }

    /** The outcome of resolving one OPMC row — exactly one of five kinds, never a guess. */
    public static final class Resolution {
        public enum Kind { RESOLVED, NON_GEOGRAPHIC, NEEDS_CONFIRMATION, AMBIGUOUS_HPCODE, UNKNOWN_HPCODE }

        private final Kind kind;
        private final Opmc.Province province;
        private final String detail;

        private Resolution(Kind kind, Opmc.Province province, String detail) {
            this.kind = kind;
            this.province = province;
            this.detail = detail;
        }

        static Resolution resolved(Opmc.Province province) {
            return new Resolution(Kind.RESOLVED, province, null);
        }
        static Resolution nonGeographic(String opmcCode) {
            return new Resolution(Kind.NON_GEOGRAPHIC, null, opmcCode);
        }
        static Resolution needsConfirmation(String opmcCode) {
            return new Resolution(Kind.NEEDS_CONFIRMATION, null, opmcCode);
        }
        static Resolution ambiguous(String opmcCode, String hpCode) {
            return new Resolution(Kind.AMBIGUOUS_HPCODE, null, opmcCode + " (HPCODE " + hpCode + ")");
        }
        static Resolution unknownHpCode(String opmcCode, String hpCode) {
            return new Resolution(Kind.UNKNOWN_HPCODE, null, opmcCode + " (HPCODE " + hpCode + ")");
        }

        public Kind getKind() { return kind; }
        public Opmc.Province getProvince() { return province; }
        public String getDetail() { return detail; }
    }
}
