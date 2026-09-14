# AceEconomy v2.2.0 release draft

English · [简体中文](release-v2.2.0.zh-CN.md) · [繁體中文](release-v2.2.0.zh-TW.md)

> **Preparation draft — not a release.** This page describes work in progress. The repository still declares `2.1.0`, and there is no `v2.2.0` artifact, checksum, or release tag yet. Do not install from this page or link to it as a download.

If you need a version to run today, install the released [AceEconomy v2.1.0](release-v2.1.0.md). This draft exists so operators and maintainers can see the current v2.2.0 scope, the evidence behind it, and the gates that are still open before it can ship.

## Contents

- [Can I install this now?](#can-i-install-this-now)
- [Baseline and release status](#baseline-and-release-status)
- [What v2.2.0 adds](#what-v220-adds)
- [What is implemented, what has evidence, what is still gated](#what-is-implemented-what-has-evidence-what-is-still-gated)
- [Before you install or upgrade](#before-you-install-or-upgrade)
- [Release gates](#release-gates)
- [Explicitly not claimed](#explicitly-not-claimed)

## Can I install this now?

No. Nothing here is downloadable or buildable as a release yet:

- `build.gradle.kts` still declares `version = "2.1.0"`, so the current build produces `AceEconomy-2.1.0.jar`.
- There is no `v2.2.0` GitHub Release, artifact, or `SHA256SUMS` entry, and no release date has been set.
- The live-validation gate for permissions, GUI, a real MySQL/MariaDB, and a real Bedrock client is still open.

Use the [v2.1.0 installation runbook](admin-install-runbook.md) with the released v2.1.0 until a v2.2.0 release notes page replaces this one.

## Baseline and release status

| Item | Current state |
| --- | --- |
| Java | 25 |
| Server | Paper/Folia 26.1.2 (officially supported line); Folia 26.2 verified only on specific builds (VERIFIED-BETA) |
| Required dependency | `AceLib v1.2.1` |
| Repo build version | `2.1.0` (unchanged) |
| Plugin artifact | To confirm before release |
| Config schema | `version: "2.0"` |
| Release date | To confirm before release |

The baseline above matches the released v2.1.0 line. v2.2.0 changes are in the plugin surface, not the Java/server baseline.

## What v2.2.0 adds

The scope below is what the current code and documentation cover. Each item names the setting or command it depends on so you can check it against the references.

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

## What is implemented, what has evidence, what is still gated

The table separates three things that are easy to blur: code that exists, automated or partial evidence behind it, and live checks that have not run. A row can have an implementation and automated tests while its release gate is still open.

| Area | Implemented | Evidence available | Release gate pending |
| --- | --- | --- | --- |
| Localization messages | Three locale files, typed variables, MiniMessage, escaped user text | Resource-contract and language-coverage tests | A live locale switch through `/aceeco reload` |
| Discord notifications | Async best-effort embeds; redaction and length bounds | Notifier, payload, and transport tests | One real webhook delivery |
| SQL pool and disconnect recovery | Strict provider-owned pool; unsafe borrows abandoned, expired connections replaced | Pool, lifecycle, and SQL-backend tests | Real MySQL/MariaDB: connect, write, read, restart |
| SQL leaderboard and read cache | Cached `/baltop` snapshot; `AccountBalanceCache` for Vault reads | Leaderboard-cache and balance-cache tests | Live MySQL/MariaDB leaderboard and cache behaviour |
| Vault name-based API | Name-only methods resolve via online and cached offline records | Vault provider and name-lookup tests | A live Vault consumer against a running server |
| Essentials/CMI import | Console-only command, path gate, pre-import backup, idempotent re-runs | Parser, path-gate, TOCTOU, and bounded-read tests | A live import from real EssentialsX/CMI data |
| Bedrock downgrade and bank form | Click-action fallbacks; native form sharing the deposit/withdraw path | Bedrock-fallback and form-routing tests | A real Bedrock client: deposit, withdraw, cancel, reopen |
| PlaceholderAPI placeholders | `aceeco` namespace including `rank` and `top_*` | Resolver and expansion tests | A live PlaceholderAPI expansion on a server |
| Banknote redemption | Right-click redemption through the atomic deposit path | Redeem and banknote-schema tests | Live-player redemption, including failure paths |
| Configurable bank GUI | `bank-gui` title, size, and actions; sessions close on reload | Config-parser and GUI-session tests | A real Java client opening and clicking the GUI |
| Currency reload | Display-only hot-apply; structural changes refused with a reason | Reload-plan and currency-transaction tests | A live reload against a running economy |
| Permission and sender rules | Declared sender and permission policy per command | Command-contract tests | Real-player permission and sender denial |

Automated tests and static source checks are not live-server validation, and a partial runtime smoke check is not release acceptance. Only the release gates below close that gap.

## Before you install or upgrade

1. Install the released v2.1.0 for now, following the [admin installation runbook](admin-install-runbook.md).
2. Keep the Java 25 and Paper/Folia 26.1.2 baseline, and keep exactly one `AceLib-1.2.1.jar` in `plugins/`.
3. Keep `config.yml` at `version: "2.0"`. Structural currency changes and startup-only settings still require a restart, not a reload.
4. Back up the plugin data folder or database before any change, and validate on a copy first.
5. Schedule the real MySQL/MariaDB drill and the real Bedrock client check for the pre-release verification window. These are release gates that a production claim cannot omit, so they must pass before release, not after it.

## Release gates

The following must close before this draft can replace the v2.1.0 release notes. None of them is reported as passed here.

1. **Version and artifact** — bump `build.gradle.kts` to `2.2.0`, produce the artifact, and publish its `SHA256SUMS`.
2. **Full test and build** — run the complete test suite and the release build on the release commit.
3. **Real-player permission and sender denial** — verify player-only and console-only sender rejection, plus missing root and child permissions, on the target server.
4. **Java bank GUI** — open `/bank open` with a real Java client and verify rendering plus the `DEPOSIT`, `WITHDRAW`, and `CLOSE` clicks.
5. **Real MySQL/MariaDB** — connect to the intended service and verify startup, writes, reads, restart, and the logical snapshot path.
6. **Real Bedrock client** — complete deposit, withdraw, cancel, and reopen on a Bedrock client, and confirm the click-action fallback hints.
7. **Release-document record** — record the runs above so the released notes can cite them.

The v2.1.0 carry-over gates also remain open and still apply to this line: live Folia/Bukkit rollback execution, JSON cross-process contention, physical/native database backup, and real-data recovery and fault injection. See the v2.1.0 [remaining validation gates](release-v2.1.0.md#remaining-validation-gates).

## Explicitly not claimed

- v2.2.0 is not released, downloadable, or built. No version bump, artifact filename, checksum, or date is stated on this page.
- No test counts or coverage numbers are given.
- No release gate above is reported as passed.
- Essentials/CMI import is not automatic migration; it stays an explicit, console-only command.
- The logical backup commands do not replace `mysqldump`, `mariadb-dump`, or a database administrator's physical recovery process.
- Vault bank methods are not supported, and Vault always uses the configured default currency.
- There are no per-world balances; world-name parameters are accepted but ignored.

For installation, daily operation, and recovery, use the [admin installation runbook](admin-install-runbook.md), [Server operations](operations.md), and [Persistence, backup, and restore](persistence.md). Keep passwords, tokens, webhook URLs, data files, and backups private when collecting evidence or reporting a problem.
