"""import_master_data.py — loads docs/master-data/ into the fieldops DB.

Pipeline: Opmc -> Exchange -> CircuitCategory -> Cab/Dp/Circuit (Cab/Dp/
Circuit derived in one streaming pass over CIRCUIT.csv, the only file too
large to load whole — 349,180 rows, ~32MB, Windows-1252 encoded).

Confirmed decisions applied (see docs/QA_Compliance_Consolidated_Report.md,
"Master-data CSV import investigation" and the CAB_DP.csv-superseded note):
  - Blank CIRCUITTYPE (35 rows)        -> circuits.circuit_category_id NULL
  - Orphan EXCHANGECODE (104 codes,
    2,243 rows, root cause tracked
    separately, not blocking this)     -> cabs.exchange_id NULL
  - DP == "DEFXXX" (12 rows)           -> circuits.dp_id NULL, no Dp row made
  - CAB_DP.csv                         -> not imported (superseded by the
                                           real Cab/Dp structure this script
                                           derives from CIRCUIT.csv itself)

Resumable by design, not just by intent: on every run it loads whatever
Opmc/Exchange/Cab/Dp/Circuit rows already exist into in-memory caches keyed
by the same natural keys used to decide "is this new", so re-running after
a crash (or a deliberate partial run) skips already-imported rows rather
than erroring or duplicating them. Batches of BATCH_SIZE CIRCUIT.csv rows
are committed together so a crash loses at most one in-flight batch.

Usage: python fieldops/scripts/import_master_data.py
Run from the repo root (docs/master-data/ resolved relative to cwd).
"""

import csv
import os
import sys
import time

import pymysql

MASTER_DATA_DIR = os.path.join("docs", "master-data")
BATCH_SIZE = 5000

# Local-dev DB defaults, matching fieldops/src/main/resources/application-local.yml
# (gitignored, never committed) -- override via env vars for any other environment.
DB_HOST = os.environ.get("SPRING_DATASOURCE_HOST", "localhost")
DB_PORT = int(os.environ.get("SPRING_DATASOURCE_PORT", "3306"))
DB_NAME = os.environ.get("SPRING_DATASOURCE_DB", "slt_fieldops_db")
DB_USER = os.environ.get("SPRING_DATASOURCE_USERNAME", "root")
DB_PASSWORD = os.environ.get("SPRING_DATASOURCE_PASSWORD", "1234")

# Province free-text (OPMC.csv) -> the 9-value opmcs.province ENUM.
# Same defaulting-to-NULL-on-ambiguity convention as
# fieldops/migrations/manual/opmc_rename.sql: don't guess.
PROVINCE_MAP = {
    "central": "CENTRAL",
    "sabaragamuwa": "SABARAGAMUWA",
    "uva": "UVA",
    "south": "SOUTHERN",
    "north western": "NORTH_WESTERN",
    "western": "WESTERN",
    "wetern": "WESTERN",          # source typo
    "western/ colombo": "WESTERN",
    "western/gampaha": "WESTERN",
    # "north east" (9 rows) has no matching single enum value (the schema
    # has separate NORTHERN/EASTERN, no combined region) -- left unmapped
    # on purpose, falls through to NULL below, not guessed at.
}


def read_csv_all(path, encoding="utf-8-sig"):
    with open(path, newline="", encoding=encoding) as f:
        reader = csv.reader(f)
        header = next(reader)
        rows = list(reader)
    return header, rows


def now_expr():
    return "NOW(6)"


def import_opmc(cur, conn):
    path = os.path.join(MASTER_DATA_DIR, "OPMC.csv")
    header, rows = read_csv_all(path)
    idx = {c: header.index(c) for c in ("OPMCCODE", "DESCRIPTION", "HPCODE", "Province")}

    cur.execute("SELECT code FROM opmcs")
    existing = {r[0] for r in cur.fetchall()}

    inserted = 0
    for row in rows:
        code = row[idx["OPMCCODE"]].strip()
        if code in existing:
            continue
        name = row[idx["DESCRIPTION"]].strip()
        province_raw = row[idx["Province"]].strip().lower()
        province = PROVINCE_MAP.get(province_raw)  # None -> SQL NULL
        cur.execute(
            "INSERT INTO opmcs (name, code, address, province, created_at, updated_at) "
            "VALUES (%s, %s, %s, %s, NOW(), NOW())",
            (name, code, "", province),
        )
        existing.add(code)
        inserted += 1
    conn.commit()
    print(f"Opmc: {inserted} inserted, {len(existing) - inserted} already present, {len(existing)} total")


def import_exchange(cur, conn):
    path = os.path.join(MASTER_DATA_DIR, "EXCHANGE.csv")
    header, rows = read_csv_all(path)
    idx = {c: header.index(c) for c in ("EXCHANGECODE", "DESCRIPTION", "OPMCCODE")}

    cur.execute("SELECT code, id FROM opmcs")
    opmc_code_to_id = {r[0]: r[1] for r in cur.fetchall()}

    cur.execute("SELECT code FROM exchanges")
    existing = {r[0] for r in cur.fetchall()}

    inserted, skipped_no_opmc = 0, 0
    for row in rows:
        code = row[idx["EXCHANGECODE"]].strip()
        if code in existing:
            continue
        opmc_code = row[idx["OPMCCODE"]].strip()
        opmc_id = opmc_code_to_id.get(opmc_code)
        if opmc_id is None:
            # Not expected -- EXCHANGE.csv -> OPMC.csv FK was confirmed 100%
            # clean during the investigation. Skip + report rather than
            # violate exchanges.opmc_id's NOT NULL constraint.
            skipped_no_opmc += 1
            continue
        name = row[idx["DESCRIPTION"]].strip()
        cur.execute(
            "INSERT INTO exchanges (name, code, opmc_id, is_active, created_at, updated_at) "
            "VALUES (%s, %s, %s, 1, NOW(), NOW())",
            (name, code, opmc_id),
        )
        existing.add(code)
        inserted += 1
    conn.commit()
    print(f"Exchange: {inserted} inserted, {len(existing) - inserted} already present, "
          f"{skipped_no_opmc} skipped (no matching Opmc), {len(existing)} total")


def import_circuit_category(cur, conn):
    path = os.path.join(MASTER_DATA_DIR, "CIRCUITCATEGORY.csv")
    header, rows = read_csv_all(path)
    idx = {c: header.index(c) for c in ("CIRCUITCATCODE", "DESCRIPTION")}

    cur.execute("SELECT id FROM circuit_categories")
    existing = {r[0] for r in cur.fetchall()}

    inserted = 0
    for row in rows:
        cat_id = int(row[idx["CIRCUITCATCODE"]].strip())
        if cat_id in existing:
            continue
        desc = row[idx["DESCRIPTION"]].strip()
        cur.execute(
            "INSERT INTO circuit_categories (id, code, name, is_active, created_at, updated_at) "
            "VALUES (%s, %s, %s, 1, NOW(), NOW())",
            (cat_id, desc, desc),
        )
        existing.add(cat_id)
        inserted += 1
    conn.commit()
    print(f"CircuitCategory: {inserted} inserted, {len(existing) - inserted} already present, "
          f"{len(existing)} total")


def import_circuit_stream(cur, conn):
    path = os.path.join(MASTER_DATA_DIR, "CIRCUIT.csv")

    cur.execute("SELECT code, id FROM exchanges")
    exchange_code_to_id = {r[0]: r[1] for r in cur.fetchall()}

    # cab_cache / dp_cache keys are upper()-cased on the code component because
    # MySQL's utf8mb4_0900_ai_ci collation (the DB default here) is
    # case-insensitive: uk_cab_exchange_code / uk_dp_cab_code reject
    # "L010" and "l010" as duplicates even though they're different Python
    # strings. Discovered mid-run (2026-08-20): cab_id 505 already had DP
    # "L010" committed; the very next occurrence was "l010" for the same
    # cab, and the case-sensitive dict missed the match, so the INSERT hit
    # the DB's case-insensitive unique constraint instead of the cache.
    # First-seen casing is still what gets stored -- only the lookup key is
    # normalized, not the data.

    # cab_cache key: (exchange_id_or_None, code.upper()) -- matches uk_cab_exchange_code.
    cur.execute("SELECT exchange_id, code, id FROM cabs")
    cab_cache = {(r[0], r[1].upper()): r[2] for r in cur.fetchall()}

    # dp_cache key: (cab_id, code.upper()) -- matches uk_dp_cab_code.
    cur.execute("SELECT cab_id, code, id FROM dps")
    dp_cache = {(r[0], r[1].upper()): r[2] for r in cur.fetchall()}

    cur.execute("SELECT code FROM circuits")
    existing_circuit_codes = {r[0] for r in cur.fetchall()}

    new_cabs = new_dps = new_circuits = skipped_existing = 0
    total_rows = 0
    start = time.time()

    with open(path, newline="", encoding="cp1252") as f:
        reader = csv.reader(f)
        header = next(reader)
        exch_idx = header.index("EXCHANGECODE")
        cname_idx = header.index("CIRCUITNAME")
        dp_idx = header.index("DP")
        ctype_idx = header.index("CIRCUITTYPE")
        cid_idx = header.index("CIRCUITID")

        since_commit = 0
        for row in reader:
            if len(row) != len(header):
                continue  # no malformed rows in this file (verified), guard kept anyway
            total_rows += 1

            circuit_code = row[cid_idx].strip()
            if circuit_code in existing_circuit_codes:
                skipped_existing += 1
                continue

            exchangecode = row[exch_idx].strip()
            circuitname = row[cname_idx].strip()
            dp_raw = row[dp_idx].strip()
            ctype_raw = row[ctype_idx].strip()

            exchange_id = exchange_code_to_id.get(exchangecode)  # None for the 104 orphan codes

            # --- resolve/create Cab ---
            cab_key = (exchange_id, circuitname.upper())
            cab_id = cab_cache.get(cab_key)
            if cab_id is None:
                cur.execute(
                    "INSERT INTO cabs (name, code, exchange_id, is_active, created_at, updated_at) "
                    "VALUES (%s, %s, %s, 1, NOW(), NOW())",
                    (circuitname, circuitname, exchange_id),
                )
                cab_id = cur.lastrowid
                cab_cache[cab_key] = cab_id
                new_cabs += 1

            # --- resolve/create Dp (skip entirely for DEFXXX placeholder) ---
            dp_id = None
            if dp_raw != "DEFXXX":
                dp_key = (cab_id, dp_raw.upper())
                dp_id = dp_cache.get(dp_key)
                if dp_id is None:
                    cur.execute(
                        "INSERT INTO dps (name, code, cab_id, is_active, created_at, updated_at) "
                        "VALUES (%s, %s, %s, 1, NOW(), NOW())",
                        (dp_raw, dp_raw, cab_id),
                    )
                    dp_id = cur.lastrowid
                    dp_cache[dp_key] = dp_id
                    new_dps += 1

            category_id = int(ctype_raw) if ctype_raw else None

            cur.execute(
                "INSERT INTO circuits (code, dp_id, circuit_category_id, is_active, created_at, updated_at) "
                "VALUES (%s, %s, %s, 1, NOW(), NOW())",
                (circuit_code, dp_id, category_id),
            )
            existing_circuit_codes.add(circuit_code)
            new_circuits += 1
            since_commit += 1

            if since_commit >= BATCH_SIZE:
                conn.commit()
                since_commit = 0
                elapsed = time.time() - start
                print(f"  ... {total_rows} rows processed ({new_circuits} new circuits, "
                      f"{new_cabs} new cabs, {new_dps} new dps) in {elapsed:.1f}s")

        conn.commit()  # final partial batch

    elapsed = time.time() - start
    print(f"Circuit stream: {total_rows} rows read, {skipped_existing} already-imported skipped, "
          f"{new_circuits} circuits / {new_cabs} cabs / {new_dps} dps inserted in {elapsed:.1f}s")


def main():
    conn = pymysql.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
        database=DB_NAME, autocommit=False, charset="utf8mb4",
    )
    cur = conn.cursor()
    try:
        import_opmc(cur, conn)
        import_exchange(cur, conn)
        import_circuit_category(cur, conn)
        import_circuit_stream(cur, conn)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()
