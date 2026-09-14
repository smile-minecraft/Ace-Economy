# AceEconomy v2.2.0 release

English · [简体中文](release-v2.2.0.zh-CN.md) · [繁體中文](release-v2.2.0.zh-TW.md)

AceEconomy v2.2.0 is a released plugin for the v2 server line. It adds localization files, Discord notifications, a provider-owned SQL connection pool with disconnect recovery, a cached SQL leaderboard and read cache, a name-based Vault API, an Essentials/CMI import command, Bedrock message downgrade with a native bank form, PlaceholderAPI placeholders, banknote redemption, a configurable bank GUI, and display-only currency reload. The baseline is Java 25 on Paper/Folia 26.1.2, the officially supported server line; Folia 26.2 is verified only on specific builds (VERIFIED-BETA). `AceLib-1.2.1.jar` is a required dependency, and the plugin artifact is `AceEconomy-2.2.0.jar`. The release is published as GitHub Release `v2.2.0` at <https://github.com/smile-minecraft/Ace-Economy/releases/tag/v2.2.0>.

This document is for operators and maintainers deciding whether to install or upgrade. It states what the release contains, how to verify the download, and what the pre-release validation covered.

## Contents

- [Can I install this now?](#can-i-install-this-now)
- [Baseline and release status](#baseline-and-release-status)
- [Verify the release file](#verify-the-release-file)
- [What v2.2.0 adds](#what-v220-adds)
- [What is implemented and the evidence behind it](#what-is-implemented-and-the-evidence-behind-it)
- [Folia live rerun](#folia-live-rerun)
- [Release verification](#release-verification)
- [Before you install or upgrade](#before-you-install-or-upgrade)
- [Explicitly not claimed](#explicitly-not-claimed)

## Can I install this now?

Yes. v2.2.0 is a published release:

- Download `AceEconomy-2.2.0.jar` from GitHub Release `v2.2.0` at <https://github.com/smile-minecraft/Ace-Economy/releases/tag/v2.2.0>. The same Release carries the slim, sources, and javadoc JARs and a `SHA256SUMS` asset.
- Keep exactly one `AceLib-1.2.1.jar` in `plugins/` beside the plugin. AceLib is required to start.
- Follow the [admin installation runbook](admin-install-runbook.md) for a first install or an upgrade, and check the downloaded JAR against the published `SHA256SUMS` as shown in [Verify the release file](#verify-the-release-file).

## Baseline and release status

| Item | v2.2.0 value |
| --- | --- |
| Java | 25 |
| Server | Paper/Folia 26.1.2 (officially supported line); Folia 26.2 verified only on specific builds (VERIFIED-BETA) |
| Required dependency | `AceLib v1.2.1` |
| Repo build version | `2.2.0` |
| Plugin artifact | `AceEconomy-2.2.0.jar` |
| GitHub Release / tag | `v2.2.0` (published) |
| Config schema | `version: "2.0"` |
| Release date | 2026-09-14 |

The baseline matches the released v2.1.0 line. v2.2.0 changes the plugin surface, not the Java or server baseline.

## Verify the release file

The v2.2.0 release is published as GitHub Release `v2.2.0`. The Publish Release workflow attaches the full, slim, sources, and javadoc JARs together with a `SHA256SUMS` asset. Use that published `SHA256SUMS` as the source of truth: place it beside the artifact you downloaded and verify the bare filename entry:

```text
sha256sum -c SHA256SUMS
```

On macOS, calculate the local digest with:

```text
shasum -a 256 AceEconomy-2.2.0.jar
```

Compare the first column with the `AceEconomy-2.2.0.jar` entry in `SHA256SUMS` before placing the plugin on a live server. The `SHA256SUMS` file in the repository root is a local build record, not a published asset; do not verify against it. Do not replace the comparison with a value copied from an earlier release.

## What v2.2.0 adds

The scope below is what this release covers. Each item names the setting or command it depends on so you can check it against the references.

- **Localization messages** — `lang/<locale>.yml` for `en_US`, `zh_TW`, and `zh_CN`, with typed `{placeholder}` variables, MiniMessage presentation, and escaping for user-supplied text.
- **Discord notifications** — asynchronous, best-effort embeds after a committed transaction, configured by `discord.enabled` and `discord.webhook-url`. A delivery failure never changes the committed economy result.
- **SQL connection pool and disconnect recovery** — `storage.mysql.pool-size` and `storage.mysql.max-lifetime` feed a provider-owned strict pool. A borrow whose cleanup failed is discarded instead of being lent again, and an expired or closed idle connection is replaced on the next borrow.
- **SQL leaderboard query and account read cache** — `/baltop` reads a cached snapshot (`leaderboard.cache-time-seconds`), and `AccountBalanceCache` keeps synchronous Vault reads off storage, returning the safe default `0.0` on a cache miss instead of blocking.
- **Vault name-based API** — `hasAccount(String)`, `getBalance(String)`, `has`, `depositPlayer`, `withdrawPlayer`, and `createPlayerAccount` resolve a name through online players and cached offline records, case-insensitively. The UUID stays the account key, so a renamed player keeps the same account, and no storage or network I/O runs on the calling thread.
- **Essentials/CMI import** — the console-only `/aceeco import <essentials|cmi> <path> [currency] [apply confirm]` command. It previews without the exact `apply confirm` pair, keeps the source inside the plugin-controlled `import/` directory, takes a pre-import safety backup, and skips already-applied records on a re-run.
- **Bedrock message downgrade and native bank form** — with Floodgate, click actions in chat messages are replaced by readable hints from `message.bedrock.fallback.*`, and `/bank open` sends Bedrock players a native form instead of the chest menu. Both interfaces share the same deposit/withdraw path.
- **PlaceholderAPI placeholders** — the `aceeco` namespace, including raw and formatted balances, `rank`, `top_name`, `top_balance`, and `currency_name`/`currency_symbol`. Rank and top values read the same cached snapshot as `/baltop` and never trigger a database query.
- **Banknote redemption** — right-clicking a valid banknote redeems it through the same atomic deposit path as the dashboard button. A replay is rejected, and an invalid or failed credit leaves the item with the player.
- **Configurable bank GUI** — the `bank-gui` section sets the title key, inventory size, and per-slot actions. A valid reload closes open sessions first, so no click runs against half old, half new rules.
- **Currency reload** — `/aceeco reload` hot-applies display-only changes (`name`, `symbol`); adding, removing, or changing `scale`/`default` is refused with a reason and needs a restart.

Command and permission details are in [Commands and permissions](commands.md); settings are in the [Configuration guide](config.md); integration behaviour is in [Integrations](integrations.md) and the [Integration API](integration-api.md).

## What is implemented and the evidence behind it

The table separates two things that are easy to blur: code that shipped, and the evidence behind it. The pre-release validation that covers the live checks is recorded in [Release verification](#release-verification).

| Area | Implemented | Evidence |
| --- | --- | --- |
| Localization messages | Three locale files, typed variables, MiniMessage, escaped user text | Resource-contract and language-coverage tests |
| Discord notifications | Async best-effort embeds; redaction and length bounds | Notifier, payload, and transport tests |
| SQL pool and disconnect recovery | Strict provider-owned pool; unsafe borrows abandoned, expired connections replaced | Pool, lifecycle, and SQL-backend tests; connect, write, read, and restart persistence on a controlled MySQL 8.4 service in the Folia rerun |
| SQL leaderboard and read cache | Cached `/baltop` snapshot; `AccountBalanceCache` for Vault reads | Leaderboard-cache and balance-cache tests |
| Vault name-based API | Name-only methods resolve via online and cached offline records | Vault provider and name-lookup tests |
| Essentials/CMI import | Console-only command, path gate, pre-import backup, idempotent re-runs | Parser, path-gate, TOCTOU, and bounded-read tests |
| Bedrock downgrade and bank form | Click-action fallbacks; native form sharing the deposit/withdraw path | Bedrock-fallback and form-routing tests |
| PlaceholderAPI placeholders | `aceeco` namespace including `rank` and `top_*` | Resolver and expansion tests |
| Banknote redemption | Right-click redemption through the atomic deposit path | Redeem and banknote-schema tests |
| Configurable bank GUI | `bank-gui` title, size, and actions; sessions close on reload | Config-parser and GUI-session tests; rendering plus the `DEPOSIT`, `WITHDRAW`, `CLOSE`, reopen, and no-item deposit path on a real Java client in the Folia rerun |
| Currency reload | Display-only hot-apply; structural changes refused with a reason | Reload-plan and currency-transaction tests |
| Permission and sender rules | Declared sender and permission policy per command | Command-contract tests; console-only sender denial observed in the Folia rerun |

Automated tests and static source checks are not live-server validation. The pre-release validation recorded in [Release verification](#release-verification) covers the live checks that automated evidence cannot.

## Folia live rerun

A bounded rerun on the test server exercised the v2.2.0 artifact on Folia. The server runs Folia `26.2-7` on Java 25, with `storage.type: mysql` pointed at a controlled MySQL `8.4` service. One Java client player connected to a quiet server and ran the checks below. The run ended by resetting the test balance with the console `/aceeco set` command and closing the GUI.

The rerun covers the Java bank GUI, the permission and sender rules, and the MySQL connect/write/read/restart-persistence subset.

**Passed in this rerun**

- Plugin enable — the console enable log showed `AceEconomy 2.2.0` and `AceLib 1.2.1` loaded and enabled, and PlaceholderAPI registered the `aceeco` expansion as `2.2.0`.
- Balance display — `/money` and `/baltop top` showed the connected player's balance and top rank.
- Banknote round trip — `/withdraw cash 100` produced a v2 banknote, and right-clicking it redeemed the note. Chat confirmed the redemption, and the balance returned to the value it held before the withdrawal.
- Bank GUI rendering — `/bank open` opened the Java `Generic_9x3` bank menu, and the `DEPOSIT` (slot `4`), `WITHDRAW` (slots `11` and `13`), and `CLOSE` (slot `15`) buttons rendered with their lore.
- Bank GUI withdraw — clicking `WITHDRAW` produced a new banknote.
- Bank GUI deposit — `/withdraw cash 100` was followed by `/bank open`; after reading the current active inventory id, `DEPOSIT` (slot `4`) was clicked, the menu was closed, and `/money` showed the temporary test balance restored to its pre-withdrawal value.
- Bank GUI close and reopen — clicking `CLOSE` (slot `15`) with the current active inventory id closed the menu, the inventories list no longer showed it, and a further `/bank open` reopened it.
- Bank GUI deposit with no banknote — with no banknote in hand, `/bank open` was reopened and `DEPOSIT` (slot `4`) was clicked; after closing, `/money` still showed the same temporary test balance, so the no-item path credited nothing.
- Console-only sender denial — a player running `/aceeco reload` got `subcommand is console-only: reload`.
- MySQL write/read/restart persistence — the server ran with `storage.type: mysql` against a controlled MySQL `8.4` service on the local host, which answered a `SELECT 1` probe. The console `/aceeco set` wrote a temporary test balance, `/money` read it back, and after a non-destructive server restart the same value was read again. The test balance was reset to its starting value at the end.

This rerun is bounded runtime evidence for the v2.2.0 artifact. The pre-release validation that closed the remaining release checks is recorded in [Release verification](#release-verification).

## Release verification

The release gates for v2.2.0 are closed. Before the release, the maintainer completed the pre-release validation across the areas the automated evidence could not cover:

- The real-player permission and sender matrix.
- A real Bedrock client: the native form, the fallback hints, cancel, and reopen.
- The remaining MySQL/MariaDB items: concurrent transfers, logical snapshot/restore, disconnect recovery, and recovery/fault injection.
- The v2.1.0 carry-over checks: JSON cross-process contention, physical/native database backup, and a real-data restore.

The v2.2.0 artifact also passed the bounded live rerun on Folia `26.2-7` with Java 25 and AceLib 1.2.1; the checks and their observed results are recorded in [Folia live rerun](#folia-live-rerun).

## Before you install or upgrade

1. Install the released v2.2.0, following the [admin installation runbook](admin-install-runbook.md).
2. Keep the Java 25 and Paper/Folia 26.1.2 baseline, and keep exactly one `AceLib-1.2.1.jar` in `plugins/`.
3. Keep `config.yml` at `version: "2.0"`. Structural currency changes and startup-only settings still require a restart, not a reload.
4. Back up the plugin data folder or database before any change, and validate on a copy first.

## Explicitly not claimed

- No test counts or coverage numbers are given.
- Essentials/CMI import is not automatic migration; it stays an explicit, console-only command.
- The logical backup commands do not replace `mysqldump`, `mariadb-dump`, or a database administrator's physical recovery process.
- Vault bank methods are not supported, and Vault always uses the configured default currency.
- There are no per-world balances; world-name parameters are accepted but ignored.

For installation, daily operation, and recovery, use the [admin installation runbook](admin-install-runbook.md), [Server operations](operations.md), and [Persistence, backup, and restore](persistence.md). Keep passwords, tokens, webhook URLs, data files, and backups private when collecting evidence or reporting a problem.
