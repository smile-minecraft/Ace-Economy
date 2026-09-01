# AceEconomy v2.0.0 上線切換（cutover）：入口、生命週期、相依性與回退

[English](cutover.md) · [简体中文](cutover.zh-CN.md) · 繁體中文

本文是給發布負責人與維護者看的，記下 v2.0.0 上線切換（cutover）中最容易搞錯的事實：`CompositionRoot` 與輕量入口 `AceEconomy`、start/stop/反向拆除與資源歸屬、執行時基線與外部相依性語意、shadow JAR 內容、v1 乾淨重寫的移除狀態，以及安裝與回退注意事項。

目前的原始碼、建置設定與執行時證據並不是同一件事。共用 Folia 測試服在 Folia `26.2-4-ver/26.2.x`、Java 25、AceLib `1.0.0`、AceEconomy `2.0.0`、Vault `2.20.2`、PlaceholderAPI `2.12.3` 上完成了全新啟動、重啟與基本 RCON 檢查。這些結果不能替代同版本 Folia 26.1.2 的證據。尚未完成的事項列在[v2.0.0 發布驗證](#v2000-release-validation已完成與仍待補驗)。

## 目錄

- [版本與 runtime 基線](#版本與-runtime-基線)
  - [外部相依性的 runtime 語意](#外部相依性的-runtime-語意)
- [入口與 CompositionRoot](#入口與-compositionroot)
  - [輕量入口：`AceEconomy`](#輕量入口-aceeconomy)
  - [模組順序與資源所有權](#模組順序與資源所有權)
  - [`ModuleLifecycle`：start、rollback 與 stop](#modulelifecyclestartrollback-與-stop)
  - [`ResourceOwner`：cleanup 順序與冪等](#resourceownercleanup-順序與冪等)
  - [`AceLibModule` 與 `AceLibAccess`：facade 解析](#acelibmodule-與-acelibaccessfacade-解析)
- [Shadow JAR](#shadow-jar)
  - [打包內容](#打包內容)
  - [目前 inspection 方式](#目前-inspection-方式)
- [Clean-slate：已移除的 v1 元件](#clean-slate已移除的-v1-元件)
  - [移除狀態](#移除狀態)
  - [v2 取代項目](#v2-取代項目)
- [資料與 config：不 migration、不 downgrade](#資料與-config不-migration不-downgrade)
- [安裝與 rollback 注意事項](#安裝與-rollback-注意事項)
  - [全新安裝](#全新安裝)
  - [cutover 前備份](#cutover-前備份)
  - [rollback](#rollback)
  - [v2.0/v2.1 scope boundary](#v20v21-scope-boundary)
- [v2.0.0 發布驗證：已完成與仍待補驗](#v2000-release-validation已完成與仍待補驗)
- [證據來源](#證據來源)
- [未確定事項](#未確定事項)

## 版本與 runtime 基線

| 項目 | 值 | 來源 |
|---|---|---|
| 插件版本 | `2.1.0` | `build.gradle.kts` `version = "2.1.0"` |
| Java | 25（`JavaLanguageVersion.of(25)`；`compileJava` 與 `compileV2Foundation` 都是 `options.release.set(25)`） | `build.gradle.kts` |
| Paper/Folia | 26.1.2（paperweight dev bundle `26.1.2.build.74-stable`；`plugin.yml` `api-version: 1.26`、`folia-supported: true`） | `build.gradle.kts`、`plugin.yml` |
| AceLib | `com.github.smile-minecraft:AceLib:v1.2.0`（`compileOnly`；runtime 由外部 JAR 提供，**禁止 shade**） | `build.gradle.kts` |
| Vault | `com.github.MilkBowl:VaultAPI:1.7.1`（`compileOnly`） | `build.gradle.kts` |
| PlaceholderAPI | `me.clip:placeholderapi:2.11.6`（`compileOnly`） | `build.gradle.kts` |

### 外部相依性的 runtime 語意

- **AceLib：runtime hard dependency。** `plugin.yml` 的 `depend: [AceLib]` 讓缺少 AceLib 時 Paper 不啟用本插件。`CompositionRoot.requireApi()` 在 facade 未 ready 時也會丟 `IllegalStateException("AceLib is missing or not ready")`，`AceEconomy.onEnable()` 捕捉後呼叫 `disablePlugin(this)`。
- **Vault：optional。** `plugin.yml` 使用 `softdepend: [Vault, PlaceholderAPI]`；只有 `Bukkit.getPluginManager().isPluginEnabled("Vault")` 為真時才建立 `VaultIntegrationModule`，未安裝或未啟用就略過且不註冊 provider。
- **PlaceholderAPI：optional。** 建立 `PlaceholderIntegrationModule` 使用相同的啟用 gate。

若早期計畫寫 Vault 是 hard dependency，應以實際 `plugin.yml` 的 `softdepend` 為準。

## 入口與 CompositionRoot

### 輕量入口：`AceEconomy`

`src/main/java/com/smile/aceeconomy/AceEconomy.java`（28 行）只建立並啟動 `CompositionRoot`；`start()` 失敗時記錄 severe 並 `disablePlugin(this)`；停用時若 `root != null` 才執行 `stop()` 並清除 root。主 class 不保存經濟規則或 service 欄位，所有 v2 建構與 teardown 都在 `CompositionRoot`。

### 模組順序與資源所有權

`CompositionRoot.registerModules()` 依相依順序註冊七個模組；每個模組以 `ResourceOwner` 登記 cleanup callback，`ModuleLifecycle` 依反向順序 teardown。

| 順序 | 模組 | start 動作 | stop 動作 | ResourceOwner 資源 |
|---|---|---|---|---|
| 1 | `configuration` | `ConfigLangAdapter` 的 `config.load()`，v2 schema `version: "2.0"` | 無 | — |
| 2 | `persistence` | 建立 fixed pool 2 的 daemon `ioExecutor`（`aceeconomy-v2-io`）；解析 typed `StorageConfig`；`json`（預設）→ `JsonPersistenceBackend`、`sqlite` → `SqlBackend + SqliteDialect`、`mysql` → `SqlBackend + MySqlDialect`，HikariCP 來自 `storage.mysql.*` | `stopPersistence()` → `persistence.close()` | `ioExecutor::shutdown`、`persistence::close` |
| 3 | `application` | `buildCurrencies()`、`Clock`、`InMemoryTransactionEventPublisher`、`DebtPolicy`、`EconomyService`、`EconomyApiImpl` | 無 | — |
| 4 | `acelib-runtime`（`RuntimeModule extends AceLibModule`） | 解析 ready facade；建立 `SafeScheduler`、`SafeEventRegistry`、取得 `GuiService`、註冊 GUI listener | `runtimeGui.shutdown()`、`scheduler = null` | `scheduler::cancelAll`、`events::unregisterAll`、`HandlerList.unregisterAll(guiListener)` |
| 5 | `sessions` | 建立 `SafeSchedulerFoliaContext`、`AsyncAccountSessionStore`、`PlayerSessionManager(store, folia, 5000ms)` 與 join/quit listener | `sessions.disable(5000ms)` | `HandlerList.unregisterAll(listener)` |
| 6 | `presentation` | 建立 `V2BanknoteFactory`、`ProductionAdapters.*`、`CommandServices`、`V2CommandRegistry`、`CommandRegistryImpl(BukkitReplySink)`、`BukkitCommandBridge`，attach 六個指令 | `commandRegistry.onPluginDisable()` | `commandRegistry::onPluginDisable` |
| 7 | `integrations` | 依 Vault/PAPI 啟用狀態建立模組並啟動 `ExternalIntegrationCoordinator` | `integrations.stop()` | `integrations::stop` |

`ioExecutor` 與 persistence 都會 close；`JsonPersistenceBackend.close()` 冪等，`SqlBackend.close()` 關閉 `Connection`／HikariCP `DataSource`，因此 owner cleanup 再 close 一次安全。AceLib scheduler/event cleanup 在建立後立即登記；sessions 有 `SESSION_SHUTDOWN_DEADLINE_MILLIS = 5_000L` 的 5 秒 bounded flush。integration readiness 非 `READY` 會保持 `DISABLED`；`initialize()` 失敗先 `shutdown()` 再標記 `FAILED`。join 在 `ioExecutor` 建帳戶，再經 `scheduler.runForPlayer()` login；quit 呼叫 `sessions.quit(uuid, deadline)`，玩家操作都經 `SafeSchedulerFoliaContext`。

### `ModuleLifecycle`：start、rollback 與 stop

依註冊順序啟動。模組 N 失敗時先 close N 的 owner，再對成功模組 0..N-1 反向 `stop()` 並 close owner；原始 failure rethrow，其餘錯誤以 `addSuppressed` 附上，並標記 `stopped = true`。正常 stop 也是反向順序、先 stop 再 close，`stopAll()` 冪等；`startAll()` 後呼叫 `add()` 會丟 `IllegalStateException`。

### `ResourceOwner`：cleanup 順序與冪等

`register(Runnable)` 登記 callback；`close()` 依反向註冊順序執行，每個 callback 至多一次。close 後 register 會丟 `IllegalStateException`。某個 cleanup 丟 `RuntimeException` 時仍執行其餘 cleanup，第一個例外 rethrow，其餘 suppressed。

### `AceLibModule` 與 `AceLibAccess`：facade 解析

`AceLibAccess.resolveReadyApi()` 每次都經 Bukkit `ServicesManager` 重新解析 `AceLibApi.AceLibProvider` 並檢查 `isReady()`，不快取 stale facade；缺少 registration 是正常的 `Optional.empty()`。facade 未 ready 時 `AceLibModule.start()` 拒絕啟動，讓 lifecycle rollback；`api()`、`scheduler()`、`events()` 在模組未啟動時丟 `IllegalStateException`。

## Shadow JAR

### 打包內容

`AceLib:v1.2.0`、`VaultAPI:1.7.1`、`PlaceholderAPI:2.11.6` 是 `compileOnly`，不打包。shaded `implementation` 包含 HikariCP `5.1.0`、`slf4j-api:2.0.9`、`slf4j-nop:2.0.9`、SQLite JDBC `3.47.0.0`、MySQL Connector/J `9.1.0`。兩個 JDBC driver 會 shade、由 `minimize { }` 保留，並以 `mergeServiceFiles()` 合併 `META-INF/services/java.sql.Driver`；不需額外 driver JAR。relocate 為 `com.zaxxer.hikari` → `com.smile.aceeconomy.libs.hikari`、`org.slf4j` → `com.smile.aceeconomy.libs.slf4j`，並排除 `META-INF/*.SF`、`*.DSA`、`*.RSA`。

`jar` 使用 classifier `slim`；`shadowJar` 使用空 classifier 取代預設 JAR；`assemble` 依賴 `shadowJar`。交付物是 `build/libs/AceEconomy-2.1.0.jar`。

### 目前 inspection 方式

驗收條件是 consumer JAR 不包含 `com/smile/acelib/**`。repo 的 `src/test` 與 CI 沒有已提交的自動 inspection 測試或 script，目前手動執行 `./gradlew shadowJar`、`jar tf build/libs/AceEconomy-2.1.0.jar`（或 `unzip -l`），確認沒有 `com/smile/acelib/**`，並確認有 relocate 後的 `com/smile/aceeconomy/libs/hikari/**` 與 `com/smile/aceeconomy/libs/slf4j/**`。本次 `clean build` 與 `shadowJar` 成功，並完成產出 JAR inspection。

## Clean-slate：已移除的 v1 元件

### 移除狀態

v2 是同 repository 的 clean-slate 重寫；v1 source 只作行為與商業規則參考，不承諾 binary、schema、config 相容。v1 套件目錄只剩空目錄：`manager/`、`hook/`、`data/`、`event/`、`exception/`、`listener/`、`listeners/`、`migration/`、`service/`、`storage/implementation/`、`utils/`、`zz/`。

已移除集中式 managers（`CurrencyManager`、`ConfigManager`、`LogManager`、`LeaderboardManager`）、legacy storage（`SQLiteImplementation`、`MySQLImplementation`、`JsonStorageHandler`、`SchemaManager`）、v1 commands（`PayCommand`、`RollbackCommand`、`BaltopCommand`、`AdminCommand`）、舊 GUI（`BankMenu`、`GUIListener`）、hooks（`VaultImpl`、`AceEcoExpansion`）、重複 event/listener/webhook、native API（`EconomyProvider`、`EconomyTransactionEvent`）與 reflection command registrar。v2 以 AceLib `CommandSpec` 顯式建構，經 `CommandRegistryImpl`/`BukkitCommandBridge`/`V2CommandRegistry` 註冊，沒有 reflection 路徑。grep `src/main` 未發現上述 v1 class 名稱殘留；`VaultEconomyProvider` 是 v2 實作，`ConfigManager` 是 AceLib 公開 API。

### v2 取代項目

| v1 移除 | v2 取代 |
|---|---|
| `CurrencyManager` | `domain.CurrencyRegistry` + `application.EconomyService` |
| v1 `ConfigManager` | `infrastructure.acelib.ConfigLangAdapter`（AceLib `ConfigManager` + `V2ConfigSchema`） |
| v1 storage 三件 | `JsonPersistenceBackend`（v2.0.0 預設），或 `PersistenceBackendFactory` 的 `SqlBackend` + `SqliteDialect`/`MySqlDialect`；MySQL 使用 `storage.mysql.*` 與 shaded JDBC driver |
| `EconomyProvider` | `api.v2.EconomyApi`，typed `EconomyResult`/`EconomyError`/`TransactionEvent`，javadoc 明言不承諾 v1 binary compatibility |
| v1 listeners | `bootstrap.PlayerSessionListener`，join/quit 使用 Folia-safe dispatch |
| v1 hooks | `VaultEconomyProvider`、`AceEconomyExpansion` |

## 資料與 config：不 migration、不 downgrade

`config.yml` 是 v2 schema，`version: "2.0"`；檔頭說明不讀取或遷移 v1 `config-version`。production persistence 由 `config.storage.type` 選擇，預設為 `plugins/AceEconomy/data-v2.json` 的 JSON；SQLite/MySQL 使用 `storage.type: sqlite`/`mysql`。v2 JSON 的 `schemaVersion` 目前為 `1`；不相容版本會丟 `PersistenceException`，備份後需 `truncateAndRecreate()`。不提供 v1→v2 data/config migration、v1 native API binary compatibility 或 v2→v1 downgrade。

`restore(InputStream)` 會先完整解析並驗證 well-formed JSON 與 `schemaVersion == 1`，才碰 live data；壞備份會保留現有資料。JSON 與 SQL 共用 v2 logical model，但舊 v1 backup 不因此相容。

## 安裝與 rollback 注意事項

### 全新安裝

1. 使用 Java 25 的 Paper/Folia 26.1.2（`api-version 1.26`、`folia-supported: true`）。
2. 將 `AceLib-1.2.0.jar` 與 `AceEconomy-2.1.0.jar` 放入 `plugins/`；AceLib 是 hard dependency。
3. Vault 或 VaultUnlocked、PlaceholderAPI 是選用整合，缺少時略過。
4. 重啟伺服器；不要用 Bukkit `/reload` 驗證生命週期。
5. 首次啟動建立 `config.yml`、`lang/<locale>.yml` 與 `data-v2.json`。

### cutover 前備份

cutover 前做完整伺服器備份，至少包含 `plugins/AceEconomy/` 與 `config.yml`。v2 JSON backup/restore 使用 persistence layer 的 `backup()`/`restore()`，JSON 與 SQL 共用 v2 JSON model；詳見 [`persistence.zh-TW.md`](persistence.zh-TW.md)。

### rollback

v2→v1 downgrade 不支援。v1.4.0 不會讀取 `data-v2.json` 或 `version: "2.0"` config。唯一回復路徑是還原完整 cutover 前備份（含 v1 資料與設定），並移除 v2 產生的 `data-v2.json`。依賴 v1 native API、banknote schema 或舊指令表面的第三方插件會失效。

### v2.0/v2.1 scope boundary

**v2.0.0 已接線：** JSON（預設）、SQLite、MySQL；`EconomyService`、`EconomyApiImpl`、`PersistentAuditSink`、`HistoryService`（`/aceeco history`）、`RollbackService`（`/aceeco rollback <transaction-id>`）、`LeaderboardService`（`/baltop`）、banknotes（`/withdraw cash`）、bank GUI（`/bank`）與 Vault/PAPI。`V2CommandRegistry` 註冊六個指令與 `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore` 子指令；rollback、restore 是 console-only destructive，backup 可由授權玩家或主控台執行。

**仍待補驗：** live history/rollback、`/aceeco backup [label]`、`/aceeco restore <backup-id> confirm`、live MySQL、玩家/GUI、真實伺服器 backup/restore、failure injection 與更廣泛的 `PersistentIdempotencyGuard`。restore 只接受精確小寫 `confirm`、拒絕有玩家在線、先做 JSON/schema/records/currency preflight、建立 safety backup，成功後清除 leaderboard cache，但不熱刷新 session/GUI；玩家回來前要重啟。MySQL logical snapshot 不能取代 native dump。`lang/messages_*.yml` 是 v1 dead keys，v2 語系為 `lang/<locale>.yml`（`en_US`/`zh_TW`/`zh_CN`）。Essentials/CMI import 已移除，保留 `ImportService` 不代表產品可用。

## v2.0.0 發布驗證：已完成與仍待補驗

共用 Folia 測試服在 Folia `26.2-4-ver/26.2.x`（Minecraft 26.2）、Java 25、AceLib `1.0.0`、AceEconomy `2.0.0`、Vault `2.20.2`、PlaceholderAPI `2.12.3` 完成 fresh start 與第二次 restart；不代表 Folia 26.1.2 同版本證據。此歷史紀錄早於目前的 AceLib v1.2.0 runtime 基線；新安裝以[版本與 runtime 基線](#版本與-runtime-基線)中的 AceLib 版本為準。

已完成：只有一個 `AceLib 1.0.0`、`AceEconomy 2.0.0` 成功啟用且無 `MemorySection` 啟動錯誤、建立 `plugins/AceEconomy/data-v2.json`；移除舊 `AceLib-0.5.0-SNAPSHOT.jar`；RCON 執行 `plugins`、`aceeco`、`money`、`pay`、`withdraw`、`baltop`、`bank` help、`aceeco reload` 與 stop；保留 parser regression log 與測試；full suite **291 tests、0 failures/errors**，parser target **23 tests、0 failures/errors**；`clean build` 與 `shadowJar` 成功；JAR inspection 的 `com/smile/acelib/**`、`org/bukkit/**`、`net/milkbowl/**`、`me/clip/**` 為 0，SQLite、MySQL、Hikari 與 service markers 存在。`SHA256SUMS` 已更新，本文不重複 hash。

仍待完成：

- [ ] Folia 26.1.2 全新 data folder 的同版本驗證。
- [ ] 實際連線 live MySQL。
- [ ] 玩家登入與遊戲內 GUI click。
- [ ] 真實伺服器 backup/restore 與清除後 restore。
- [ ] DB failure、AceLib non-ready、external integration failure、shutdown in-flight failure injection。
- [ ] GitHub tag、release 與 push 仍需發布者明確授權。

## 證據來源

`build.gradle.kts`、`src/main/resources/plugin.yml`、`AceEconomy.java`、`CompositionRoot.java`、`ModuleLifecycle.java`、`ResourceOwner.java`、`LifecycleModule.java`、`AceLibModule.java`、`AceLibAccess.java`、`AceLibConsumer.java`；persistence、config、API、command 與 integration source；`.opencode/plans/plan-20260816-aceeconomy-v2-acelib-rewrite.md`；`Test-Server-Folia/logs/v2-release-final.log`、`v2-memorysection-fix.log`、`v2-memorysection-restart.log`、`v2-release-start.log`。

## 未確定事項

Folia 26.1.2 同版本 runtime、live MySQL、玩家／GUI 流程、failure injection 與真實伺服器 backup/restore 仍未驗證。Vault 依賴文字必須依實際 `softdepend`。Essentials/CMI import 不屬於 v2.0.0 production wiring；History 與 Rollback 已接線，但仍缺 live server/Bukkit bridge 證據。
