# AceEconomy v2.0.0 上线切换（cutover）：入口、生命周期、依赖与回退

[English](cutover.md) · 简体中文 · [繁體中文](cutover.zh-TW.md)

本文是给发布负责人和维护者看的，记下 v2.0.0 上线切换（cutover）中最容易搞错的事实：`CompositionRoot` 与轻量入口 `AceEconomy`、start/stop/反向拆除与资源归属、运行时基线与外部依赖语义、shadow JAR 内容、v1 干净重写的移除状态，以及安装与回退注意事项。

当前的源码、构建配置和运行时证据并不是同一件事。共用的 Folia 测试服在 Folia `26.2-4-ver/26.2.x`、Java 25、AceLib `1.0.0`、AceEconomy `2.0.0`、Vault `2.20.2`、PlaceholderAPI `2.12.3` 上完成了全新启动、重启与基本 RCON 检查。这些结果不能替代同版本 Folia 26.1.2 的证据。尚未完成的项目列在[v2.0.0 发布验证](#v2000-release-validation已完成与仍待验证)。

## 目录

- [版本与 runtime 基线](#版本与-runtime-基线)
  - [外部依赖的 runtime 语意](#外部依赖的-runtime-语意)
- [入口与 CompositionRoot](#入口与-compositionroot)
  - [轻量入口：`AceEconomy`](#轻量入口-aceeconomy)
  - [模块顺序与资源所有权](#模块顺序与资源所有权)
  - [`ModuleLifecycle`：start、rollback 与 stop](#modulelifecyclestartrollback-与-stop)
  - [`ResourceOwner`：cleanup 顺序与幂等](#resourceownercleanup-顺序与幂等)
  - [`AceLibModule` 与 `AceLibAccess`：facade 解析](#acelibmodule-与-acelibaccessfacade-解析)
- [Shadow JAR](#shadow-jar)
  - [打包内容](#打包内容)
  - [当前 inspection 方式](#当前-inspection-方式)
- [Clean-slate：已移除的 v1 组件](#clean-slate已移除的-v1-组件)
  - [移除状态](#移除状态)
  - [v2 替代项](#v2-替代项)
- [数据与 config：不 migration、不 downgrade](#数据与-config不-migration不-downgrade)
- [安装与 rollback 注意事项](#安装与-rollback-注意事项)
  - [全新安装](#全新安装)
  - [cutover 前备份](#cutover-前备份)
  - [rollback](#rollback)
  - [v2.0/v2.1 scope boundary](#v20v21-scope-boundary)
- [v2.0.0 发布验证：已完成与仍待验证](#v2000-release-validation已完成与仍待验证)
- [证据来源](#证据来源)
- [未确定事项](#未确定事项)

## 版本与 runtime 基线

| 项目 | 值 | 来源 |
|---|---|---|
| 插件版本 | `2.1.0` | `build.gradle.kts` `version = "2.1.0"` |
| Java | 25（`JavaLanguageVersion.of(25)`；`compileJava` 与 `compileV2Foundation` 都是 `options.release.set(25)`） | `build.gradle.kts` |
| Paper/Folia | 26.1.2（paperweight dev bundle `26.1.2.build.74-stable`；`plugin.yml` `api-version: 1.26`、`folia-supported: true`） | `build.gradle.kts`、`plugin.yml` |
| AceLib | `com.github.smile-minecraft:AceLib:v1.2.0`（`compileOnly`；runtime 由外部 JAR 提供，**禁止 shade**） | `build.gradle.kts` |
| Vault | `com.github.MilkBowl:VaultAPI:1.7.1`（`compileOnly`） | `build.gradle.kts` |
| PlaceholderAPI | `me.clip:placeholderapi:2.11.6`（`compileOnly`） | `build.gradle.kts` |

### 外部依赖的 runtime 语意

- **AceLib：runtime hard dependency。** `plugin.yml` 的 `depend: [AceLib]` 使 Paper 在缺少 AceLib 时不启用本插件。`CompositionRoot.requireApi()` 在 facade 未 ready 时也会抛出 `IllegalStateException("AceLib is missing or not ready")`，`AceEconomy.onEnable()` 捕获后调用 `disablePlugin(this)`。
- **Vault：optional。** `plugin.yml` 使用 `softdepend: [Vault, PlaceholderAPI]`；只有 `Bukkit.getPluginManager().isPluginEnabled("Vault")` 为真时才创建 `VaultIntegrationModule`，未安装或未启用时跳过且不注册 provider。
- **PlaceholderAPI：optional。** 创建 `PlaceholderIntegrationModule` 使用同一个启用 gate。

如果早期计划写 Vault 是 hard dependency，应以实际 `plugin.yml` 的 `softdepend` 为准。

## 入口与 CompositionRoot

### 轻量入口：`AceEconomy`

`src/main/java/com/smile/aceeconomy/AceEconomy.java`（28 行）只创建并启动 `CompositionRoot`；`start()` 失败时记录 severe 并 `disablePlugin(this)`；停用时只有 `root != null` 才执行 `stop()` 并清空 root。主 class 不保存经济规则或 service 字段，所有 v2 构建和 teardown 都在 `CompositionRoot`。

### 模块顺序与资源所有权

`CompositionRoot.registerModules()` 按依赖顺序注册七个模块；每个模块通过 `ResourceOwner` 登记 cleanup callback，`ModuleLifecycle` 按反向顺序 teardown。

| 顺序 | 模块 | start 动作 | stop 动作 | ResourceOwner 资源 |
|---|---|---|---|---|
| 1 | `configuration` | `ConfigLangAdapter` 的 `config.load()`，v2 schema `version: "2.0"` | 无 | — |
| 2 | `persistence` | 创建 fixed pool 2 的 daemon `ioExecutor`（`aceeconomy-v2-io`）；解析 typed `StorageConfig`；`json`（默认）→ `JsonPersistenceBackend`、`sqlite` → `SqlBackend + SqliteDialect`、`mysql` → `SqlBackend + MySqlDialect`，HikariCP 来自 `storage.mysql.*` | `stopPersistence()` → `persistence.close()` | `ioExecutor::shutdown`、`persistence::close` |
| 3 | `application` | `buildCurrencies()`、`Clock`、`InMemoryTransactionEventPublisher`、`DebtPolicy`、`EconomyService`、`EconomyApiImpl` | 无 | — |
| 4 | `acelib-runtime`（`RuntimeModule extends AceLibModule`） | 解析 ready facade；创建 `SafeScheduler`、`SafeEventRegistry`、取得 `GuiService`、注册 GUI listener | `runtimeGui.shutdown()`、`scheduler = null` | `scheduler::cancelAll`、`events::unregisterAll`、`HandlerList.unregisterAll(guiListener)` |
| 5 | `sessions` | 创建 `SafeSchedulerFoliaContext`、`AsyncAccountSessionStore`、`PlayerSessionManager(store, folia, 5000ms)` 与 join/quit listener | `sessions.disable(5000ms)` | `HandlerList.unregisterAll(listener)` |
| 6 | `presentation` | 创建 `V2BanknoteFactory`、`ProductionAdapters.*`、`CommandServices`、`V2CommandRegistry`、`CommandRegistryImpl(BukkitReplySink)`、`BukkitCommandBridge`，attach 六个指令 | `commandRegistry.onPluginDisable()` | `commandRegistry::onPluginDisable` |
| 7 | `integrations` | 按 Vault/PAPI 启用状态创建模块并启动 `ExternalIntegrationCoordinator` | `integrations.stop()` | `integrations::stop` |

`ioExecutor` 与 persistence 都会 close；`JsonPersistenceBackend.close()` 幂等，`SqlBackend.close()` 关闭 `Connection`／HikariCP `DataSource`，所以 owner cleanup 再 close 一次也安全。AceLib scheduler/event cleanup 创建后立即登记；sessions 使用 `SESSION_SHUTDOWN_DEADLINE_MILLIS = 5_000L` 的 5 秒 bounded flush。integration readiness 非 `READY` 会保持 `DISABLED`；`initialize()` 失败会先 `shutdown()` 再标记 `FAILED`。join 在 `ioExecutor` 创建账户，再经 `scheduler.runForPlayer()` login；quit 调用 `sessions.quit(uuid, deadline)`，玩家操作都经过 `SafeSchedulerFoliaContext`。

### `ModuleLifecycle`：start、rollback 与 stop

按注册顺序启动。模块 N 失败时先 close N 的 owner，再将成功模块 0..N-1 反向 `stop()` 并 close owner；原始 failure rethrow，其余错误用 `addSuppressed` 附上，并标记 `stopped = true`。正常 stop 也是反向顺序、先 stop 再 close，`stopAll()` 幂等；`startAll()` 后调用 `add()` 会抛出 `IllegalStateException`。

### `ResourceOwner`：cleanup 顺序与幂等

`register(Runnable)` 登记 callback；`close()` 按反向注册顺序执行，每个 callback 至多一次。close 后 register 会抛出 `IllegalStateException`。某个 cleanup 抛出 `RuntimeException` 时仍会执行其他 cleanup，第一项 rethrow，其余 suppressed。

### `AceLibModule` 与 `AceLibAccess`：facade 解析

`AceLibAccess.resolveReadyApi()` 每次都通过 Bukkit `ServicesManager` 重新解析 `AceLibApi.AceLibProvider` 并检查 `isReady()`，不缓存 stale facade；缺少 registration 是正常的 `Optional.empty()`。facade 未 ready 时 `AceLibModule.start()` 拒绝启动，让 lifecycle rollback；模块未启动时 `api()`、`scheduler()`、`events()` 会抛出 `IllegalStateException`。

## Shadow JAR

### 打包内容

`AceLib:v1.2.0`、`VaultAPI:1.7.1`、`PlaceholderAPI:2.11.6` 是 `compileOnly`，不打包。shaded `implementation` 包含 HikariCP `5.1.0`、`slf4j-api:2.0.9`、`slf4j-nop:2.0.9`、SQLite JDBC `3.47.0.0`、MySQL Connector/J `9.1.0`。两个 JDBC driver 都会 shade、由 `minimize { }` 保留，并通过 `mergeServiceFiles()` 合并 `META-INF/services/java.sql.Driver`；不需额外 driver JAR。relocate 为 `com.zaxxer.hikari` → `com.smile.aceeconomy.libs.hikari`、`org.slf4j` → `com.smile.aceeconomy.libs.slf4j`，并排除 `META-INF/*.SF`、`*.DSA`、`*.RSA`。

`jar` 使用 classifier `slim`；`shadowJar` 使用空 classifier 取代默认 JAR；`assemble` 依赖 `shadowJar`。交付物为 `build/libs/AceEconomy-2.1.0.jar`。

### 当前 inspection 方式

验收条件是 consumer JAR 不含 `com/smile/acelib/**`。repo 的 `src/test` 与 CI 没有已提交的自动 inspection 测试或 script；当前手动执行 `./gradlew shadowJar`、`jar tf build/libs/AceEconomy-2.1.0.jar`（或 `unzip -l`），确认没有 `com/smile/acelib/**`，并确认存在 relocate 后的 `com/smile/aceeconomy/libs/hikari/**` 与 `com/smile/aceeconomy/libs/slf4j/**`。本轮 `clean build` 和 `shadowJar` 成功，并完成产出 JAR inspection。

## Clean-slate：已移除的 v1 组件

### 移除状态

v2 是同一 repository 的 clean-slate 重写；v1 source 只作为行为与业务规则参考，不承诺 binary、schema、config 兼容。v1 包目录只剩空目录：`manager/`、`hook/`、`data/`、`event/`、`exception/`、`listener/`、`listeners/`、`migration/`、`service/`、`storage/implementation/`、`utils/`、`zz/`。

已移除集中式 managers（`CurrencyManager`、`ConfigManager`、`LogManager`、`LeaderboardManager`）、legacy storage（`SQLiteImplementation`、`MySQLImplementation`、`JsonStorageHandler`、`SchemaManager`）、v1 commands（`PayCommand`、`RollbackCommand`、`BaltopCommand`、`AdminCommand`）、旧 GUI（`BankMenu`、`GUIListener`）、hooks（`VaultImpl`、`AceEcoExpansion`）、重复 event/listener/webhook、native API（`EconomyProvider`、`EconomyTransactionEvent`）和 reflection command registrar。v2 使用 AceLib `CommandSpec` 显式构建，通过 `CommandRegistryImpl`/`BukkitCommandBridge`/`V2CommandRegistry` 注册，没有 reflection 路径。grep `src/main` 未发现上述 v1 class 名称残留；`VaultEconomyProvider` 是 v2 实现，`ConfigManager` 是 AceLib 公共 API。

### v2 替代项

| 移除的 v1 | v2 替代 |
|---|---|
| `CurrencyManager` | `domain.CurrencyRegistry` + `application.EconomyService` |
| v1 `ConfigManager` | `infrastructure.acelib.ConfigLangAdapter`（AceLib `ConfigManager` + `V2ConfigSchema`） |
| v1 storage 三件 | `JsonPersistenceBackend`（v2.0.0 默认），或 `PersistenceBackendFactory` 的 `SqlBackend` + `SqliteDialect`/`MySqlDialect`；MySQL 使用 `storage.mysql.*` 与 shaded JDBC driver |
| `EconomyProvider` | `api.v2.EconomyApi`，typed `EconomyResult`/`EconomyError`/`TransactionEvent`，javadoc 明确不承诺 v1 binary compatibility |
| v1 listeners | `bootstrap.PlayerSessionListener`，join/quit 使用 Folia-safe dispatch |
| v1 hooks | `VaultEconomyProvider`、`AceEconomyExpansion` |

## 数据与 config：不 migration、不 downgrade

`config.yml` 是 v2 schema，`version: "2.0"`；文件头说明不会读取或迁移 v1 `config-version`。production persistence 由 `config.storage.type` 选择，默认是 `plugins/AceEconomy/data-v2.json` 的 JSON；SQLite/MySQL 使用 `storage.type: sqlite`/`mysql`。v2 JSON 的 `schemaVersion` 当前为 `1`；不兼容版本会抛出 `PersistenceException`，备份后需要 `truncateAndRecreate()`。不提供 v1→v2 data/config migration、v1 native API binary compatibility 或 v2→v1 downgrade。

`restore(InputStream)` 会先完整解析并验证 well-formed JSON 与 `schemaVersion == 1`，才接触 live data；坏备份会保留现有数据。JSON 与 SQL 共用 v2 logical model，但旧 v1 backup 不因此兼容。

## 安装与 rollback 注意事项

### 全新安装

1. 使用 Java 25 的 Paper/Folia 26.1.2（`api-version 1.26`、`folia-supported: true`）。
2. 将 `AceLib-1.2.0.jar` 与 `AceEconomy-2.1.0.jar` 放入 `plugins/`；AceLib 是 hard dependency。
3. Vault 或 VaultUnlocked、PlaceholderAPI 是可选整合，缺少时跳过。
4. 重启服务器；不要用 Bukkit `/reload` 验证生命周期。
5. 首次启动创建 `config.yml`、`lang/<locale>.yml` 和 `data-v2.json`。

### cutover 前备份

cutover 前做完整服务器备份，至少包含 `plugins/AceEconomy/` 与 `config.yml`。v2 JSON backup/restore 使用 persistence layer 的 `backup()`/`restore()`，JSON 与 SQL 共用 v2 JSON model；详见 [`persistence.zh-CN.md`](persistence.zh-CN.md)。

### rollback

v2→v1 downgrade 不支持。v1.4.0 不会读取 `data-v2.json` 或 `version: "2.0"` config。唯一回退路径是恢复完整 cutover 前备份（含 v1 数据和配置），并移除 v2 生成的 `data-v2.json`。依赖 v1 native API、banknote schema 或旧指令表面的第三方插件会失效。

### v2.0/v2.1 scope boundary

**v2.0.0 已接线：** JSON（默认）、SQLite、MySQL；`EconomyService`、`EconomyApiImpl`、`PersistentAuditSink`、`HistoryService`（`/aceeco history`）、`RollbackService`（`/aceeco rollback <transaction-id>`）、`LeaderboardService`（`/baltop`）、banknotes（`/withdraw cash`）、bank GUI（`/bank`）与 Vault/PAPI。`V2CommandRegistry` 注册六个指令和 `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore` 子指令；rollback、restore 是 console-only destructive，backup 可由授权玩家或主控台执行。

**仍待验证：** live history/rollback、`/aceeco backup [label]`、`/aceeco restore <backup-id> confirm`、live MySQL、玩家/GUI、真实服务器 backup/restore、failure injection 与更广泛的 `PersistentIdempotencyGuard`。restore 只接受精确小写 `confirm`，拒绝在线玩家，先做 JSON/schema/records/currency preflight，创建 safety backup，成功后清除 leaderboard cache，但不热刷新 session/GUI；玩家回来前要重启。MySQL logical snapshot 不能取代 native dump。`lang/messages_*.yml` 是 v1 dead keys，v2 语言是 `lang/<locale>.yml`（`en_US`/`zh_TW`/`zh_CN`）。Essentials/CMI import 已移除，保留 `ImportService` 不代表产品可用。

## v2.0.0 发布验证：已完成与仍待验证

共用 Folia 测试服务器在 Folia `26.2-4-ver/26.2.x`（Minecraft 26.2）、Java 25、AceLib `1.0.0`、AceEconomy `2.0.0`、Vault `2.20.2`、PlaceholderAPI `2.12.3` 完成 fresh start 和第二次 restart；不代表 Folia 26.1.2 同版本证据。此历史记录早于当前 AceLib v1.2.0 runtime 基线；新安装以[版本与 runtime 基线](#版本与-runtime-基线)中的 AceLib 版本为准。

已完成：只有一个 `AceLib 1.0.0`、`AceEconomy 2.0.0` 成功启用且没有 `MemorySection` 启动错误、创建 `plugins/AceEconomy/data-v2.json`；移除旧 `AceLib-0.5.0-SNAPSHOT.jar`；RCON 执行 `plugins`、`aceeco`、`money`、`pay`、`withdraw`、`baltop`、`bank` help、`aceeco reload` 和 stop；保留 parser regression log 与测试；full suite **291 tests、0 failures/errors**，parser target **23 tests、0 failures/errors**；`clean build` 和 `shadowJar` 成功；JAR inspection 中 `com/smile/acelib/**`、`org/bukkit/**`、`net/milkbowl/**`、`me/clip/**` 为 0，SQLite、MySQL、Hikari 和 service markers 存在。`SHA256SUMS` 已更新，本文不重复 hash。

仍待完成：

- [ ] Folia 26.1.2 全新 data folder 的同版本验证。
- [ ] 实际连接 live MySQL。
- [ ] 玩家登录与游戏内 GUI click。
- [ ] 真实服务器 backup/restore 与清除后 restore。
- [ ] DB failure、AceLib non-ready、external integration failure、shutdown in-flight failure injection。
- [ ] GitHub tag、release 和 push 仍需发布者明确授权。

## 证据来源

`build.gradle.kts`、`src/main/resources/plugin.yml`、`AceEconomy.java`、`CompositionRoot.java`、`ModuleLifecycle.java`、`ResourceOwner.java`、`LifecycleModule.java`、`AceLibModule.java`、`AceLibAccess.java`、`AceLibConsumer.java`；persistence、config、API、command 和 integration source；`.opencode/plans/plan-20260816-aceeconomy-v2-acelib-rewrite.md`；`Test-Server-Folia/logs/v2-release-final.log`、`v2-memorysection-fix.log`、`v2-memorysection-restart.log`、`v2-release-start.log`。

## 未确定事项

Folia 26.1.2 同版本 runtime、live MySQL、玩家／GUI 流程、failure injection 和真实服务器 backup/restore 仍未验证。Vault 依赖文字必须遵循实际 `softdepend`。Essentials/CMI import 不属于 v2.0.0 production wiring；History 与 Rollback 已接线，但仍缺 live server/Bukkit bridge 证据。
