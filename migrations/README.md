# `migrations/` — two directories, deliberately different rules

This project has no Flyway/Liquibase. `spring.jpa.hibernate.ddl-auto=update` handles ordinary schema
growth (new columns/tables from the current entity mapping) automatically, on every environment,
including a fresh Testcontainers instance. It **never** renames, drops, backfills, or reconciles a
column an entity used to map and no longer does — that gap is what everything in this folder exists
to close.

## `manual/` — hand-run, one-time, never auto-applied (12 files)

Every script here documents a historical transformation applied, by hand, against the live dev
database (`slt_fieldops_db`) at a specific point in time — a rename, a backfill, a constraint
rescoping, a brand-new lookup table. Each one is safe to *read* forever (they're the audit trail of
how the schema got where it is), but **none of them are safe to blindly re-run against a fresh
database**, and this was checked directly, not assumed (2026-09-03 investigation,
`QA_Compliance_Consolidated_Report.md` §4 §K):

- 9 of the 12 (every `ADD COLUMN`/`CREATE TABLE`/`DROP`/`RENAME` script) **fail outright** against a
  database built fresh from the current entity mappings — `opmc_rename.sql`'s whole premise, for
  example, is a `branches` table and a half-migrated `ddl-auto`-created `opmcs` table coexisting,
  a state that only ever existed on the live dev DB at one specific moment and that a fresh build
  never produces. `RENAME TABLE branches TO opmcs` on a fresh database simply errors — there is no
  `branches` table to rename.
- The remaining 3 (`MODIFY COLUMN ... ENUM(...)`/`MODIFY COLUMN ... DATETIME(6)`) are technically
  harmless to re-run, but pointless — the entities they target have long since caught up, and
  `ddl-auto=update` already generates the same end state fresh.

In short: these scripts did real, necessary work against a live, already-evolved database, once. They
are not a migration history a fresh database can replay. **If you need to stand up a schema from
scratch (a new environment, a DR rebuild, a fresh Testcontainers run), `ddl-auto=update` plus the
current entity mappings alone already produces the correct end state for everything in this folder
except what's in `auto/` below.**

## `auto/` — auto-applied at every test startup, idempotency-safe by convention (1 file so far)

A different, narrower class of gap: columns (or constraints) that exist on the live database but
that **no current entity maps at all** — not a lagging schema waiting for `ddl-auto` to catch up, but
orphaned state `ddl-auto` will *never* create, on any database, ever, because nothing in the Java code
references it. `kpi_targets_legacy_schema_columns.sql` is the first instance: 13 columns from an
earlier `KpiTarget` design that the current entity dropped from its mapping but that the live database
(and the tests that exercise that legacy shape directly, via native SQL) still needs.

This class of gap is different in one important way: a fresh Testcontainers instance needs it too,
automatically, with no manual step — a CI run should not require someone to remember to run a SQL
script by hand. So files here are wired into `src/test/resources/application.yml`:

```yaml
spring:
  jpa:
    defer-datasource-initialization: true   # run after ddl-auto builds the base schema, not before
  sql:
    init:
      mode: always                          # Testcontainers' JDBC URL isn't Boot's "embedded", so the
                                             # default (embedded-only) would silently skip this folder
      continue-on-error: true                # see "why continue-on-error is safe here" below
      schema-locations: file:migrations/auto/*.sql
```

**Why `continue-on-error: true` is safe here, specifically.** MySQL 8.0 has no `ADD COLUMN`/`ADD KEY
IF NOT EXISTS` (confirmed empirically against a live 8.0.45 instance — all three forms tried are a
syntax error, unlike MariaDB). A test suite creates many distinct Spring context caches (different
`@MockBean` sets, different security configuration per test class), and each one triggers its own
datasource initialization against the *same* underlying Testcontainers database. Without
`continue-on-error`, the second context's re-application of an already-applied `ALTER TABLE` aborts
that context's boot entirely — this is exactly the self-inflicted regression this project hit on
2026-09-03 (a false, cascading `386 run / 53 failures / 275 errors` result, diagnosed and reverted;
see the QA report). With it: the first context to boot applies the file for real; every subsequent one
hits a "duplicate column"/"duplicate key" error on the identical statements and is silently, correctly
skipped, because that is the only error a repeat application of this file can ever throw.

**That safety property is the contract for anything else placed in `auto/`, not a one-off exception.**
Before adding a new file here, confirm every statement in it can *only* ever fail with an
already-exists-shaped error on a second application — plain `ADD COLUMN`/`ADD KEY`/`ADD CONSTRAINT`
statements qualify; anything with a data `UPDATE`, a `RENAME`, or a `DROP` almost certainly does not,
and belongs in `manual/` instead, run by hand, exactly like its 12 predecessors.

## Deciding where a new migration goes

- **Does an entity map this column/table, but the schema hasn't caught up yet?** It doesn't need a
  script at all — restart the app (or a fresh Testcontainers run), `ddl-auto=update` handles it.
- **Is this a rename, a backfill, a constraint narrowing against real existing data, or anything that
  assumes a specific already-drifted starting state?** `manual/` — write it, run it once by hand
  against the real target database, document what you verified before running it (every existing
  script in `manual/` follows this discipline — read a couple for the pattern).
- **Is this a column/table an entity used to map and no longer does, that a fresh database would
  otherwise never have?** `auto/` — write it so every statement's only possible re-application failure
  is "already exists," and it will auto-apply to every test run (local and CI) with no manual step.

If this class of gap (`auto/`) keeps recurring, or the `manual/` runbook keeps growing, that's the
signal to revisit adopting a real migration tool (Flyway/Liquibase) — deliberately not done now, since
most of `manual/`'s 12 scripts assume ad-hoc, `ddl-auto`-drifted intermediate states a clean
version-tracked migration history would never produce, converting them would be closer to rewriting
this project's schema history from scratch than porting a file format, and there's no multi-environment
deployment pressure yet that makes the payoff worth that cost. See `QA_Compliance_Consolidated_Report.md`
§4 §K for the full investigation.
