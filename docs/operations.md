# AceEconomy v2 server operations

English · [简体中文](operations.zh-CN.md) · [繁體中文](operations.zh-TW.md)

Use this checklist to start a server, change configuration safely, confirm storage, create or restore backups, and respond to incidents without deleting data first.

## Contents

- [Start-of-day checks](#start-of-day-checks)
- [Choosing and checking storage](#choosing-and-checking-storage)
- [Safe configuration changes](#safe-configuration-changes)
- [Routine commands](#routine-commands)
- [Backups and restore](#backups-and-restore)
- [Integrations](#integrations)
- [Stop, restart, and reopen](#stop-restart-and-reopen)
- [Emergency rollback](#emergency-rollback)
- [Handing a problem to support](#handing-a-problem-to-support)

## Start-of-day checks

After a normal start or restart, check the server console for `AceEconomy v2.1.0` and `AceLib v1.2.0`. Confirm that only one AceLib version is loaded. Then use a test account to run `/money balance` and, when appropriate, `/baltop top`.

If the server is not ready, do not open it to players. Keep the first AceEconomy, AceLib, or storage error and follow [Troubleshooting](troubleshooting.md).

## Choosing and checking storage

The v2 backend is selected by `storage.type`:

| Value | Location | Good fit |
|---|---|---|
| `json` | `plugins/AceEconomy/data-v2.json` | One server with a local file |
| `sqlite` | `storage.sqlite.path` under the plugin data folder | One server with a SQLite database |
| `mysql` | `storage.mysql.*` | A managed MySQL or MariaDB service |

Keep the SQLite path inside `plugins/AceEconomy/`. For MySQL, keep `pool-size` and `max-lifetime` under `storage.mysql`, and keep the password outside shared documentation.

The same v2 account and transaction model is used for the supported backends. A v1 data file is not a v2 backup and must not be substituted for one.

## Safe configuration changes

Edit `plugins/AceEconomy/config.yml` while the server is running only when your normal change process protects the file. Make a copy before editing, keep `version: "2.0"`, and validate the YAML shape before applying it.

Use the console command for ordinary configuration or language changes:

```text
/aceeco reload
```

The reload reports `AceEconomy reloaded` on success and keeps the last valid in-memory snapshot if the new configuration or language files cannot be loaded. After changing `storage.type`, the SQLite path, MySQL connection values, plugin JARs, AceLib, or optional plugins, stop and restart the full server instead.

Reload runs as a single transaction: the configuration, language, currency, and bank-GUI candidates are each validated first, and the plugin swaps them in together only when all of them pass. If any candidate fails, nothing is swapped and the server keeps running on the previous configuration.

Currency edits are sorted before the swap:

| Change | What reload does | Why |
| --- | --- | --- |
| Nothing changed | Reload succeeds; nothing is swapped. | There is nothing to apply. |
| Only `name` or `symbol` changed | Hot-applied: the new display text takes effect everywhere at once. | Display text never affects stored amounts, scales, or the default currency. |
| A currency was added | Reload is refused with a reason naming the new ID; the server keeps running on the old set. Restart to apply. | Existing accounts need batch initialization with rollback support, which no storage backend currently offers. |
| A currency was removed, or a `scale` or `default` flag changed | Reload is refused with a reason naming the affected ID; the server keeps running on the old set. Restart to apply. | Those changes would reinterpret or orphan stored balances. |
| The candidate section is invalid | Reload is refused with the parser reason; nothing is swapped. | An unparsable candidate must never replace the live registry. |

Three settings are restart-only and are never applied by reload. If `settings.main-command-alias`, `storage.type`, or `leaderboard.enabled` differs from the running value, the reload still succeeds but the reply carries a restart note naming it; the live value stays unchanged until the next full restart.

The reply tells you what happened. A failure shows the refusal reason — for example which currency ID was added or which scale changed — so you know whether to fix the file or schedule a restart. A success appends notes: what was hot-applied (display text, layout, configuration and language) and what still needs a restart.

A successful reload also closes every open bank session before the new layout takes effect, so no click can run with half old, half new rules; affected players simply reopen `/bank`.

A successful reload also drops the whole synchronous balance cache. That is accepted behaviour, not a bug: Vault reads never block on storage, so until the next persisted read or successful write re-primes an entry, a balance query falls back to the safe default `0.0`. There is no synchronous refill, because refilling on the calling thread would reintroduce the blocking I/O the cache exists to avoid.

Do not use Bukkit `/reload` as a maintenance or upgrade shortcut.

## Routine commands

Use these forms when checking a live server:

```text
/money balance [player] [currency]
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/baltop top [currency]
/bank open
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
```

Run a small, reversible check with a test account rather than changing a real player's balance. Record administrator balance changes in the server's normal administration notes.

## Backups and restore

Managed logical snapshots and manual disaster-recovery copies are different procedures. Do not use the instructions for one as a substitute for the other.

### Manual file and database disaster recovery

Before directly copying a JSON or SQLite data file, stop the server. Keep that manual copy outside the live plugin folder, use a date and purpose in its name, and do not replace the only known-good copy. This is a file-level disaster-recovery copy, not the managed `/aceeco backup` command.

For MySQL or MariaDB native/physical backups, follow the database administrator's normal backup process and keep the result with the matching server configuration backup. Do not treat a native or physical database backup as the managed v2 logical snapshot. The live MySQL/Folia combination and disaster-recovery workflows have not been validated here; verify them in a controlled drill before relying on them in production.

### Managed commands

The console and authorized administrators can create a managed logical snapshot:

```text
/aceeco backup [label]
```

This command can run while the server is running. It writes a credential-free v2 JSON logical snapshot under the plugin-controlled `<plugin data folder>/backups/` directory using a verified secure directory handle. It creates `<backup-id>.json` with handle-relative `CREATE_NEW`, writes and forces the complete content, then creates `<backup-id>.ready` with handle-relative `CREATE_NEW`. The ready file contains a SHA-256 digest and is the application-level logical commit point; restore requires the marker and a matching, fully validated JSON snapshot. Existing target or marker names are never replaced. The optional label accepts only letters, digits, `.`, `_` and `-`. The snapshot contains accounts, balances, transactions including reverted markers, and consumed nonces, never storage passwords or webhook URLs. For MySQL it reads through the live connection; it is a logical snapshot, not a `mysqldump`/`mariabackup` native or physical backup.

Snapshot publication requires a filesystem that supports secure directory handles, no-follow attribute checks, regular-file checks, and forced file channels. The protocol is an application-level commit-marker protocol; it does not claim an OS atomic rename or hard-link publication. On an unsupported filesystem, or after a partial target/marker failure, the command fails closed instead of falling back to an unsafe write. Keep the `.json` and matching `.ready` files together when moving a snapshot; a bare JSON file is not a committed backup. An unmarked orphan may remain and restore will reject it.

Restoring is destructive and strictly gated:

```text
/aceeco restore <backup-id> confirm
```

- Console only; requires `aceeconomy.admin` plus `aceeconomy.admin.restore`, and the literal word `confirm` exactly.
- Rejected while any player is online; ask everyone to leave first.
- Before touching live data it validates the snapshot and writes a safety backup of the current state. If the safety backup fails, nothing is restored.
- On success the old state's safety backup ID is reported and the leaderboard cache is cleared. Restart the server before letting players back in; open sessions and GUIs are not hot-refreshed by design.

There are no separate `/backup` or `/restore` root commands; these operations exist only as `/aceeco` admin subcommands.

The managed restore does not require a separate manual shutdown or file copy before running the command. It is still best scheduled in a maintenance window. The no-online-player gate, preflight, and pre-restore safety backup protect the managed operation; a malformed or incompatible snapshot must not be used as a reason to delete live data.

## Integrations

Vault and PlaceholderAPI are optional. If either is absent, the core economy service can still run. Vault uses the configured default currency. PlaceholderAPI uses the `aceeco` namespace:

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_<currency>%
%aceeco_balance_<currency>_formatted%
```

Discord is configured under `discord.enabled` and `discord.webhook-url`. Store the real webhook only on the server. Delivery is asynchronous and best-effort: a notification problem must be handled separately from the already completed economy transaction.

## Stop, restart, and reopen

Use the normal service control or the server console command:

```text
stop
```

Wait for world and plugin saving to finish. After a restart, repeat the start-of-day checks before letting players back in. If a restart follows a failed reload, restore the last known-good config first so the server does not repeatedly boot with the same bad edit.

## Emergency rollback

For a v2-to-v1 rollback, follow [Upgrade from v1](upgrade-from-v1.md). Keep the current v2 data copy before restoring the pre-upgrade v1 installation. Never ask v1 to read `data-v2.json`, `data-v2.sqlite`, or a v2 snapshot.

To reverse a single recorded transaction, use `/aceeco rollback <transaction-id>` from the console. It is console-only, requires `aceeconomy.admin` plus `aceeconomy.admin.rollback`, and reports each outcome explicitly: success lists the reversal audit record IDs, an already reverted transaction is a safe no-op, and a marker persist failure means the effect may exist without durable bookkeeping. Inspect storage and reconcile manually before retrying. See [Commands and permissions](commands.md) for the full outcome table.

The rollback command is wired into the production command surface and covered by automated contract tests, but live validation is still pending: Folia/Bukkit bridge execution, live MySQL storage, and fault-injection drills with real data have not been run yet. Until that release gate closes, use it only in controlled drills with backups taken beforehand.

## Handing a problem to support

Use [Troubleshooting](troubleshooting.md) and provide the first relevant error, the versions, the active storage type, the exact sanitized command, and the time of the incident. Keep data files, backups, passwords, tokens, and webhook URLs private.
