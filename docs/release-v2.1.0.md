# AceEconomy v2.1.0 release

English · [简体中文](release-v2.1.0.zh-CN.md) · [繁體中文](release-v2.1.0.zh-TW.md)

AceEconomy v2.1.0 extends the v2 server surface with operational history and rollback commands, managed logical backup and restore, configurable currencies and command forwarding, banknote and bank GUI actions, and JSON/SQLite persistence paths. The release baseline is Java 25 with Paper/Folia 26.1.2, the officially supported server line; Folia 26.2 is verified only on specific builds (VERIFIED-BETA) and other 26.2 builds are unverified. `AceLib-1.2.0.jar` is a required runtime dependency. The expected plugin artifact is `AceEconomy-2.1.0.jar`.

This document is for release operators and maintainers. It describes the implemented command and persistence surfaces together with the bounded runtime evidence currently available. It does not claim that the remaining live-player, live-database, client-GUI, cross-process, or recovery gates have passed.

## Contents

- [Release baseline](#release-baseline)
- [What is included](#what-is-included)
  - [Operations, history, and rollback](#operations-history-and-rollback)
  - [Backup and restore](#backup-and-restore)
  - [Dynamic currency and configuration](#dynamic-currency-and-configuration)
  - [Banknotes, GUI actions, and command forwarding](#banknotes-gui-actions-and-command-forwarding)
  - [Persistence](#persistence)
- [Install, upgrade, and rollback](#install-upgrade-and-rollback)
  - [Fresh installation](#fresh-installation)
  - [Replacing v1](#replacing-v1)
  - [Release rollback](#release-rollback)
- [Verify the release file](#verify-the-release-file)
- [Bounded Folia runtime evidence](#bounded-folia-runtime-evidence)
- [Remaining validation gates](#remaining-validation-gates)
- [Explicit non-goals](#explicit-non-goals)

For installation and daily operation, use [`admin-install-runbook.md`](admin-install-runbook.md), [`operations.md`](operations.md), and [`troubleshooting.md`](troubleshooting.md). For a v1 replacement, use [`upgrade-from-v1.md`](upgrade-from-v1.md). Detailed command and persistence references are [`commands.md`](commands.md) and [`persistence.md`](persistence.md).

## Release baseline

| Item | v2.1.0 value |
| --- | --- |
| Java | 25 |
| Paper/Folia | 26.1.2 |
| Required dependency | `AceLib-1.2.0.jar` |
| Plugin artifact | `AceEconomy-2.1.0.jar` (expected filename) |
| AceLib config schema | `version: "2.0"` |

The config schema remains `2.0`; this release does not introduce `version: "2.1"`. Keep exactly one compatible AceLib JAR in `plugins/`. Download `AceLib-1.2.0.jar` from <https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.0> and verify its SHA-256 `da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278` before installing it; [`admin-install-runbook.md`](admin-install-runbook.md) shows the exact command. Vault and PlaceholderAPI remain optional integrations, and the JDBC drivers used by the documented storage paths are supplied by the plugin artifact.

## What is included

### Operations, history, and rollback

- `/aceeco history [player] [currency] [page]` provides a read-only, newest-first transaction history view. Page numbers start at `0`, and the documented page size is `10`.
- `/aceeco rollback <transaction-id>` reverses one recorded transaction from the console. It requires `aceeconomy.admin` and `aceeconomy.admin.rollback`, validates the transaction UUID before lookup, and reports success, already-reverted, typed failure, and marker-persistence outcomes.
- An already reverted transaction is a safe no-op. If marker persistence fails, the effect may exist without durable bookkeeping; inspect storage and reconcile manually before retrying.

The command surface and permission table are in [`commands.md`](commands.md). The rollback path is implemented and covered by automated contract tests, but its live Folia/Bukkit bridge execution, live database path, and real-data fault drills remain open gates; see [Remaining validation gates](#remaining-validation-gates).

The key command policies are:

| Command | Sender | Permission |
| --- | --- | --- |
| `/aceeco history [player] [currency] [page]` | Player or console | `aceeconomy.admin` + `aceeconomy.admin.history` |
| `/aceeco reload` | Console only | `aceeconomy.admin` + `aceeconomy.admin.reload` |
| `/aceeco rollback <transaction-id>` | Console only | `aceeconomy.admin` + `aceeconomy.admin.rollback` |
| `/aceeco backup [label]` | Player or console | `aceeconomy.admin` + `aceeconomy.admin.backup` |
| `/aceeco restore <backup-id> confirm` | Console only; no online players | `aceeconomy.admin` + `aceeconomy.admin.restore` |
| `/withdraw cash <amount> [currency]` | Player only | `aceeconomy.command.withdraw` |
| `/bank open` | Player only | `aceeconomy.command.bank` |

These are declared command policies, not live sender- or permission-denial evidence. The latter remains an open validation gate.

### Backup and restore

Use the canonical managed commands:

```text
/aceeco backup [label]
/aceeco restore <backup-id> confirm
```

`backup` writes a v2 logical JSON snapshot under `<plugin data folder>/backups`, keeps the snapshot and matching `.ready` marker together, and does not replace an existing target. The snapshot contains logical accounts, balances, transactions, reverted markers, and consumed nonces; it does not contain database passwords or webhook URLs.

`restore` is destructive. It is console-only, requires `aceeconomy.admin` and `aceeconomy.admin.restore`, rejects an online player, and accepts only lowercase `confirm`. It performs preflight checks and creates a safety backup before changing live state. After a successful restore, restart the server before players return because sessions and GUIs are not hot-refreshed.

These are logical application snapshots. They do not replace `mysqldump`, `mariadb-dump`, or a database administrator's physical/disaster-recovery process, and there are no independent `/backup` or `/restore` root commands.

### Dynamic currency and configuration

The `currencies.*` map is operator-defined. Each currency supplies an ID, display name, symbol, scale, and exactly one default currency is required. Currency IDs are normalized for case and surrounding whitespace, while invalid, duplicate, empty, or malformed currency configuration prevents a partial startup.

`/aceeco reload` reloads configuration and language files while preserving the last valid in-memory configuration when reload fails. It does not re-register commands or rebuild startup-only currency and alias registries. Restart after changing the plugin JAR, AceLib, storage backend or connection settings, currencies, or the configured main command alias.

### Banknotes, GUI actions, and command forwarding

`/withdraw cash <amount> [currency]` creates a v2 banknote. `/bank open` opens the bank interface. The documented GUI action contract includes `DEPOSIT` at slot `4`, `WITHDRAW` at slots `11` and `13`, and `CLOSE` at slot `15`. A valid banknote is credited and protected against replay before the item is removed or its stack is reduced. Invalid, replayed, or failed credits leave the item in the player's inventory.

The command registry forwards `plugin.yml`-declared aliases to their canonical roots: `/balance` and `/bal` forward to `/money`, `/balancetop` and `/top` forward to `/baltop`, and `/menu` and `/bankmenu` forward to `/bank`. `settings.main-command-alias` configures the additional administrator root alias and defaults to `aceeco`. Alias changes are startup-only and are rejected when they collide with another declared command label. Right-click banknote redemption is not included.

### Persistence

The documented v2 backends are JSON, SQLite, and MySQL-compatible configuration for MySQL/MariaDB. JSON uses `data-v2.json`; SQLite uses the configured path inside the plugin data folder; MySQL/MariaDB uses `storage.type: mysql` and `storage.mysql.*`. JSON and SQLite persistence paths have automated coverage for schema, restart, snapshot, and transaction boundaries. The current release evidence does not turn that coverage into live MySQL/MariaDB or cross-process JSON approval.

## Install, upgrade, and rollback

### Fresh installation

1. Stop the server and make a dated, restorable copy outside the live server directory. Include the complete `plugins/AceEconomy/` directory when it already exists.
2. Put `AceLib-1.2.0.jar` and the expected `AceEconomy-2.1.0.jar` in `plugins/`. Do not keep another AceLib version beside them.
3. Start once to create the v2 files, then confirm the active `plugins/AceEconomy/config.yml` contains `version: "2.0"`.
4. Choose JSON, SQLite, or the configured MySQL-compatible backend. Keep database passwords and webhook URLs as local values.
5. Start again, check the enable messages, and run the appropriate operator checks. Use [`admin-install-runbook.md`](admin-install-runbook.md) for the full procedure.

### Replacing v1

v2 is a clean-slate installation. It does not automatically migrate v1 configuration or data, and a v1 file must not be renamed to `data-v2.json` or loaded into a v2 backend. Keep the complete pre-cutover v1 installation as the rollback source. Follow [`upgrade-from-v1.md`](upgrade-from-v1.md) rather than copying v1 files into v2.

### Release rollback

To return from v2.1.0 to v1, stop v2, preserve a separate copy of the current v2 data, move the v2 JARs out of `plugins/`, and restore the dated v1 JARs, configuration, and data. Start v1 and confirm that its data is readable before allowing players back in. Never ask v1 to read `data-v2.json`, `data-v2.sqlite`, or a v2 snapshot.

## Verify the release file

The v2.1.0 release is published as the GitHub Release `v2.1.0` (release commit `2bb86c4`). The Publish Release workflow attaches the full, slim, sources, and javadoc JARs together with a `SHA256SUMS` asset. Use that published `SHA256SUMS` as the source of truth: place it beside the artifact you downloaded and verify the bare filename entry:

```text
sha256sum -c SHA256SUMS
```

On macOS, calculate the local digest with:

```text
shasum -a 256 AceEconomy-2.1.0.jar
```

Compare the first column with the `AceEconomy-2.1.0.jar` entry in `SHA256SUMS` before placing the plugin on a live server. Do not replace this comparison with a value copied from an earlier release.

## Bounded Folia runtime evidence

The available bounded evidence covered both Folia `26.1.2-8` and Folia `26.2-4`. It used the same v2.0.0 artifact and covered startup and plugin enable, AceLib capability, status/health, RCON route/help and typed errors, declared aliases, backup/restore confirmation and safety-backup paths, and reload/restart behavior.

This is bounded runtime evidence, not a production certification. It does not prove successful real-player economy actions, live MySQL/MariaDB behavior, GUI rendering or clicking, JSON multi-process safety, physical database backup recovery, or fault-injection recovery.

## Remaining validation gates

The following items are explicitly not-run or still open. A future release or operator acceptance record must provide the corresponding live evidence before treating the affected path as production-ready.

- **Player sender and permission denial** — not run. Verify player-only and console-only sender rejection, missing root permission, and missing child permission on the target server.
- **GUI render and click** — not run. Open the bank GUI with a real client and verify rendering plus `DEPOSIT`, `WITHDRAW`, and `CLOSE` clicks.
- **Live MySQL/MariaDB** — not run. Connect to the intended service and verify startup, writes, reads, restart, and the documented logical snapshot path.
- **JSON cross-process race** — not run. Run a multi-process contention test; same-process atomic file behavior is not a cross-process guarantee.
- **Physical/native backup** — not run by the plugin. For SQL production operations, validate the database administrator's native backup and restore procedure separately; the logical commands do not replace it.
- **Real-data recovery and fault injection** — not run. Use representative data and controlled failures to verify recovery, marker handling, and the required manual reconciliation path.
- **Real-player history and rollback paths** — partial paths remain not run. Exercise history queries and controlled rollback scenarios with real player accounts, including the transfer-counterpart and persistence-failure cases.

## Explicit non-goals

- Automatic v1 migration is not included.
- Essentials/CMI import is not included.
- Native database dump replacement is not included.
- Right-click banknote redemption is not included.
- Independent `/backup` and `/restore` root commands are not included; use the `/aceeco` subcommands.

The release scope and operational boundaries are also recorded in [`operations.md`](operations.md), [`persistence.md`](persistence.md), and [`cutover.md`](cutover.md). Keep passwords, tokens, webhook URLs, data files, and backups private when collecting evidence or reporting a problem.
