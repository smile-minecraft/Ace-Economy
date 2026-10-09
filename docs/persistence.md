# Persistence, backup, and restore

English · [简体中文](persistence.zh-CN.md) · [繁體中文](persistence.zh-TW.md)

Use this guide to choose a storage backend, copy data safely, create a managed snapshot, or restore a known-good v2 snapshot. Where the current live validation has limits, they are called out next to the operation they affect.

## Contents

- [Choose a backend](#choose-a-backend)
- [One server per store](#one-server-per-store)
- [Lock and epoch capacity limits](#lock-and-epoch-capacity-limits)
- [JSON file](#json-file)
- [SQLite file](#sqlite-file)
- [MySQL and MariaDB](#mysql-and-mariadb)
- [Normal-operation safety](#normal-operation-safety)
- [Restore sequence](#restore-sequence)
- [Logical backend boundary](#logical-backend-boundary)
- [When startup fails](#when-startup-fails)

## Choose a backend

AceEconomy stores the same logical v2 data through a JSON file or a SQL backend. JSON and SQLite are local to the plugin data folder; MySQL is a network database configured through `storage.mysql.*`. The storage choice changes the physical location, not the account and transaction concepts described in [Database concepts and upgrades](database.md).

| Backend | Where data lives | Good fit |
| --- | --- | --- |
| JSON | `<plugin-data>/data-v2.json` | A single server that wants one portable file. |
| SQLite | `<plugin-data>/<storage.sqlite.path>` | A single server that wants a local SQL database file. |
| MySQL | The configured database at `host:port/database`. | A single server whose operator already runs a database service. |
| MariaDB | Configure it through `storage.type: mysql`; there is no `mariadb` value. | A single server using a MariaDB service exposed through the MySQL-compatible connection settings. |

The backend is selected at startup. Changing `storage.type` points the plugin at another store; it does not copy old rows or merge two stores.

## One server per store

One store serves one live server. Pointing two servers — or two plugin instances — at the same JSON file, the same SQLite file, or the same MySQL database is not supported.

The backends only coordinate writes inside a single running instance. The JSON backend serializes writes with an instance-local lock and publishes through an atomic file replacement; two writers on the same file silently overwrite each other, and each instance keeps serving its own in-memory view, so balances and transaction history can diverge without an error. The SQL backend has no cross-instance fencing either: concurrent writers can interleave in ways neither instance detects. A SQLite file shared over a network mount adds the mount's own locking behaviour on top, which this plugin does not validate.

> **Warning:** The plugin does not diagnose this misconfiguration at startup. Two servers pointed at one store both start normally; the damage shows up later as missing or contradictory records. Prevent it operationally: one server, one store.

If you run more than one server, give each server its own store (its own file, or its own MySQL database), and move data between servers only through the managed `/aceeco backup` and `/aceeco restore` snapshot flow. Sharing one live store across servers needs its own lock and epoch or fencing design, which is not part of the current storage backends.

## Lock and epoch capacity limits

The instance-local locks hold no entries: they only serialize work inside one running server. What grows over time are the tracking maps next to those locks, plus the durable history. Every bounded map below shares one measured cap, `LockEpochCapacity.MAX_TRACKED_IDS` (100,000 distinct IDs).

| What grows | Grows with | Bound | Past the bound |
| --- | --- | --- | --- |
| Session invalidation generations | Distinct UUIDs ever loaded | 100,000 | A new UUID's load fails fast with `SessionError.CAPACITY_EXCEEDED`; existing sessions keep working. Restart the server to reset tracking. |
| Balance-cache epochs | Distinct owners ever cached | 100,000 | A new owner is served uncached (reads fall through to storage); publishes report `false`. Existing owners keep working. |
| Flush-failure diagnostics | Distinct UUIDs with an unacknowledged flush failure | 100,000 | Recording a further failure throws `SessionError.CAPACITY_EXCEEDED` after the session is already cleaned up. Stale entries are dropped on the next login or a clean quit. |
| SQL connection pool | Concurrent SQL users | `pool-size` (default 10) | Borrowers wait, then fail with an explicit timeout — never silently. |
| 64-bit epoch counters | Invalidations | No cap, by design | No overflow in practice: even one million invalidations per second leaves about 292,000 years of headroom. |

How the cap was set: one `UUID -> counter` holder measures about 75 bytes of heap, so 100,000 tracked IDs cost about 7.5 MB worst case — a small fraction of a server heap. 10,000 tracked session loads (accounts, snapshots, and holders together) fit in 64 MB. One consumed nonce costs about 61 JSON characters. These figures come from the automated capacity tests; rerun them before changing the cap. A restart resets all three tracking maps, which is safe because no in-flight load or stale write survives a restart.

Durable growth is capped by nothing, deliberately: consumed nonces and transaction history are never deleted, because deleting a consumed key would break first-writer-wins and deleting history would destroy audit evidence. Watch the file and table sizes as part of server maintenance, and move data between servers only through the managed backup and restore flow.

Not measured, not claimed: per-row storage cost inside SQLite/MySQL, behaviour of a SQLite file on a network mount, survival of a hardware power cut, and heap behaviour under live player threading. Those remain operational validation items, not guarantees in this guide.

## JSON file

With `storage.type: json`, the plugin creates `data-v2.json` in its data folder when the file does not exist. Each mutation writes a temporary file next to the live file, forces it to storage, then replaces the live file with an atomic rename and syncs the file and its directory, so the live file is always either the previous or the new complete file, never a partially written document. A failed write attempts to clean up its temp file on a best-effort basis; if cleanup fails, or if the process is killed, one may be left behind (temp names never collide with the live file, and leftovers are ignored). The filesystem must support atomic file replacement; otherwise the write fails with an explicit error instead of a silent non-atomic fallback. If the replacement succeeded but the final durability sync could not be confirmed, the failure is reported as already committed (never as a safe retry) and the backend stops accepting writes until the server restarts and the file is verified. These automated tests simulate I/O failures only; they do not cut power and do not prove safety against arbitrary hardware power loss.

The JSON model contains a schema version, accounts with their balances, and transactions. Amounts are represented as decimal strings so their stored value is not changed by floating-point formatting.

### Safe file operations

- Stop the server before copying, replacing, or moving `data-v2.json`.
- Keep the file in the plugin data folder unless the plugin is configured to use another supported backend.
- Keep more than one dated backup and record which configuration used each backup.
- Do not hand-edit balances or transaction records.

## SQLite file

SQLite uses the path in `storage.sqlite.path`, resolved under the plugin data folder. The default is `data-v2.sqlite`. The parser rejects a path that resolves outside that folder, including `..` traversal and an absolute path to another root.

The SQL backend creates the v2 tables when it initializes. Schema creation and a batch of transaction rows use a database transaction. A restart reopens the same file and initializes the existing schema without duplicating the version row.

For a file backup, stop the server first, copy the SQLite file, and keep the copy outside the live plugin directory. Restoring means stopping the server, placing the chosen file at the configured path, and starting the server again.

## MySQL and MariaDB

The `mysql` backend builds `jdbc:mysql://<host>:<port>/<database>` from the YAML values and passes the credentials and pool values to HikariCP. It opens one SQL backend over the resulting connection. The plugin package includes the JDBC drivers it uses; a separate driver file is not part of the server setup described here.

```yaml
storage:
  type: mysql
  mysql:
    host: "db.example.invalid"
    port: 3306
    database: "aceeconomy"
    username: "<database-user>"
    password: "<set-locally>"
    pool-size: 10
    max-lifetime: 1800000
```

`pool-size` must be positive and controls the maximum number of pooled connections. `max-lifetime` must be positive and is measured in milliseconds. These values belong under `storage.mysql`, not as global `storage` keys.

For MariaDB, keep `type: mysql` and provide the MariaDB host, port, database, account, and password. There is no separate `mariadb` branch in the configuration.

## Normal-operation safety

### Writes and transactions

- A single transaction record is either appended or rejected; a duplicate transaction ID is not silently overwritten.
- A batch of records commits as one database transaction. If one record fails, the batch is rolled back.
- Marking a transaction as reverted is safe to repeat for an existing record.
- A process restart reopens the same store; it does not select a different backend or migrate data.

### Managed backup

Use the canonical command to create a logical snapshot while the server is running:

```text
/aceeco backup [label]
```

The command writes a v2 JSON snapshot only under the plugin-controlled `<plugin data folder>/backups` directory. The optional label is restricted to safe filename characters. It creates `<backup-id>.json` with a verified directory handle and `CREATE_NEW`, forces the complete snapshot, and then creates `<backup-id>.ready` with `CREATE_NEW`. The ready marker contains a SHA-256 digest and is the application-level logical commit point; restore requires the marker and a matching, fully validated snapshot. Existing target or marker files are never replaced. The snapshot contains logical accounts, balances, transactions, reverted markers, and consumed nonces; it does not contain database passwords or webhook URLs. Keep the `.json` and matching `.ready` files together when moving a snapshot; a bare JSON file is not committed.

There are no separate `/backup` or `/restore` root commands. These operations exist as `/aceeco` admin subcommands. Command details and permissions are listed in [Commands and permissions](commands.md).

## Restore sequence

> **Warning:** Restore is destructive. A malformed or incompatible snapshot must not be used as a reason to delete live data.

Run the exact command below from the console:

```text
/aceeco restore <backup-id> confirm
```

The command requires `aceeconomy.admin` and `aceeconomy.admin.restore`, rejects any online player, and accepts only lowercase `confirm`. Before live data is touched, the service performs a read-only preflight for JSON shape, schema version, account and transaction records, duplicate transaction IDs, amounts and timestamps, and compatibility with the configured currencies. It then creates a safety backup of the current state. If the safety backup fails, the restore stops and live state is left untouched.

After preflight and the safety backup pass, JSON restore reads the already committed marker/target pair through a secure directory handle; the marker protocol is not an OS atomic rename or hard-link claim. SQLite/MySQL restore runs as one JDBC transaction. A backend failure is reported without claiming unchanged live state. On success, the leaderboard cache is cleared, but sessions and GUIs are not hot-refreshed; restart the server before players return.

## Logical backend boundary

The same v2 JSON model is used for logical backup and restore across JSON, SQLite, and MySQL. This supports logical moves such as JSON→JSON, SQLite→JSON→SQLite, and the corresponding MySQL logical path. It is not a MySQL server-native dump: it does not replace `mysqldump`, `mariadb-dump`, or a database administrator's physical/disaster-recovery process.

Automated round-trip coverage confirms JSON→JSON and SQLite→JSON→SQLite, including accounts, balances, transactions, reverted markers, and consumed nonces, plus backend rollback on restore failure. No live MySQL connection or live Folia backup/restore proof has been completed; those remain operational validation items rather than claims made by this document.

## When startup fails

- Check that `storage.type` is one of the three accepted values.
- For SQLite, check that `storage.sqlite.path` is a YAML map containing `path`, and that the resolved path stays inside the plugin data folder.
- For MySQL or MariaDB, check host, port, database, username, password, and network access.
- A schema version mismatch is not repaired by changing the backend name. Take a backup, then follow the v2 upgrade guidance in [Database concepts and upgrades](database.md).
