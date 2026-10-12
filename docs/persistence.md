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

When `storage.type: json` and the target file does not exist, the plugin creates `data-v2.json`. Every state mutation strictly follows a Copy-on-Write strategy:
1. **Staged write and forced sync:** A temporary file is created next to the live file, populated with the complete state snapshot, and flushed to disk via `fsync`.
2. **Atomic rename:** The temporary file replaces the live file using an atomic move operation, syncing directory metadata. The live file always represents a valid snapshot—never a partially written corrupted state.
3. **Error handling and recovery:** Aborted writes clean up temporary files on a best-effort basis; if the filesystem lacks atomic rename support, the operation fails immediately rather than falling back to an unsafe non-atomic write. If the atomic rename succeeds but the durability sync cannot be confirmed, the transaction is marked as committed and write operations are halted to prevent state split, requiring a server restart and manual verification.

*Note: Automated tests simulate I/O failures; they do not test arbitrary hardware power loss.*

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

The command writes a v2 JSON snapshot only under the plugin-controlled `<plugin data folder>/backups` directory. The optional label is restricted to safe filename characters. It creates `<backup-id>.json` with a verified directory handle and `CREATE_NEW`, forces the complete snapshot, and then creates `<backup-id>.ready` with `CREATE_NEW`. The ready marker contains a SHA-256 digest and is the application-level logical commit point; restore requires the marker and a matching, fully validated snapshot. Existing target or marker files are never replaced. The snapshot contains logical accounts, balances, transactions, reverted markers, consumed nonces, and managed banknote issuance progress; it does not contain database passwords or webhook URLs. Keep the `.json` and matching `.ready` files together when moving a snapshot; a bare JSON file is not committed.

There are no separate `/backup` or `/restore` root commands. These operations exist as `/aceeco` admin subcommands. Command details and permissions are listed in [Commands and permissions](commands.md).

## Restore sequence

> **Warning:** Restore is destructive. A malformed or incompatible snapshot must not be used as a reason to delete live data.

Run the exact command below from the console:

```text
/aceeco restore <backup-id> confirm
```

The command requires `aceeconomy.admin` and `aceeconomy.admin.restore`, rejects any online player, and accepts only lowercase `confirm`. Before live data is touched, the service performs a read-only preflight for JSON shape, schema version, account and transaction records, duplicate transaction IDs, amounts and timestamps, and compatibility with the configured currencies. It then creates a safety backup of the current state. If the safety backup fails, the restore stops and live state is left untouched.

The managed issuance guard then checks the live issuance records against the candidate. A candidate without issuance progress (a backup written before issuance tracking) may only replace live progress that cannot leave a redeemable note behind (deduction-free records, or redeemed or aborted records), which is the supported migration rollback path. A live delivered, possibly-delivered, refunded-voided or undecidable record refuses the empty candidate, so the balance cannot revert while a physical note stays or becomes redeemable again.

A live delivered or possibly-delivered record is unblocked only by a genuine redemption that marks the managed record redeemed. Refunded-voided and quarantined (undecidable) records have no formal unblock path and need separate administrator handling (for example under the incident procedure), so settling the physical note alone does not guarantee a rollback. If the redeem observation fails after the credit committed (logged loudly), the record stays delivered and keeps refusing the empty candidate. Otherwise every live managed record must appear identically in the candidate, and a candidate newcomer carrying debit evidence must reference its authoritative durable debit in the same snapshot.

Plain consumed nonces are not covered by this guard. A restore rolls balances and plain nonces back together, and physical notes live in player inventories outside the economy snapshot, so the guard never promises that a restored nonce cannot be presented again — only that a restore never leaves an already-delivered, possibly-delivered or refunded-voided managed note redeemable after the balance is rolled back, and never invents a refund. A refunded or voided note is disabled on the ledger because its nonce is consumed, but the physical note may still be kept and presented again — that is why refunded-voided records refuse the empty candidate just like delivered ones.

After preflight and the safety backup pass, JSON restore reads the already committed marker/target pair through a secure directory handle; the marker protocol is not an OS atomic rename or hard-link claim. SQLite/MySQL restore runs as one JDBC transaction. A backend failure is reported without claiming unchanged live state. On success, the leaderboard cache is cleared, but sessions and GUIs are not hot-refreshed; restart the server before players return.

Delivery persistence window (known limitation): a managed banknote is placed into the player inventory on the region thread, and only afterwards is the issuance record marked delivered on the IO executor. Startup recovery refunds anything still marked delivery-started, but never refunds a record that reads delivered. So two orderings matter: stopping after the inventory handover but before the delivered mark is stored refunds the charge at restart (keeping the note too, if the world save already persisted it); stopping after the delivered mark is stored but before the world and player data are saved leaves the player charged with neither the note nor a refund. The economy snapshot and the world save are separate timings with no shared atomic boundary — this ordering follows from the program sequence and has not been proven out on a live server. Always stop cleanly and wait for world and plugin saving to finish.

## Logical backend boundary

The same v2 JSON model is used for logical backup and restore across JSON, SQLite, and MySQL. This supports logical moves such as JSON→JSON, SQLite→JSON→SQLite, and the corresponding MySQL logical path. It is not a MySQL server-native dump: it does not replace `mysqldump`, `mariadb-dump`, or a database administrator's physical/disaster-recovery process.

Automated round-trip coverage confirms JSON→JSON and SQLite→JSON→SQLite, including accounts, balances, transactions, reverted markers, consumed nonces, and managed issuance progress, plus backend rollback on restore failure. No live MySQL connection or live Folia backup/restore proof has been completed; those remain operational validation items rather than claims made by this document.

## When startup fails

- Check that `storage.type` is one of the three accepted values.
- For SQLite, check that `storage.sqlite.path` is a YAML map containing `path`, and that the resolved path stays inside the plugin data folder.
- For MySQL or MariaDB, check host, port, database, username, password, and network access.
- A schema version mismatch is not repaired by changing the backend name. Take a backup, then follow the v2 upgrade guidance in [Database concepts and upgrades](database.md).
