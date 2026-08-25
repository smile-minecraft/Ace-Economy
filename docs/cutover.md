# AceEconomy v2.0.0 cutover: entrypoint, lifecycle, dependencies, and rollback

English · [简体中文](cutover.zh-CN.md) · [繁體中文](cutover.zh-TW.md)

This document is for release operators and maintainers. It records the v2.0.0 cutover facts that are easiest to get wrong: `CompositionRoot` and the thin `AceEconomy` entrypoint, start/stop/reverse teardown and resource ownership, the runtime baseline and external dependency semantics, shadow JAR contents, the v1 clean-slate removal state, and installation and rollback cautions.

The current source, build configuration, and runtime evidence are not identical claims. The shared Folia test server completed fresh start, restart, and basic RCON checks on Folia `26.2-4-ver/26.2.x`, Java 25, AceLib `1.0.0`, AceEconomy `2.0.0`, Vault `2.20.2`, and PlaceholderAPI `2.12.3`. Those results do not substitute for same-version Folia 26.1.2 evidence. Unfinished items remain listed in [v2.0.0 release validation](#v200-release-validation-completed-and-open).

## Contents

- [Version and runtime baseline](#version-and-runtime-baseline)
  - [External dependency semantics](#external-dependency-semantics)
- [Entrypoint and CompositionRoot](#entrypoint-and-compositionroot)
  - [Thin entrypoint: `AceEconomy`](#thin-entrypoint-aceeconomy)
  - [Module order and resource ownership](#module-order-and-resource-ownership)
  - [`ModuleLifecycle`: start, rollback, and stop](#modulelifecycle-start-rollback-and-stop)
  - [`ResourceOwner`: cleanup order and idempotency](#resourceowner-cleanup-order-and-idempotency)
  - [`AceLibModule` and `AceLibAccess`: facade resolution](#acelibmodule-and-acelibaccess-facade-resolution)
- [Shadow JAR](#shadow-jar)
  - [Packaged contents](#packaged-contents)
  - [Current inspection method](#current-inspection-method)
- [Clean-slate: removed v1 components](#clean-slate-removed-v1-components)
  - [Removal state](#removal-state)
  - [v2 replacements](#v2-replacements)
- [Data and config: no migration, no downgrade](#data-and-config-no-migration-no-downgrade)
- [Installation and rollback cautions](#installation-and-rollback-cautions)
  - [Fresh install](#fresh-install)
  - [Pre-cutover backup](#pre-cutover-backup)
  - [Rollback](#rollback)
  - [v2.0/v2.1 scope boundary](#v20v21-scope-boundary)
- [v2.0.0 release validation: completed and open](#v200-release-validation-completed-and-open)
- [Evidence sources](#evidence-sources)
- [Unresolved items](#unresolved-items)

## Version and runtime baseline

| Item | Value | Source |
|---|---|---|
| Plugin version | `2.1.0` | `build.gradle.kts` `version = "2.1.0"` |
| Java | 25 (`JavaLanguageVersion.of(25)`; `compileJava` and `compileV2Foundation` both use `options.release.set(25)`) | `build.gradle.kts` |
| Paper/Folia | 26.1.2 (paperweight dev bundle `26.1.2.build.74-stable`; `plugin.yml` `api-version: 1.26`, `folia-supported: true`) | `build.gradle.kts`, `plugin.yml` |
| AceLib | `com.github.smile-minecraft:AceLib:v1.0.0` (`compileOnly`; runtime supplied by an external JAR; **must not be shaded**) | `build.gradle.kts` |
| Vault | `com.github.MilkBowl:VaultAPI:1.7.1` (`compileOnly`) | `build.gradle.kts` |
| PlaceholderAPI | `me.clip:placeholderapi:2.11.6` (`compileOnly`) | `build.gradle.kts` |

### External dependency semantics

- **AceLib: runtime hard dependency.** `plugin.yml` declares `depend: [AceLib]`; Paper does not enable this plugin without AceLib. `CompositionRoot.requireApi()` also throws `IllegalStateException("AceLib is missing or not ready")` when the facade is not ready, and `AceEconomy.onEnable()` catches it and calls `disablePlugin(this)`. Both protections exist.
- **Vault: optional.** `plugin.yml` declares `softdepend: [Vault, PlaceholderAPI]`; `CompositionRoot.startIntegrations()` creates `VaultIntegrationModule` only when `Bukkit.getPluginManager().isPluginEnabled("Vault")` is true. If Vault is absent or disabled, the module is skipped and no provider is registered.
- **PlaceholderAPI: optional.** The same gate applies before creating `PlaceholderIntegrationModule`.

The implementation is authoritative when it differs from an earlier plan statement: an earlier plan section described Vault as a hard dependency, but the actual `plugin.yml` uses `softdepend`.

## Entrypoint and CompositionRoot

### Thin entrypoint: `AceEconomy`

`src/main/java/com/smile/aceeconomy/AceEconomy.java` is a 28-line entrypoint that only constructs and starts `CompositionRoot`, logs a severe failure and disables the plugin when `start()` throws, and stops and clears the root when `onDisable()` runs. It does not hold economy rules or service fields; all v2 construction and teardown are in `CompositionRoot`. `stop()` runs only when `root != null`, and therefore once for a normal lifecycle.

### Module order and resource ownership

`CompositionRoot.registerModules()` registers seven modules in dependency order. Each module registers external-resource cleanup callbacks through `ResourceOwner`; `ModuleLifecycle` tears them down in reverse order.

| Order | Module | Start action | Stop action | ResourceOwner resources |
|---|---|---|---|---|
| 1 | `configuration` | `config.load()` through `ConfigLangAdapter`; v2 schema `version: "2.0"` | None | — |
| 2 | `persistence` | Creates fixed-pool-2 daemon `ioExecutor` named `aceeconomy-v2-io`; parses `config.storage` into typed `StorageConfig`; `PersistenceBackendFactory.create(...)` wires `json` (default) to `JsonPersistenceBackend`, `sqlite` to `SqlBackend + SqliteDialect`, or `mysql` to `SqlBackend + MySqlDialect`, with HikariCP from `storage.mysql.*` | `stopPersistence()` → `persistence.close()` | `ioExecutor::shutdown`, `persistence::close` |
| 3 | `application` | `buildCurrencies()`, `Clock`, `InMemoryTransactionEventPublisher`, `DebtPolicy`, `EconomyService`, `EconomyApiImpl` | None | — |
| 4 | `acelib-runtime` (`RuntimeModule extends AceLibModule`) | Resolves the ready facade; creates `SafeScheduler` and `SafeEventRegistry`; obtains `GuiService`; registers the GUI listener | `onStop()`: `runtimeGui.shutdown()`, `scheduler = null` | `scheduler::cancelAll`, `events::unregisterAll`, `HandlerList.unregisterAll(guiListener)` |
| 5 | `sessions` | Creates `SafeSchedulerFoliaContext(scheduler)`, `AsyncAccountSessionStore(persistence, ioExecutor)`, `PlayerSessionManager(store, folia, 5000ms)`, and the join/quit `PlayerSessionListener` | `stopSessions()` → `sessions.disable(5000ms)` | `HandlerList.unregisterAll(listener)` |
| 6 | `presentation` | Creates `V2BanknoteFactory`, `ProductionAdapters.*`, `CommandServices`, `V2CommandRegistry`, `CommandRegistryImpl(BukkitReplySink)`, and `BukkitCommandBridge`; attaches six commands | `stopPresentation()` → `commandRegistry.onPluginDisable()` | `commandRegistry::onPluginDisable` |
| 7 | `integrations` | Creates integration modules according to Vault/PAPI enablement and starts `ExternalIntegrationCoordinator` | `stopIntegrations()` → `integrations.stop()` | `integrations::stop` |

Important ownership details:

- `ioExecutor::shutdown` and `persistence::close` are both registered; `stopPersistence()` also closes persistence. `JsonPersistenceBackend.close()` is idempotent (`initialized=false`); `SqlBackend.close()` closes its `Connection`/HikariCP `DataSource` best-effort, so owner cleanup closing again is safe.
- `AceLibModule.start()` registers scheduler cancellation immediately, then event unregistration immediately; if `onStart` fails later, both still tear down.
- Sessions use the 5-second `SESSION_SHUTDOWN_DEADLINE_MILLIS = 5_000L` deadline for bounded flush.
- `ExternalIntegrationCoordinator` probes AceLib external-service readiness. A non-`READY` module stays `DISABLED`; failed `initialize()` calls `shutdown()` before marking `FAILED`.
- Join handling calls `economy.createAccount(...)` on `ioExecutor`, then `sessions.login(...)` through `scheduler.runForPlayer(...)`; quit handling calls `sessions.quit(uuid, deadline)`. Player operations use `SafeSchedulerFoliaContext`.

### `ModuleLifecycle`: start, rollback, and stop

`ModuleLifecycle.java` starts modules in registration order and gives each module its own `ResourceOwner`. If module N fails: it closes N's owner first, stops successful modules 0..N-1 in reverse order and closes their owners, rethrows the original failure, and attaches stop/close failures with `addSuppressed`. It then marks `stopped = true`; later `stopAll()` is a no-op. A normal stop follows the same reverse order, runs each `stop()` before owner close, is idempotent, rethrows the first error, and suppresses the rest. Calling `add()` after `startAll()` throws `IllegalStateException`.

### `ResourceOwner`: cleanup order and idempotency

`ResourceOwner.register(Runnable)` records callbacks. `close()` runs them in reverse registration order and each callback at most once. Registering after close throws `IllegalStateException`. If a cleanup throws `RuntimeException`, remaining cleanup still runs; the first exception is rethrown and later ones are suppressed.

### `AceLibModule` and `AceLibAccess`: facade resolution

`AceLibAccess.resolveReadyApi()` re-resolves `AceLibApi.AceLibProvider` through Bukkit `ServicesManager` on every call and gates on `isReady()`. It does not retain a stale facade; missing registration is the normal `Optional.empty()` branch. `AceLibModule.start()` rejects a non-ready facade with `IllegalStateException`, allowing lifecycle rollback. Accessors `api()`, `scheduler()`, and `events()` throw `IllegalStateException` before the module starts.

## Shadow JAR

### Packaged contents

`build.gradle.kts` marks `AceLib:v1.0.0`, `VaultAPI:1.7.1`, and `PlaceholderAPI:2.11.6` as `compileOnly`, so runtime supplies them. Shaded `implementation` dependencies are HikariCP `5.1.0`, `slf4j-api:2.0.9`, `slf4j-nop:2.0.9`, SQLite JDBC `3.47.0.0`, and MySQL Connector/J `9.1.0`. Both JDBC drivers are shaded, retained by `minimize { }`, and included through merged `META-INF/services/java.sql.Driver`; operators do not need separate driver JARs. Relocations are `com.zaxxer.hikari` → `com.smile.aceeconomy.libs.hikari` and `org.slf4j` → `com.smile.aceeconomy.libs.slf4j`. Signature metadata `META-INF/*.SF`, `*.DSA`, and `*.RSA` is excluded.

The `jar` task uses classifier `slim`; `shadowJar` uses the empty classifier and replaces the default JAR; `assemble` depends on `shadowJar`. The artifact is `build/libs/AceEconomy-2.1.0.jar`.

### Current inspection method

The acceptance condition is that the consumer JAR contains no `com/smile/acelib/**`. There is no committed automated inspection test or script in `src/test` or CI. The current manual check is:

1. `./gradlew shadowJar`
2. `jar tf build/libs/AceEconomy-2.1.0.jar` (or `unzip -l`)
3. Confirm no `com/smile/acelib/**` entry.
4. Confirm relocated `com/smile/aceeconomy/libs/hikari/**` and `com/smile/aceeconomy/libs/slf4j/**` entries.

This round ran `clean build` and `shadowJar` successfully and completed the inspection with the produced JAR.

## Clean-slate: removed v1 components

### Removal state

v2 is a clean-slate rewrite in the same repository. v1 source is reference for behavior and business rules, not a promise of binary, schema, or config compatibility. The v1 package directories are empty only: `manager/`, `hook/`, `data/`, `event/`, `exception/`, `listener/`, `listeners/`, `migration/`, `service/`, `storage/implementation/`, `utils/`, and `zz/`.

Removed v1 components include centralized managers (`CurrencyManager`, `ConfigManager`, `LogManager`, `LeaderboardManager`), legacy storage (`SQLiteImplementation`, `MySQLImplementation`, `JsonStorageHandler`, `SchemaManager`), v1 commands (`PayCommand`, `RollbackCommand`, `BaltopCommand`, `AdminCommand`), the old bank GUI (`BankMenu`, `GUIListener`), hooks (`VaultImpl`, `AceEcoExpansion`), duplicate event/listener/webhook classes, native API (`EconomyProvider`, `EconomyTransactionEvent`), and the reflection command registrar. v2 commands are explicitly built with AceLib `CommandSpec` and registered through `CommandRegistryImpl`/`BukkitCommandBridge` and `V2CommandRegistry`; there is no reflection registration path. Grep of `src/main` found no listed v1 class names; `VaultEconomyProvider` is a v2 implementation and `ConfigManager` is an AceLib public API.

### v2 replacements

| Removed v1 | v2 replacement |
|---|---|
| `CurrencyManager` | `domain.CurrencyRegistry` + `application.EconomyService` |
| v1 `ConfigManager` | `infrastructure.acelib.ConfigLangAdapter` (AceLib `ConfigManager` + `V2ConfigSchema`) |
| v1 storage trio | `JsonPersistenceBackend` (v2.0.0 default), or `SqlBackend` + `SqliteDialect`/`MySqlDialect` via `PersistenceBackendFactory`; MySQL uses `storage.mysql.*` and shaded JDBC driver |
| `EconomyProvider` | `api.v2.EconomyApi` with typed `EconomyResult`/`EconomyError`/`TransactionEvent`; javadoc states no v1 binary compatibility |
| v1 listeners | `bootstrap.PlayerSessionListener` with join/quit Folia-safe dispatch |
| v1 hooks | `VaultEconomyProvider`, `AceEconomyExpansion` |

## Data and config: no migration, no downgrade

`config.yml` is the v2 schema with `version: "2.0"`; its header says it does not read or migrate the v1 `config-version` surface. Production persistence is selected by `config.storage.type`, defaulting to JSON at `plugins/AceEconomy/data-v2.json`; SQLite and MySQL use `storage.type: sqlite`/`mysql`. The v2 JSON model has `schemaVersion` currently `1`; an incompatible version causes `PersistenceException` and requires `truncateAndRecreate()` after a backup. There is no v1→v2 data or config migration, no v1 native API binary compatibility, and no v2→v1 downgrade.

`restore(InputStream)` fully parses and validates well-formed JSON with `schemaVersion == 1` before touching live data; a bad backup leaves existing data intact. JSON and SQL share the v2 logical model, but that does not make old v1 backups compatible.

## Installation and rollback cautions

### Fresh install

1. Use Java 25 with Paper/Folia 26.1.2 (`api-version 1.26`, `folia-supported: true`).
2. Put `AceLib-1.0.0.jar` and `AceEconomy-2.1.0.jar` in `plugins/`; AceLib is hard dependency.
3. Vault or VaultUnlocked and PlaceholderAPI are optional; absent integrations are skipped.
4. Restart the server. Do not use Bukkit `/reload` to validate lifecycle; the plan explicitly does not use it.
5. The first start creates `config.yml`, `lang/<locale>.yml`, and `data-v2.json`.

### Pre-cutover backup

Make a complete server backup before cutover, including at least `plugins/AceEconomy/` and `config.yml`. v2 JSON backup/restore uses the persistence layer's `backup()`/`restore()` and the shared v2 JSON model across JSON and SQL; see [`persistence.md`](persistence.md).

### Rollback

v2→v1 downgrade is unsupported. v1.4.0 cannot read `data-v2.json` or a `version: "2.0"` config. The only return path is restoring the complete pre-cutover backup, including v1 data and config, and removing v2-generated `data-v2.json`. Removed v1 components are not in the v2 JAR, so third-party plugins that depend on v1 native API, banknote schema, or command surface fail.

### v2.0/v2.1 scope boundary

**Wired for v2.0.0 production:** JSON (default), SQLite, and MySQL through `StorageConfigParser` and `PersistenceBackendFactory`; `EconomyService`, `EconomyApiImpl`, `PersistentAuditSink`, `HistoryService` (`/aceeco history`), `RollbackService` (`/aceeco rollback <transaction-id>`), `LeaderboardService` (`/baltop`), banknotes (`/withdraw cash`), bank GUI (`/bank`), and Vault/PAPI integrations. `V2CommandRegistry` registers six commands and the `give`, `take`, `set`, `history`, `reload`, `rollback`, `backup`, and `restore` subcommands. `rollback` and `restore` are console-only destructive operations; `backup` accepts an authorized player or console.

**Still open:** live `/aceeco history` and `/aceeco rollback`; canonical `/aceeco backup [label]` and `/aceeco restore <backup-id> confirm` management operations; live MySQL; player/GUI; real-server backup/restore; failure injection; and broader `PersistentIdempotencyGuard` coverage. `restore` requires exact lowercase `confirm`, rejects online players, runs JSON/schema/records/currency preflight, creates a safety backup, clears leaderboard cache after success, and does not hot-refresh sessions or GUI; restart before players return. MySQL logical snapshots do not replace native dumps. `lang/messages_*.yml` are v1 dead keys; v2 locales are `lang/<locale>.yml` (`en_US`/`zh_TW`/`zh_CN`). Essentials/CMI import is removed and a retained `ImportService` does not mean product availability.

## v2.0.0 release validation: completed and open

The shared Folia test server completed a fresh start and a second restart on Folia `26.2-4-ver/26.2.x` (Minecraft 26.2), Java 25, AceLib `1.0.0`, AceEconomy `2.0.0`, Vault `2.20.2`, and PlaceholderAPI `2.12.3`. It is not same-version Folia 26.1.2 evidence.

Completed checks: one active AceLib (`AceLib 1.0.0`), successful `AceEconomy 2.0.0` enable without the `MemorySection` startup error, and creation of `plugins/AceEconomy/data-v2.json`; removal of old `AceLib-0.5.0-SNAPSHOT.jar`; RCON `plugins`, `aceeco`, `money`, `pay`, `withdraw`, `baltop`, `bank` help checks, `aceeco reload`, and RCON stop; parser regression logs and tests; full suite **291 tests, 0 failures/errors**, parser target **23 tests, 0 failures/errors**; successful `clean build` and `shadowJar`; JAR inspection showing zero `com/smile/acelib/**`, `org/bukkit/**`, `net/milkbowl/**`, and `me/clip/**`, with SQLite, MySQL, Hikari, and service markers present. `SHA256SUMS` was updated; the hash is not repeated here.

Open checks:

- [ ] Repeat fresh-folder verification on Folia 26.1.2.
- [ ] Connect to live MySQL.
- [ ] Verify player login and in-game GUI clicks.
- [ ] Run real-server backup/restore and restore-after-clear.
- [ ] Inject DB failure, AceLib non-ready, external integration failure, and in-flight shutdown failures.
- [ ] Release tag, release, and push require explicit publisher authorization.

## Evidence sources

- `build.gradle.kts`; `src/main/resources/plugin.yml`; `AceEconomy.java`; `CompositionRoot.java`; `ModuleLifecycle.java`; `ResourceOwner.java`; `LifecycleModule.java`; `AceLibModule.java`; `AceLibAccess.java`; `AceLibConsumer.java`.
- `JsonPersistenceBackend.java`; `StorageConfig.java`; `StorageConfigParser.java`; `PersistenceBackendFactory.java`; `StorageBackendKind.java`; `SqlBackend.java`; `SqliteDialect.java`; `MySqlDialect.java`; `V2Schema.java`; `SchemaVersion.java`; `V2ConfigSchema.java`; `ConfigLangAdapter.java`.
- `EconomyApi.java`; `V2CommandRegistry.java`; `ExternalIntegrationCoordinator.java`; source tree and grep results for v1 removal.
- `.opencode/plans/plan-20260816-aceeconomy-v2-acelib-rewrite.md`.
- `Test-Server-Folia/logs/v2-release-final.log`, `v2-memorysection-fix.log`, `v2-memorysection-restart.log`, and `v2-release-start.log`.

## Unresolved items

Folia 26.1.2 same-version runtime, live MySQL, player/GUI flows, failure injection, and real-server backup/restore remain unverified. Vault dependency wording must follow the actual `softdepend` declaration. Essentials/CMI import is not v2.0.0 production wiring; History and Rollback are wired but still lack live server/Bukkit bridge evidence.
