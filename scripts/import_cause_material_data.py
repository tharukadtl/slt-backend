"""import_cause_material_data.py — loads docs/master-data/ Cause/Material CSVs
into the fieldops DB.

Pipeline: TypeOfFault -> CauseCategory -> CauseOfFault (1:1 hierarchy mirror,
new tables), MaterialCategory -> MaterialSubCategory (reuses the existing
material_categories table via parent_id), Material (sku = real MATERIALCODE
+ richer ERP/brand/measurement metadata), MaterialCause_ (resolved via
row-position, see the large warning below -- read this before touching
anything in step 4).

Confirmed decisions applied (see docs/QA_Compliance_Consolidated_Report.md,
"Cause/Material hierarchy import"):
  - The 36 Material rows whose MATERIALSUBCATEGORYCODE has no matching
    MaterialSubCategory row  -> imported anyway, category_id NULL, flagged
                                 in the run's printed report (not held back).
  - IVUDBACLSB typo (row for MATERIALCODE 1VUDDASB, "STEEL BUCKLE") -> fixed
    directly in docs/master-data/MATERIAL.csv to 1VUDBACLSB (matching its
    sibling rows' naming, e.g. 1VUDBACLSS) before this script ever reads it.
    Logged here too since a typo fix belongs on record wherever the data is
    read, not only in the QA report: the corrected code STILL has no match
    in MaterialSubCategory.csv today (it's one of the 36 above either way)
    -- the fix is a data-hygiene correction, not something that changes
    this run's FK-resolution outcome.
  - *-FTTH service variants (V-VOICE FTTH, BB-INTERNET FTTH, E-IPTV FTTH)
    -> mapped to their underlying service's Fault category (PHONE/INTERNET/
    TV respectively) -- the medium is fiber, but the service is the same
    voice/data/tv service riding over it. AB-FTTH (bearer-only, no specific
    service riding over it) -> FIBER. SERVICETYPE.csv itself is NOT
    imported as a table (nothing in fieldops references SERVICETYPECODE
    today) -- SERVICE_TYPE_CATEGORY_MAP below exists purely to verify/report
    the mapping this run, per the confirmed design.

════════════════════════════════════════════════════════════════════════════
 ⚠️  MaterialCause_.csv's CAUSECODE/MATERIALCODE ARE NOT FOREIGN KEYS BY
     VALUE. READ THIS BEFORE CHANGING ANYTHING IN THIS FILE.
════════════════════════════════════════════════════════════════════════════
 MaterialCause_.csv has two columns named CAUSECODE and MATERIALCODE, and
 CauseOfFault.csv / Material.csv each have a column with the exact same
 name -- but they are NOT the same kind of value. CauseOfFault.CAUSECODE
 and Material.MATERIALCODE are real alphanumeric business keys ("BAQX",
 "ADWPPVWI"). MaterialCause_.CAUSECODE and MaterialCause_.MATERIALCODE are
 small integers ("44", "48"...).

 Direct value-matching between them resolves ZERO of the 62 rows, in either
 direction -- confirmed by direct investigation before this script was
 written. The NEW_CAUF_ID / NEW_MAT_ID surrogate-key columns visible in
 CauseOfFault.csv / Material.csv are NOT the answer either: NEW_CAUF_ID is
 blank in all 869 CauseOfFault rows, and NEW_MAT_ID is only populated for
 144/990 Material rows with just the values 0/1 (a flag, not an id).

 What actually resolves them -- verified across ALL 62 rows, not a sample,
 every single pair semantically sensible (drop-wire faults pair with
 drop-wire materials, MDF-jumper faults pair with MDF materials, etc.):
 MaterialCause_.CAUSECODE/MATERIALCODE are 1-INDEXED ROW POSITIONS into
 CauseOfFault.csv / Material.csv's ORIGINAL EXPORT ROW ORDER. Row 19 of
 CauseOfFault.csv (1-indexed, header excluded) is cause code BARO
 ("601-FAULTY DROP WIRE"); MaterialCause_ row CAUSECODE=19 means THAT row,
 not "the row whose CAUSECODE column equals the string '19'".

 THIS IS FRAGILE BY CONSTRUCTION, NOT A CHOICE THIS SCRIPT MADE:
   - It ONLY works if CauseOfFault.csv and Material.csv are read in their
     EXACT original row order. Any re-sort, any filter, any different CSV
     library that reorders rows, any header-row miscount -- and every
     single material_cause pairing silently resolves to the WRONG cause or
     the WRONG material. There is no error, no exception, no orphan row to
     notice -- it just points at a different, still-valid-looking row.
   - This script resolves position -> REAL DATABASE ID exactly once, in
     this same run, immediately after importing CauseOfFault/Material, and
     writes ONLY the resolved real ids to material_cause. The raw
     MaterialCause_.CAUSECODE/MATERIALCODE integers are never written to
     the database anywhere, by design -- so nothing downstream of this
     script can ever be tempted to treat them as real ids later.
   - If CauseOfFault.csv or Material.csv is ever re-exported, edited, or
     regenerated in a different row order, this script's material_cause
     resolution WILL BE WRONG the next time it runs (though CauseOfFault/
     Material import themselves stay correct, since those are matched by
     real code, not position) -- there is no way for this script to detect
     that the source row order changed. If you are the next person editing
     either of those two CSVs: do not reorder existing rows. Append new
     rows at the end, or re-run against a source you know preserves order.
════════════════════════════════════════════════════════════════════════════

Idempotent by natural key on every table (existing codes/skus loaded into
in-memory sets before insert, matching import_master_data.py's convention)
-- safe to re-run. The row-position resolution step re-derives its position
maps from the CSVs fresh on every run (not from what happened to get
inserted this run), so a partial-then-resumed run still resolves correctly
as long as the two source files' row order hasn't changed since the first
run (see the warning above).

Usage: python fieldops/scripts/import_cause_material_data.py
Run from the repo root (docs/master-data/ resolved relative to cwd).
"""

import csv
import os
import sys

import pymysql

MASTER_DATA_DIR = os.path.join("docs", "master-data")

DB_HOST = os.environ.get("SPRING_DATASOURCE_HOST", "localhost")
DB_PORT = int(os.environ.get("SPRING_DATASOURCE_PORT", "3306"))
DB_NAME = os.environ.get("SPRING_DATASOURCE_DB", "slt_fieldops_db")
DB_USER = os.environ.get("SPRING_DATASOURCE_USERNAME", "root")
DB_PASSWORD = os.environ.get("SPRING_DATASOURCE_PASSWORD", "1234")

# Confirmed FTTH-mapping decision (see module docstring). Not persisted as a
# table -- exists only to verify/report the mapping this run.
SERVICE_TYPE_CATEGORY_MAP = {
    "V-VOICE COPPER": "PHONE", "V-VOICE CAB": "PHONE", "V-VOICE": "PHONE",
    "V-VOICE FTTH": "PHONE", "PSTN": "PHONE",
    "BB-INTERNET COPPER": "INTERNET", "BB-INTERNET CAB": "INTERNET", "BB-INTERNET": "INTERNET",
    "BB-INTERNET FTTH": "INTERNET", "ADSL": "INTERNET", "AB-WIRELESS ACCESS": "INTERNET",
    "E-IPTV COPPER": "TV", "E-IPTV CAB": "TV", "E-IPTV": "TV", "E-IPTV FTTH": "TV",
    "AB-FTTH": "FIBER",
    "CAB": "OTHER", "AB-CAB": "OTHER", "DIG-SMART HOME": "OTHER",
    "D-VALUE VPN": "OTHER", "D-DAB": "OTHER", "D-BIL": "OTHER",
}


def read_csv_all(path, encoding="latin1"):
    with open(path, newline="", encoding=encoding) as f:
        reader = csv.reader(f)
        header = next(reader)
        rows = list(reader)
    return header, rows


def idx_of(header, *names):
    return {n: header.index(n) for n in names}


def import_type_of_fault(cur):
    header, rows = read_csv_all(os.path.join(MASTER_DATA_DIR, "TYPEOFFAULT.csv"))
    idx = idx_of(header, "TYPECODE", "DESCRIPTION", "SORTKEY")
    cur.execute("SELECT type_code FROM type_of_fault")
    existing = {r[0] for r in cur.fetchall()}
    inserted = 0
    for row in rows:
        code = row[idx["TYPECODE"]].strip()
        if code in existing:
            continue
        cur.execute(
            "INSERT INTO type_of_fault (type_code, description, sort_key, created_at, updated_at) "
            "VALUES (%s, %s, %s, NOW(6), NOW(6))",
            (code, row[idx["DESCRIPTION"]].strip(), int(row[idx["SORTKEY"]] or 0)),
        )
        existing.add(code)
        inserted += 1
    print(f"type_of_fault: {inserted} inserted, {len(rows) - inserted} already present, {len(rows)} source rows")
    return len(rows)


def import_cause_category(cur):
    header, rows = read_csv_all(os.path.join(MASTER_DATA_DIR, "CAUSECATEGORY.csv"))
    idx = idx_of(header, "CAUSECATEGORYCODE", "DESCRIPTION", "TYPECODE", "SORTKEY")

    cur.execute("SELECT type_code, id FROM type_of_fault")
    type_id_by_code = {r[0]: r[1] for r in cur.fetchall()}

    cur.execute("SELECT cause_category_code FROM cause_category")
    existing = {r[0] for r in cur.fetchall()}

    inserted, orphaned = 0, []
    for row in rows:
        code = row[idx["CAUSECATEGORYCODE"]].strip()
        if code in existing:
            continue
        type_code = row[idx["TYPECODE"]].strip()
        type_id = type_id_by_code.get(type_code)
        if type_id is None:
            orphaned.append((code, type_code))
        cur.execute(
            "INSERT INTO cause_category (cause_category_code, description, type_of_fault_id, sort_key, created_at, updated_at) "
            "VALUES (%s, %s, %s, %s, NOW(6), NOW(6))",
            (code, row[idx["DESCRIPTION"]].strip(), type_id, int(row[idx["SORTKEY"]] or 0)),
        )
        existing.add(code)
        inserted += 1
    print(f"cause_category: {inserted} inserted, {len(rows) - inserted} already present, {len(rows)} source rows, {len(orphaned)} orphaned TYPECODE")
    if orphaned:
        print(f"  orphaned: {orphaned}")
    return len(rows)


def import_cause_of_fault(cur):
    header, rows = read_csv_all(os.path.join(MASTER_DATA_DIR, "CAUSEOFFAULT.csv"))
    idx = idx_of(header, "CAUSECODE", "DESCRIPTION", "CAUSECATEGORYCODE", "CLARITYCODE",
                 "SORTKEY", "COPPER", "FTTH", "LTE")

    cur.execute("SELECT cause_category_code, id FROM cause_category")
    cat_id_by_code = {r[0]: r[1] for r in cur.fetchall()}

    cur.execute("SELECT cause_code FROM cause_of_fault")
    existing = {r[0] for r in cur.fetchall()}

    inserted, orphaned = 0, []
    for row in rows:
        code = row[idx["CAUSECODE"]].strip()
        if code in existing:
            continue
        cat_code = row[idx["CAUSECATEGORYCODE"]].strip()
        cat_id = cat_id_by_code.get(cat_code)
        if cat_id is None:
            orphaned.append((code, cat_code))
        cur.execute(
            "INSERT INTO cause_of_fault "
            "(cause_code, description, cause_category_id, clarity_description, "
            " applies_copper, applies_ftth, applies_lte, sort_key, created_at, updated_at) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, NOW(6), NOW(6))",
            (
                code, row[idx["DESCRIPTION"]].strip(), cat_id, row[idx["CLARITYCODE"]].strip(),
                row[idx["COPPER"]].strip() == "1", row[idx["FTTH"]].strip() == "1",
                row[idx["LTE"]].strip() == "1", int(row[idx["SORTKEY"]] or 0),
            ),
        )
        existing.add(code)
        inserted += 1
    print(f"cause_of_fault: {inserted} inserted, {len(rows) - inserted} already present, {len(rows)} source rows, {len(orphaned)} orphaned CAUSECATEGORYCODE")
    if orphaned:
        print(f"  orphaned: {orphaned}")
    return len(rows)


def import_material_categories(cur):
    """MaterialCategory.csv (top-level, parent_id NULL) then MaterialSubCategory.csv
    (children, parent_id -> the just-imported top-level row)."""
    header, rows = read_csv_all(os.path.join(MASTER_DATA_DIR, "MATERIALCATEGORY.csv"))
    idx = idx_of(header, "MATERIALCATEGORYCODE", "DESCRIPTION")

    cur.execute("SELECT code FROM material_categories WHERE code IS NOT NULL")
    existing = {r[0] for r in cur.fetchall()}

    inserted = 0
    for row in rows:
        code = row[idx["MATERIALCATEGORYCODE"]].strip()
        if code in existing:
            continue
        cur.execute(
            "INSERT INTO material_categories (name, code, description, parent_id, is_active, created_at) "
            "VALUES (%s, %s, %s, NULL, 1, NOW())",
            (row[idx["DESCRIPTION"]].strip() or code, code, row[idx["DESCRIPTION"]].strip()),
        )
        existing.add(code)
        inserted += 1
    print(f"material_categories (top-level): {inserted} inserted, {len(rows) - inserted} already present, {len(rows)} source rows")

    header2, rows2 = read_csv_all(os.path.join(MASTER_DATA_DIR, "MATERIALSUBCATEGORY.csv"))
    idx2 = idx_of(header2, "MATERIALSUBCATEGORYCODE", "DESCRIPTION", "MATERIALCATEGORYCODE")

    cur.execute("SELECT code, id FROM material_categories WHERE code IS NOT NULL")
    cat_id_by_code = {r[0]: r[1] for r in cur.fetchall()}

    inserted2, orphaned = 0, []
    for row in rows2:
        code = row[idx2["MATERIALSUBCATEGORYCODE"]].strip()
        if code in cat_id_by_code:
            continue
        parent_code = row[idx2["MATERIALCATEGORYCODE"]].strip()
        parent_id = cat_id_by_code.get(parent_code)
        if parent_id is None:
            orphaned.append((code, parent_code))
        cur.execute(
            "INSERT INTO material_categories (name, code, description, parent_id, is_active, created_at) "
            "VALUES (%s, %s, %s, %s, 1, NOW())",
            (row[idx2["DESCRIPTION"]].strip() or code, code, row[idx2["DESCRIPTION"]].strip(), parent_id),
        )
        cat_id_by_code[code] = cur.lastrowid
        inserted2 += 1
    print(f"material_categories (subcategory): {inserted2} inserted, {len(rows2) - inserted2} already present, {len(rows2)} source rows, {len(orphaned)} orphaned MATERIALCATEGORYCODE")
    if orphaned:
        print(f"  orphaned: {orphaned}")
    return len(rows), len(rows2)


def import_materials(cur):
    header, rows = read_csv_all(os.path.join(MASTER_DATA_DIR, "MATERIAL.csv"))
    idx = idx_of(header, "MATERIALCODE", "DESCRIPTION", "MATERIALSUBCATEGORYCODE",
                 "MEASUREMENTCODE", "ERPCODE", "ERPDESCRIPTION", "BRAND")

    cur.execute("SELECT code, id FROM material_categories WHERE code IS NOT NULL")
    subcat_id_by_code = {r[0]: r[1] for r in cur.fetchall()}

    cur.execute("SELECT sku FROM materials")
    existing = {r[0] for r in cur.fetchall()}

    inserted = 0
    null_subcategory_rows = []
    for row in rows:
        sku = row[idx["MATERIALCODE"]].strip()
        if sku in existing:
            continue
        subcat_code = row[idx["MATERIALSUBCATEGORYCODE"]].strip()
        subcat_id = subcat_id_by_code.get(subcat_code)
        if subcat_id is None:
            null_subcategory_rows.append((sku, subcat_code))
        cur.execute(
            "INSERT INTO materials "
            "(sku, name, description, category_id, unit, current_stock, minimum_threshold, "
            " charge_type, unit_price, status, is_active, stock_status, erp_code, "
            " erp_description, brand, measurement_code, created_at, updated_at) "
            "VALUES (%s, %s, %s, %s, %s, 0, 5, 'FOC', 0, 'ACTIVE', 1, 'OUT_OF_STOCK', "
            "        %s, %s, %s, %s, NOW(), NOW())",
            (
                sku, row[idx["DESCRIPTION"]].strip() or sku, row[idx["DESCRIPTION"]].strip(), subcat_id,
                row[idx["MEASUREMENTCODE"]].strip() or "pieces",
                row[idx["ERPCODE"]].strip() or None, row[idx["ERPDESCRIPTION"]].strip() or None,
                row[idx["BRAND"]].strip() or None, row[idx["MEASUREMENTCODE"]].strip() or None,
            ),
        )
        existing.add(sku)
        inserted += 1
    print(f"materials: {inserted} inserted, {len(rows) - inserted} already present, {len(rows)} source rows, "
          f"{len(null_subcategory_rows)} imported with category_id NULL (orphaned MATERIALSUBCATEGORYCODE, not held back)")
    if null_subcategory_rows:
        print(f"  null-subcategory rows: {null_subcategory_rows}")
    return len(rows)


def import_material_cause(cur):
    """Resolves MaterialCause_.csv's row-position CAUSECODE/MATERIALCODE into real
    cause_of_fault.id / materials.id. See the module docstring's large warning --
    this is the one step in this script where getting the source row order wrong
    produces silently-wrong data, not an error."""
    causeoffault_header, causeoffault_rows = read_csv_all(os.path.join(MASTER_DATA_DIR, "CAUSEOFFAULT.csv"))
    causeoffault_code_idx = causeoffault_header.index("CAUSECODE")
    # position (1-indexed, matching MaterialCause_'s own convention) -> real cause_of_fault.id
    cur.execute("SELECT cause_code, id FROM cause_of_fault")
    cause_id_by_code = {r[0]: r[1] for r in cur.fetchall()}
    cause_id_by_position = {
        i + 1: cause_id_by_code[row[causeoffault_code_idx].strip()]
        for i, row in enumerate(causeoffault_rows)
        if row[causeoffault_code_idx].strip() in cause_id_by_code
    }

    material_header, material_rows = read_csv_all(os.path.join(MASTER_DATA_DIR, "MATERIAL.csv"))
    material_code_idx = material_header.index("MATERIALCODE")
    cur.execute("SELECT sku, id FROM materials")
    material_id_by_sku = {r[0]: r[1] for r in cur.fetchall()}
    material_id_by_position = {
        i + 1: material_id_by_sku[row[material_code_idx].strip()]
        for i, row in enumerate(material_rows)
        if row[material_code_idx].strip() in material_id_by_sku
    }

    header, rows = read_csv_all(os.path.join(MASTER_DATA_DIR, "MATERIALCAUSE_.csv"))
    idx = idx_of(header, "CAUSECODE", "MATERIALCODE", "SORTKEY")

    cur.execute("SELECT cause_id, material_id FROM material_cause")
    existing = {(r[0], r[1]) for r in cur.fetchall()}

    inserted, unresolved = 0, []
    for row in rows:
        cause_pos = int(row[idx["CAUSECODE"]].strip())
        material_pos = int(row[idx["MATERIALCODE"]].strip())
        real_cause_id = cause_id_by_position.get(cause_pos)
        real_material_id = material_id_by_position.get(material_pos)
        if real_cause_id is None or real_material_id is None:
            unresolved.append((cause_pos, material_pos))
            continue
        if (real_cause_id, real_material_id) in existing:
            continue
        cur.execute(
            "INSERT INTO material_cause (cause_id, material_id, sort_key, created_at, updated_at) "
            "VALUES (%s, %s, %s, NOW(6), NOW(6))",
            (real_cause_id, real_material_id, int(row[idx["SORTKEY"]] or 0)),
        )
        existing.add((real_cause_id, real_material_id))
        inserted += 1
    print(f"material_cause: {inserted} inserted, {len(rows) - inserted - len(unresolved)} already present, "
          f"{len(rows)} source rows, {len(unresolved)} unresolved")
    if unresolved:
        print(f"  unresolved (position, position): {unresolved}")
    return len(rows)


def report_service_type_mapping():
    header, rows = read_csv_all(os.path.join(MASTER_DATA_DIR, "SERVICETYPE.csv"))
    idx = idx_of(header, "SERVICETYPECODE")
    print("\nSERVICETYPECODE -> Fault.category mapping (report only, not persisted):")
    unmapped = []
    for row in rows:
        code = row[idx["SERVICETYPECODE"]].strip()
        mapped = SERVICE_TYPE_CATEGORY_MAP.get(code)
        print(f"  {code!r:24} -> {mapped}")
        if mapped is None:
            unmapped.append(code)
    if unmapped:
        print(f"  UNMAPPED: {unmapped}")
    return len(rows), len(unmapped)


def main():
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
                            database=DB_NAME, charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            import_type_of_fault(cur)
            import_cause_category(cur)
            import_cause_of_fault(cur)
            import_material_categories(cur)
            import_materials(cur)
            import_material_cause(cur)
        conn.commit()
        report_service_type_mapping()
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
