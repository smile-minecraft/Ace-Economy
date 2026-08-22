# v2 Persistence, Backup & Restore

This document covers the v2 persistence layer introduced alongside the AceLib rewrite. It is
independent of the legacy v1 schema described in `database.md`.

The v2 layer is built around three ports (`AccountRepository`, `TransactionRepository`,
`PersistenceLifecycle`) with two interchangeable backends:

| Backend | Class | Storage | Use case |
|---------|-------|---------|----------|
| JSON file | `infrastructure.persistence.json.JsonPersistenceBackend` | Single atomic JSON file | Default / lightweight / Folia-local |
| SQL | `infrastructure.persistence.sql.SqlBackend` | SQLite or MySQL via JDBC | Shared / multi-node via MySQL |

Both backends share the same transaction boundary guarantees (see below) and the same
`backup()` / `restore()` snapshot format (a v2 JSON model).

---

## Transaction boundary guarantees

- **Append is atomic per record.** `append` writes one record; a duplicate transaction id is
  rejected (it throws) rather than silently overwritten, so the audit log stays trustworthy.
- **Batch is all-or-none.** `appendBatch` writes every record inside one database transaction;
  if any record fails (e.g. a duplicate id), the whole batch is rolled back and none of the
  records become visible.
- **Rollback marker is idempotent.** `markReverted` sets the `reverted` flag; calling it again on
  an existing record is safe, and calling it on an unknown id throws.
- **Backup/restore never destroys live data on a bad input.** `restore` fully parses and validates
  the incoming snapshot (JSON well-formedness + schema version) *before* any live row is deleted.
  A corrupt or schema-incompatible backup raises an error and leaves the current data untouched.

---

## Fresh install

On first start with an empty location:

1. `initialize()` creates the schema (SQL: `CREATE TABLE IF NOT EXISTS` for the four v2 tables;
   JSON: writes an empty model file).
2. `schemaVersion()` returns `1` once initialized.
3. `needsRecreation()` returns `false` when no v2 tables exist at all (a clean fresh install).

No manual setup is required for SQLite or the JSON file backend.

---

## Restart / reload

`initialize()` is idempotent: re-running it on an already-initialized store does not error and
does not duplicate data (`IF NOT EXISTS` DDL, `INSERT OR IGNORE` / `INSERT IGNORE` for the version
row, and `REPLACE INTO` for accounts/balances). A process restart simply re-opens the same file or
database and re-initializes; previously persisted accounts and transactions are visible
immediately.

---

## Backup and restore

`backup(OutputStream)` serializes the entire v2 model (accounts, balances, transactions) to the
same JSON format used by the JSON file backend. `restore(InputStream)` replaces the live data with
the snapshot:

1. The snapshot is parsed and validated (must be well-formed JSON with `schemaVersion == 1`).
2. If validation fails, `restore` throws and the live data is left intact.
3. On success, all live rows are deleted and re-inserted from the snapshot inside one transaction.

This makes the JSON-file backend and the SQL backend mutually portable: a SQL backup can be
restored into a JSON backend and vice versa, as long as both are v2.

### Failure cases that are safe

| Input | Result |
|-------|--------|
| Corrupt / non-JSON bytes | `PersistenceException`, live data untouched |
| `schemaVersion` other than `1` | `PersistenceException`, live data untouched |
| Restore into an uninitialized store | throws before touching data (tables must exist) |

---

## Schema recreation and initialization failure

- **Partial initialization is detected.** If a v2 data table exists but the schema-version table
  does not (e.g. a crash left a half-created schema), `needsRecreation()` returns `true` so the
  operator can recover instead of booting on broken tables.
- **`truncateAndRecreate()`** drops all four v2 tables and recreates them, returning the store to
  an empty-but-initialized state (`schemaVersion()` is `1`, `loadAll()` is empty).
- **Schema version mismatch.** `initialize()` refuses to load a store whose `schemaVersion` is
  incompatible; call `truncateAndRecreate()` (after taking a backup) to start fresh. The JSON
  backend reports this through `needsRecreation()` / `initialize()` as well.

---

## MySQL runtime setup

The SQL backend targets MySQL with the `MySqlDialect`. The generated DDL pins
`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`, uses `VARCHAR(36)` for UUID columns and `BOOLEAN` for the
reverted flag, and writes the version row with `INSERT IGNORE`.

To run against MySQL you configure it through the v2 config surface; no manual JDBC wiring is
required:

1. Provision a MySQL 8+ database and a dedicated user.
2. Set `storage.type: mysql` in `config.yml` and fill in the `storage.mysql.*` settings (JDBC URL,
   credentials, and HikariCP pool options). The MySQL JDBC driver is **shaded into the plugin JAR**
   — it is an `implementation` dependency of `build.gradle.kts`, preserved by `mergeServiceFiles()`
   and the `minimize` exclusions — so the operator does **not** drop a driver JAR into `plugins/`.
3. `CompositionRoot` reads `storage.mysql.*` via `StorageConfigParser` and
   `PersistenceBackendFactory` builds the `SqlBackend` + `MySqlDialect` (with the HikariCP
   `DataSource`) at startup. You do **not** supply a `Connection` manually.

> **Test status — live MySQL not executed.** The MySQL path is covered by *offline, deterministic*
> contract tests only: `V2Schema.ddlStatements(mySqlDialect)` and `versionInsertSql` are asserted to
> produce InnoDB / `VARCHAR` / `BOOLEAN` DDL with `INSERT IGNORE`, and `SchemaVersion.isCompatible`
> is verified. **No test connects to a live MySQL server** — the DDL has not been executed against a
> real MySQL instance in this repository's CI or local runs. Validate the DDL against your target
> MySQL version before production use.

---

## SQLite (tested)

SQLite is exercised by real, offline JDBC tests (`SqlBackendContractTest`) using a temporary
on-disk database: fresh create, restart/reload, account and transaction round-trips, atomic batch
with rollback on duplicate, rollback marker, duplicate-append rejection, backup/restore round-trip,
corrupt-backup safety, schema-version-mismatch rejection, partial-init detection, and
`truncateAndRecreate`. The SQLite DDL uses `TEXT`/`INTEGER` storage and `INSERT OR IGNORE` for the
version row.
