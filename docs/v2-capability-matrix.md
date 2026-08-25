# AceEconomy v2 — Capability baseline matrix

English · [简体中文](v2-capability-matrix.zh-CN.md) · [繁體中文](v2-capability-matrix.zh-TW.md)

This document is the v1 capability baseline used before the clean-slate v2 rewrite. It records the product capabilities and economic rules that remain to be preserved, identifies where v2 may break v1 compatibility, and marks deletions that require a separate decision. The evidence comes from the actual source, resources, and tests in the worktree rather than from a historical report. Current v2.0.0/v2.1 wiring and unverified items are listed in [v2.0.0/v2.1 scope boundary](#v200-v21-scope-boundary).

## Contents

- [Verification environment and evidence baseline](#verification-environment-and-evidence-baseline)
- [Capability retention matrix](#capability-retention-matrix)
- [Economic rules](#economic-rules)
- [Non-goals](#non-goals)
- [Remaining risks and v2 prerequisites](#remaining-risks-and-v2-prerequisites)
- [v2.0.0/v2.1 scope boundary](#v200-v21-scope-boundary)

## Verification environment and evidence baseline

- Command (`JAVA_HOME` points to Java 21 because the system Java is 25.0.4 and Gradle 8.12 Kotlin DSL cannot parse under that setup): `JAVA_HOME=<java21> ./gradlew clean test`.
- Result: **70 tests, 0 failures, 0 errors, 0 skipped** (49 v1 baseline tests plus 21 capability tests added in this round).
- The tests that lock the rules are under `src/test/java/com/smile/aceeconomy/capability/`:
  - `EconomyCapability.java` — v2 contract interface with no v1 class names.
  - `V1CurrencyManagerAdapter.java` — the only test adapter allowed to reference v1 classes (the anti-corruption seam).
  - `EconomyCapabilityContractTest.java` — balance, transaction, limit, and cancellation scenarios.
  - `ConfigCapabilityTest.java` — config.yml multi-currency, debt, locale, Discord, and storage contracts.
  - `CommandSurfaceCapabilityTest.java` — plugin.yml command and permission surface contracts.

## Capability retention matrix

Status definitions:

- **RETAIN** — v2 must retain the product capability; its internals may be rewritten, but the behavior contract remains.
- **RESET** — the capability remains, but v2 may break v1 binary, data, config, banknote schema, and API compatibility.
- **EXCLUDE** — the plan removes the product capability; a separate decision is required, and this baseline does not assume deletion by itself.

| # | Product capability | Status | Confirmed v1 evidence | Locking method |
|---|---|---|---|---|
| 1 | Multi-currency system | RETAIN | `config.yml` `currencies:` dollar/token; `CurrencyManager` | ConfigCapabilityTest + ContractTest.currencyExists |
| 2 | Accounts and starting balances | RETAIN | `Account`, `ConfigManager.getStartBalance()`=1000 | ContractTest.testAccountStartsAtStartBalance |
| 3 | Deposits and withdrawals (atomic transactions) | RETAIN | `CurrencyManager.deposit/withdraw` | ContractTest deposit/withdraw |
| 4 | Transfers | RETAIN | `PayCommand`, `EconomyProvider` (v1 composes withdraw+deposit at the command layer) | Contract reserved; v2 should expose an explicit transfer entry point (see risk R3) |
| 5 | Vault economy integration | RETAIN | `hook/VaultImpl.java` | plugin.yml `softdepend: Vault` (optional; wired in v2.0 and skipped when absent) |
| 6 | PlaceholderAPI | RETAIN | `hook/AceEcoExpansion.java` | plugin.yml `softdepend: PlaceholderAPI` |
| 7 | SQLite storage | RETAIN | `storage/implementation/SQLiteImplementation.java` | ConfigCapabilityTest.storage; wired in v2.0.0 (`storage.type: sqlite` → `SqlBackend + SqliteDialect`, file inside the plugin data folder; path traversal is rejected by `StorageConfigParser`) |
| 8 | MySQL storage | RETAIN | `storage/implementation/MySQLImplementation.java` | ConfigCapabilityTest.storage; wired in v2.0.0; (`storage.type: mysql` → `SqlBackend + MySqlDialect` + HikariCP, JDBC driver shaded; connection/pool from `storage.mysql.*`); live MySQL connection remains unverified (v2.0.0 release gate) |
| 9 | JSON storage | RETAIN | `storage/JsonStorageHandler.java` | Listed for retention; v1 config does not enable it by default, and v2 decided whether it is the default |
| 10 | Transaction records and audit | RETAIN | `LogManager`, `listeners/EconomyLogListener`, `AuditListener` | Contract reserved (see risk R4); v2.0.0 writes through `PersistentAuditSink` and `TransactionRepository.append`/`appendBatch`; read-only audit queries are wired to `/aceeco history [player] [currency] [page]` through `HistoryService` and `ProductionAdapters.History`, permission `aceeconomy.admin.history`; live server verification remains open |
| 11 | Rollback | RETAIN | `commands/RollbackCommand.java`; permission `aceeconomy.admin.rollback` | Wired in v2 through `RollbackService` and `ProductionAdapters.Rollback` at `/aceeco rollback <transaction-id>` (console-only, root `aceeconomy.admin` plus child `aceeconomy.admin.rollback`, atomic `StorageReversalExecutor`); live server verification remains open |
| 12 | Banknotes | RESET | `BanknoteInputListener`, `listeners/BanknoteListener` | Permission/command surface retained; schema may break |
| 13 | Bank GUI | RETAIN | `gui/BankMenu.java`, `gui/GUIListener`; `/bank` | CommandSurfaceCapabilityTest |
| 14 | Leaderboards | RETAIN | `LeaderboardManager`, `BaltopCommand`; `/baltop` | CommandSurfaceCapabilityTest |
| 15 | Discord notifications | RETAIN | `utils/DiscordWebhook`, `service/DiscordWebhook`; `config.discord` | ConfigCapabilityTest.discord |
| 16 | Three locales (en_US/zh_TW/zh_CN) | RETAIN | `lang/messages_*.yml`; `ConfigManager` locales | ConfigCapabilityTest.locale; v2 uses `lang/<locale>.yml` (`en_US`/`zh_TW`/`zh_CN`), while `messages_*.yml` are v1 dead keys |
| 17 | Essentials / CMI import | EXCLUDED | `migration/EssentialsMigrator.java`, `migration/CMIMigrator.java` (historical v1 evidence only) | Removed from v2.0.0 and the v2.1 follow-up by user decision; no vendor parser, import command/API, or v1 → v2 migration compatibility layer; a retained `ImportService` only represents a general service/unit contract, not the product feature |
| 18 | Debt / negative balances | RETAIN | `config.economy.allow-negative-balance`, `default-debt-limit`; `CurrencyManager.getDebtLimit` | ContractTest DebtEnabled/Disabled |
| 19 | Permission contract, including rollback/debt bypass | RETAIN | `plugin.yml` permissions | CommandSurfaceCapabilityTest.permissions |

## Economic rules

The following rules are locked by `ContractTest`:

1. A new account starts at `start-balance` (v1=1000).
2. Deposits and withdrawals are atomic transactions in one currency; non-positive amounts are rejected and the transaction is cancelled.
3. A withdrawal with insufficient funds throws `InsufficientFundsException` and leaves the balance unchanged; the transaction is cancelled.
4. When debt is disabled, a balance cannot be below 0; negative `setBalance` is rejected.
5. When debt is enabled, a balance may be negative but is limited by `debt-limit`; a withdrawal beyond the limit is cancelled.
6. Currency ID comparison is case-insensitive and safe around whitespace (`currencyExists`).

## Non-goals

- Do not upgrade Gradle, Java, Paper, or AceLib.
- Do not rewrite production domain, storage, or plugin code.
- Do not perform an architecture rewrite or later v2 implementation phase.
- Do not publish, push, or create external state.
- Do not promise v1 binary, data, config, or banknote compatibility (v2 may break it where marked RESET).

## Remaining risks and v2 prerequisites

- R1: This round ran under Java 21; system Java 25 causes Gradle 8.12 Kotlin DSL failure. v2 implementation must decide the CI/local `JAVA_HOME` policy or upgrade Gradle.
- R2: Capability tests map v1 behavior through `V1CurrencyManagerAdapter`; v2 needs a new adapter while the contract interface remains unchanged.
- R3: v1 has no independent `transfer` method (the command layer composes the operation); the capability contract currently locks atomic deposit/withdraw, so v2 should define transfer semantics explicitly.
- R4: Audit and rollback boundaries are not locked by capability tests (they require storage/log participation and are outside this document's minimum change); adding an `AuditCapability` contract is recommended for v2 implementation.

## v2.0.0/v2.1 scope boundary

This section follows the current source and build configuration. See [cutover](cutover.md) for the complete wiring details; do not read the v1 retention contract or unit tests as proof of v2.0.0 production availability.

- **Wired in v2.0.0:** JSON (default), SQLite, and MySQL persistence through `StorageConfigParser` → `PersistenceBackendFactory`; bank GUI deposit/redeem; `EconomyService`, `EconomyApiImpl`, `PersistentAuditSink`, `HistoryService`, `RollbackService`, `LeaderboardService`, banknotes, bank GUI, and optional Vault/PAPI integrations; six commands (`money`, `pay`, `withdraw`, `baltop`, `bank`, `aceeco`) and the eight `aceeco` subcommands `give`, `take`, `set`, `history`, `reload`, `rollback`, `backup`, `restore`.
- **Wired but still awaiting live verification:** `/aceeco history`, `/aceeco rollback`, and the managed backup/restore operations. The canonical commands are `/aceeco backup [label]` and `/aceeco restore <backup-id> confirm`; there are no `/backup` or `/restore` root commands. `restore` is console-only, rejects online players, accepts only lowercase `confirm`, runs JSON/schema/records/currency preflight, creates a safety backup, clears leaderboard cache after success, and does not hot-refresh sessions or GUI.
- JSON, SQLite, and MySQL share the v2 logical JSON snapshot. MySQL uses a logical snapshot, not a native dump, so it does not replace `mysqldump`, `mariadb-dump`, or database administration backups.
- **Essentials/CMI import:** removed from this Plan and v2.0.0; a retained `ImportService` is not product availability.
- **Unverified:** live Folia/Bukkit validation (including Folia 26.1.2 fresh install, RCON/in-game checks, fault drills, and backup/restore rehearsal), live MySQL, and cross-process smoke remain v2.0.0 release gates. Unit tests and v1 `lang/messages_*.yml` dead keys do not establish production availability; v2 locales are `lang/<locale>.yml`.
