"""geocode_master_data.py — H1a: geocode Exchange + Opmc using the real imported master data.

Populates exchanges.latitude/longitude (columns added by
fieldops/migrations/manual/exchange_geocoding_columns.sql) and opmcs.latitude/longitude (already
present, unpopulated for the 65 real rows) via Nominatim (OpenStreetMap), one-time / re-runnable.

Idempotent by construction: every run queries the database for rows that still have
latitude IS NULL and only processes those, excluding the always-skipped sets below. Rows already
resolved (in an earlier run) are never re-queried or re-classified. This is also what makes the
"only touch what's still unresolved" requirement for the 2026-08-20 retry pass (see below) hold
automatically, with no separate retry-only code path needed.

Scope, per the confirmed plan:
  - All 377 real Exchange rows.
  - All real Opmc rows EXCEPT the 11 already-confirmed non-geographic codes (DEFXXX/SLHQ/CSCT/
    CSCS/CSCI/CSCU/INTN/FLT1/NOLA/NOLC/NESF) and the 3 already-flagged-for-human-confirmation
    codes (SIERRA/HROP/HKOP) -- both sets mirrored from
    fieldops/src/main/java/lk/slt/fieldops/shared/HpCodeProvinceMapping.java so this script and
    that already-tested classification never drift apart. SIERRA/HROP/HKOP are NOT attempted at
    all (not geocoded-then-discarded) -- they're a real open data question, not a place a
    geocoder can guess at.
  - The 3 pre-existing dev/test Opmc rows (ABC-01/TES-10/NEG-18) are matched against no real
    OPMCCODE and fall outside both sets above -- skipped via the same "only real OPMC.csv codes"
    membership check the H1 Province work already established, not a new exclusion invented here.

Name cleaning strips trailing technical-code suffixes real Exchange names carry (confirmed
against the actual file, not assumed) -- both a parenthetical form ("Vilasitha Niwasa(HAV)" ->
"Vilasitha Niwasa", "IDH(RSU)" -> "IDH") and a bare trailing all-caps token requiring a preceding
space so it can never consume an entire short name on its own ("Nugegoda MSU" -> "Nugegoda").

A result is written to the database ONLY if it clears a confidence bar (real Sri Lankan place-type
match, not a road/shop/POI; importance score above a floor) -- anything else is reported as FAILED
or LOW_CONFIDENCE and left NULL rather than silently accepted.

── 2026-08-20 retry-pass additions, from analyzing the first pass's 140+10 unresolved names ──

1. Transliteration overrides. SLT's export uses an aspirated-consonant transliteration
   ("Hambanthota", "Thissamaharamaya") that OSM/Nominatim's gazetteer generally doesn't. Rather
   than a general aspirated-consonant regex (risk: could mangle a name that's correct as-is into
   something wrong), TRANSLITERATION_OVERRIDES below is an explicit dict of pairs individually
   confirmed live against Nominatim to resolve to a real place -- not derived from a pattern, each
   one tested. For names not in that dict, a single generic "th"->"t" normalization is tried as a
   second attempt (covers the same pattern for names never individually spot-checked) -- but it
   goes through the exact same classify() bar as everything else; a generic-fallback match that
   doesn't clear the bar is discarded exactly like any other failed attempt, never accepted just
   because it was a fallback.

2. Multi-candidate ranking fix. classify() previously looked at results[0] only. Confirmed live
   (Maradana, Panadura) that a real class=boundary/type=administrative match can sit at rank 3-4,
   outranked by well-tagged same-named POIs (a railway station, a platform) that Nominatim's
   default relevance ranking prefers. classify() now scans the top 5 candidates for the first one
   clearing the confidence bar, still the same bar, just no longer restricted to rank 0.

Every Nominatim response is cached to .geocode_cache.json, keyed by "<query>|limit=<n>" (the limit
is part of the key because a limit=3 response is a strict subset of a limit=5 response for the
same query -- keeping them separate means a rerun that needs more candidates always gets a fresh
fetch rather than silently reusing a truncated old one). Live requests are rate-limited to 1/sec;
cache hits are not throttled.

Usage: python fieldops/scripts/geocode_master_data.py   (run from the repo root)
"""

import json
import os
import re
import sys
import time

import pymysql
import requests

# Windows console default codepage (cp1252) can't encode every character Nominatim's
# display_name returns (accents, em dashes, etc.) -- replace rather than crash mid-report.
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_PATH = os.path.join(SCRIPT_DIR, ".geocode_cache.json")

NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
USER_AGENT = "slt-fieldops-geocoder/1.0 (H1a master-data geocoding, one-time script)"
RATE_LIMIT_SECONDS = 1.0
RESULT_LIMIT = 5  # scan up to this many candidates per query in classify()

DB_HOST = os.environ.get("SPRING_DATASOURCE_HOST", "localhost")
DB_PORT = int(os.environ.get("SPRING_DATASOURCE_PORT", "3306"))
DB_NAME = os.environ.get("SPRING_DATASOURCE_DB", "slt_fieldops_db")
DB_USER = os.environ.get("SPRING_DATASOURCE_USERNAME", "root")
DB_PASSWORD = os.environ.get("SPRING_DATASOURCE_PASSWORD", "1234")

# Mirrors HpCodeProvinceMapping.java exactly -- do not let these drift from the Java source of truth.
NON_GEOGRAPHIC_OPMC_CODES = {
    "DEFXXX", "SLHQ", "CSCT", "CSCS", "CSCI", "CSCU", "INTN", "FLT1", "NOLA", "NOLC", "NESF",
}
FLAGGED_FOR_HUMAN_CONFIRMATION = {"SIERRA", "HROP", "HKOP"}

GOOD_CLASSES = {"place", "boundary"}
GOOD_TYPES = {
    "city", "town", "village", "suburb", "hamlet", "municipality", "administrative",
    "neighbourhood", "quarter", "isolated_dwelling", "locality", "borough",
}
MIN_IMPORTANCE = 0.1

# Explicit, individually-verified pairs only -- each was queried live against Nominatim and
# confirmed to resolve to a real place/boundary match clearing the same confidence bar as
# everything else (see the H1a retry-pass writeup, QA_Compliance_Consolidated_Report.md).
# Deliberately NOT a regex rule: e.g. "Trincomale"->"Trincomalee" and "Akkarapattu"->
# "Akkaraipattu" aren't th->t substitutions at all, and a couple of these (Thissamaharamaya,
# Thangalla) combine a th->t change with an unrelated ending change that a blind regex would
# get only half right.
TRANSLITERATION_OVERRIDES = {
    "Trincomale": "Trincomalee",
    "Mathale": "Matale",
    "Hambanthota": "Hambantota",
    "Vavunia": "Vavuniya",
    "Thissamaharamaya": "Tissamaharama",
    "Thangalla": "Tangalle",
    "Thalawakele": "Talawakele",
    "Akkarapattu": "Akkaraipattu",
    "Pilimathalawa": "Pilimatalawa",
    "Haputhale": "Haputale",
    "Diyathalawa": "Diyatalawa",
    "Batticalo": "Batticaloa",
    "Negambo": "Negombo",
}


def clean_name(name):
    cleaned = name.strip()
    # Trailing parenthetical suffix, e.g. "Vilasitha Niwasa(HAV)" -> "Vilasitha Niwasa",
    # "IDH(RSU)" -> "IDH" (safe even with no preceding space, since parens unambiguously
    # delimit the suffix from the base name).
    cleaned = re.sub(r"\s*\([A-Z]{2,6}\)\s*$", "", cleaned)
    # Trailing bare all-caps token -- REQUIRES a preceding space, so this can never consume an
    # entire short all-caps name on its own (e.g. a hypothetical bare "IDH" stays "IDH").
    cleaned = re.sub(r"\s+[A-Z]{2,6}$", "", cleaned)
    return cleaned.strip()


def generic_th_normalize(name):
    """'th'/'Th' -> 't'/'T', every occurrence. Only ever tried for names NOT in
    TRANSLITERATION_OVERRIDES, and only ever accepted if the result clears classify()'s normal
    bar -- see module docstring."""
    return re.sub(r"[Tt]h", lambda m: "T" if m.group()[0] == "T" else "t", name)


def load_cache():
    if os.path.exists(CACHE_PATH):
        with open(CACHE_PATH, encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_cache(cache):
    with open(CACHE_PATH, "w", encoding="utf-8") as f:
        json.dump(cache, f, indent=2, ensure_ascii=False)


def geocode(query, cache, limit=RESULT_LIMIT):
    key = f"{query}|limit={limit}"
    if key in cache:
        return cache[key]
    resp = requests.get(
        NOMINATIM_URL,
        params={"q": query, "format": "json", "limit": limit, "addressdetails": 1},
        headers={"User-Agent": USER_AGENT},
        timeout=15,
    )
    results = resp.json() if resp.status_code == 200 else []
    cache[key] = results
    save_cache(cache)  # persist incrementally -- a crash mid-run loses no prior progress
    time.sleep(RATE_LIMIT_SECONDS)  # after the call, so cache hits are never throttled
    return results


def classify(results):
    """Scans up to RESULT_LIMIT candidates for the first one clearing the confidence bar --
    not just results[0]. Returns (status, data): data is (lat, lon, candidate, rank) for
    RESOLVED; (lat, lon, top, 0) for LOW_CONFIDENCE (top result, for reporting); None for FAILED.
    """
    if not results:
        return "FAILED", None
    for rank, cand in enumerate(results[:RESULT_LIMIT]):
        try:
            lat, lon = float(cand["lat"]), float(cand["lon"])
        except (KeyError, TypeError, ValueError):
            continue
        importance = cand.get("importance", 0)
        cls = cand.get("class")
        typ = cand.get("type")
        display = cand.get("display_name", "")
        if ("Sri Lanka" in display and cls in GOOD_CLASSES and typ in GOOD_TYPES
                and importance >= MIN_IMPORTANCE):
            return "RESOLVED", (lat, lon, cand, rank)
    top = results[0]
    lat, lon = float(top["lat"]), float(top["lon"])
    return "LOW_CONFIDENCE", (lat, lon, top, 0)


def geocode_with_fallbacks(cleaned, cache):
    """Tries, in order: (1) the name as-is [now with the multi-candidate ranking fix, which alone
    can resolve a correctly-spelled name like Maradana/Panadura], (2) the explicit transliteration
    override if one exists, (3) a generic th->t normalization if the name isn't in the override
    dict and contains "th". Returns (status, data, attempt_label, attempts_detail) -- the first
    attempt to clear RESOLVED wins; otherwise the ORIGINAL attempt's LOW_CONFIDENCE/FAILED result
    is what gets reported (fallback attempts that also failed are listed in attempts_detail for
    transparency but don't change the reported classification).
    """
    attempts = []

    query = f"{cleaned}, Sri Lanka"
    results = geocode(query, cache)
    status, data = classify(results)
    attempts.append(("original", cleaned, status, data))
    if status == "RESOLVED":
        return status, data, "original", attempts

    override = TRANSLITERATION_OVERRIDES.get(cleaned)
    if override:
        query2 = f"{override}, Sri Lanka"
        results2 = geocode(query2, cache)
        status2, data2 = classify(results2)
        attempts.append(("override", override, status2, data2))
        if status2 == "RESOLVED":
            return status2, data2, "override", attempts
    elif "th" in cleaned or "Th" in cleaned:
        normalized = generic_th_normalize(cleaned)
        if normalized != cleaned:
            query3 = f"{normalized}, Sri Lanka"
            results3 = geocode(query3, cache)
            status3, data3 = classify(results3)
            attempts.append(("generic_th", normalized, status3, data3))
            if status3 == "RESOLVED":
                return status3, data3, "generic_th", attempts

    # Nothing cleared the bar -- report using the original attempt's classification.
    return status, data, "original", attempts


def main():
    conn = pymysql.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
        database=DB_NAME, autocommit=False, charset="utf8mb4",
    )
    cur = conn.cursor()
    cache = load_cache()

    report = {
        "exchange": {"resolved": [], "low_confidence": [], "failed": []},
        "opmc": {
            "resolved": [], "low_confidence": [], "failed": [],
            "skipped_non_geographic": [], "skipped_flagged": [],
        },
    }

    def process(entity_id, code, name, table, bucket):
        cleaned = clean_name(name)
        status, data, via, attempts = geocode_with_fallbacks(cleaned, cache)
        if status == "RESOLVED":
            lat, lon, cand, rank = data
            cur.execute(f"UPDATE {table} SET latitude=%s, longitude=%s WHERE id=%s", (lat, lon, entity_id))
            report[bucket]["resolved"].append((code, name, cleaned, lat, lon, cand.get("display_name"), via, rank))
        elif status == "LOW_CONFIDENCE":
            lat, lon, top, _ = data
            report[bucket]["low_confidence"].append(
                (code, name, cleaned, lat, lon, top.get("display_name"), top.get("class"), top.get("type"),
                 top.get("importance"), attempts)
            )
        else:
            report[bucket]["failed"].append((code, name, cleaned, attempts))

    # Only rows still missing coordinates -- rows already resolved (in this run or an earlier
    # one) are never re-selected, so they're never re-queried or re-classified.
    cur.execute("SELECT id, code, name FROM exchanges WHERE latitude IS NULL ORDER BY code")
    exchanges = cur.fetchall()
    print(f"Geocoding {len(exchanges)} Exchange rows still missing coordinates...")
    for i, (eid, code, name) in enumerate(exchanges, 1):
        process(eid, code, name, "exchanges", "exchange")
        if i % 20 == 0:
            conn.commit()
            print(f"  ... {i}/{len(exchanges)} processed, committed")
    conn.commit()

    cur.execute("SELECT id, code, name FROM opmcs WHERE latitude IS NULL ORDER BY code")
    opmcs_all = cur.fetchall()
    real_opmc_codes = set()
    with open(os.path.join(SCRIPT_DIR, "..", "..", "docs", "master-data", "OPMC.csv"), newline="", encoding="utf-8-sig") as f:
        import csv
        r = csv.reader(f)
        next(r)
        for row in r:
            real_opmc_codes.add(row[0].strip())

    to_geocode = []
    for oid, code, name in opmcs_all:
        if code not in real_opmc_codes:
            continue  # pre-existing dev/test rows (ABC-01/TES-10/NEG-18), not real master data
        if code in NON_GEOGRAPHIC_OPMC_CODES:
            report["opmc"]["skipped_non_geographic"].append((code, name))
            continue
        if code in FLAGGED_FOR_HUMAN_CONFIRMATION:
            report["opmc"]["skipped_flagged"].append((code, name))
            continue
        to_geocode.append((oid, code, name))

    print(f"Geocoding {len(to_geocode)} real, geographic Opmc rows still missing coordinates...")
    for i, (oid, code, name) in enumerate(to_geocode, 1):
        process(oid, code, name, "opmcs", "opmc")
        if i % 20 == 0:
            conn.commit()
            print(f"  ... {i}/{len(to_geocode)} processed, committed")
    conn.commit()

    # Totals across the whole table (not just this run), for the report header.
    cur.execute("SELECT COUNT(*), SUM(latitude IS NOT NULL) FROM exchanges")
    exch_total, exch_with_coords = cur.fetchone()
    cur.execute("SELECT COUNT(*), SUM(latitude IS NOT NULL) FROM opmcs WHERE code IN (%s)" %
                ",".join(["%s"] * len(real_opmc_codes)), list(real_opmc_codes))
    opmc_total, opmc_with_coords = cur.fetchone()
    conn.close()

    # ── Report ──────────────────────────────────────────────────────────────
    lines = []
    def rprint(s=""):
        lines.append(s)

    rprint("\n" + "=" * 100)
    rprint("H1a GEOCODING REPORT (retry pass: transliteration overrides + multi-candidate ranking)")
    rprint("=" * 100)
    rprint(f"\nOverall coverage — Exchange: {exch_with_coords}/{exch_total} have coordinates. "
           f"Opmc (real rows): {opmc_with_coords}/{opmc_total} have coordinates.")

    for entity, label in (("exchange", "Exchange"), ("opmc", "Opmc")):
        b = report[entity]
        total = len(b["resolved"]) + len(b["low_confidence"]) + len(b["failed"])
        rprint(f"\n--- {label} this run: {len(b['resolved'])} newly resolved / "
               f"{len(b['low_confidence'])} still low-confidence / {len(b['failed'])} still failed "
               f"(of {total} retried) ---")

        if b["resolved"]:
            via_counts = {}
            for row in b["resolved"]:
                via_counts[row[6]] = via_counts.get(row[6], 0) + 1
            rprint(f"\n  NEWLY RESOLVED, by how: {via_counts}")
            for code, name, cleaned, lat, lon, display, via, rank in b["resolved"]:
                rprint(f"    {code:10s} {name!r:35s} via={via:12s} rank={rank} -> {display}")

        if b["low_confidence"]:
            rprint(f"\n  STILL LOW-CONFIDENCE (NOT written to DB):")
            for code, name, cleaned, lat, lon, display, cls, typ, imp, attempts in b["low_confidence"]:
                tried = ", ".join(f"{label}={val!r}" for label, val, st, _ in attempts)
                rprint(f"    {code:10s} {name!r:35s} tried[{tried}] top-> class={cls} type={typ} "
                       f"importance={imp:.3f} -> {display}")

        if b["failed"]:
            rprint(f"\n  STILL FAILED (no usable Nominatim result under any attempted spelling):")
            for code, name, cleaned, attempts in b["failed"]:
                tried = ", ".join(f"{label}={val!r}" for label, val, st, _ in attempts)
                rprint(f"    {code:10s} {name!r:35s} tried[{tried}]")

        if entity == "opmc":
            if b["skipped_non_geographic"]:
                rprint(f"\n  SKIPPED - non-geographic ({len(b['skipped_non_geographic'])}, not attempted):")
                for code, name in b["skipped_non_geographic"]:
                    rprint(f"    {code:10s} {name!r}")
            if b["skipped_flagged"]:
                rprint(f"\n  SKIPPED - flagged for human confirmation ({len(b['skipped_flagged'])}, not attempted):")
                for code, name in b["skipped_flagged"]:
                    rprint(f"    {code:10s} {name!r}")

    rprint("\n" + "=" * 100)

    report_text = "\n".join(lines)
    print(report_text)
    report_path = os.path.join(SCRIPT_DIR, "geocode_retry_report.txt")
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report_text)
    print(f"\n(Full report also written to {report_path})")


if __name__ == "__main__":
    main()
