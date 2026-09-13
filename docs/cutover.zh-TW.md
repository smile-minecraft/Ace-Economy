# AceEconomy v2.0.0 上線切換（cutover）：入口、生命週期、相依性與回退

[English](cutover.md) · [简体中文](cutover.zh-CN.md) · 繁體中文

本文寫給發布負責人與維護者，記下 v2.0.0 上線切換（cutover）最容易弄錯的事實：`CompositionRoot` 與輕量入口 `AceEconomy`、`start`／`stop`／反向收解與資源歸屬、執行時期基線與外部相依語意、Shadow JAR 內容、v1 全新重寫的移除狀態，以及安裝與回溯注意事項。

目前的原始碼、建置設定與執行時期證據並不是同一件事。共用 Folia 測試服在 Folia `26.2-4-ver/26.2.x`、Java 25、AceLib `1.0.0`、AceEconomy `2.0.0`、Vault `2.20.2`、PlaceholderAPI `2.12.3` 上完成了全新啟動、重啟與基本 RCON 檢查。這些結果不能替代同版本 Folia 26.1.2 的證據。尚未完成的事項列在[v2.0.0 發布驗證](#v2000-release-validation已完成與仍待補驗)。

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
  - [切換前備份](#切換前備份)
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
| AceLib | `com.github.smile-minecraft:AceLib:v1.2.1`（`compileOnly`；執行時期由外部 JAR 提供，**禁止 shade**） | `build.gradle.kts` |
| Vault | `com.github.MilkBowl:VaultAPI:1.7.1`（`compileOnly`） | `build.gradle.kts` |
| PlaceholderAPI | `me.clip:placeholderapi:2.11.6`（`compileOnly`） | `build.gradle.kts` |

### 外部相依性的 runtime 語意

- **AceLib：執行時期的硬性相依。** `plugin.yml` 的 `depend: [AceLib]` 讓缺少 AceLib 時 Paper 不啟用本插件。`CompositionRoot.requireApi()` 在 facade 未就緒時也會拋出 `IllegalStateException("AceLib is missing or not ready")`，`AceEconomy.onEnable()` 捕捉後呼叫 `disablePlugin(this)`。
- **Vault：選用。** `plugin.yml` 使用 `softdepend: [Vault, PlaceholderAPI]`；只有 `Bukkit.getPluginManager().isPluginEnabled("Vault")` 為真時才建立 `VaultIntegrationModule`，未安裝或未啟用就略過且不註冊提供者。
- **PlaceholderAPI：選用。** 建立 `PlaceholderIntegrationModule` 使用相同的啟用驗證關卡。

若早期計畫把 Vault 寫成硬性相依，應以實際 `plugin.yml` 的 `softdepend` 為準。

## 入口與 CompositionRoot

### 輕量入口：`AceEconomy`

`src/main/java/com/smile/aceeconomy/AceEconomy.java`（28 行）只建立並啟動 `CompositionRoot`；`start()` 失敗時記錄 `severe` 並呼叫 `disablePlugin(this)`；停用時若 `root != null` 才執行 `stop()` 並清除 `root`。主類別不保存經濟規則或服務欄位，所有 v2 建構與收解都在 `CompositionRoot`。

### 模組順序與資源所有權

`CompositionRoot.registerModules()` 依相依順序註冊七個模組；每個模組以 `ResourceOwner` 登記清理回呼，`ModuleLifecycle` 依反向順序收解。

| 順序 | 模組 | start 動作 | stop 動作 | ResourceOwner 資源 |
|---|---|---|---|---|
| 1 | `configuration` | `ConfigLangAdapter` 的 `config.load()`，v2 schema `version: "2.0"` | 無 | — |
| 2 | `persistence` | 建立固定大小為 2 的 daemon `ioExecutor`（`aceeconomy-v2-io`）；解析具型別的 `StorageConfig`；`json`（預設）→ `JsonPersistenceBackend`、`sqlite` → `SqlBackend + SqliteDialect`、`mysql` → `SqlBackend + MySqlDialect`，HikariCP 設定來自 `storage.mysql.*` | `stopPersistence()` → `persistence.close()` | `ioExecutor::shutdown`、`persistence::close` |
| 3 | `application` | `buildCurrencies()`、`Clock`、`InMemoryTransactionEventPublisher`、`DebtPolicy`、`EconomyService`、`EconomyApiImpl` | 無 | — |
| 4 | `acelib-runtime`（`RuntimeModule extends AceLibModule`） | 解析就緒的 facade；建立 `SafeScheduler`、`SafeEventRegistry`、取得 `GuiService`、註冊 GUI 監聽器 | `runtimeGui.shutdown()`、`scheduler = null` | `scheduler::cancelAll`、`events::unregisterAll`、`HandlerList.unregisterAll(guiListener)` |
| 5 | `sessions` | 建立 `SafeSchedulerFoliaContext`、`AsyncAccountSessionStore`、`PlayerSessionManager(store, folia, 5000ms)` 與 `join`／`quit` 監聽器 | `sessions.disable(5000ms)` | `HandlerList.unregisterAll(listener)` |
| 6 | `presentation` | 建立 `V2BanknoteFactory`、`ProductionAdapters.*`、`CommandServices`、`V2CommandRegistry`、`CommandRegistryImpl(BukkitReplySink)`、`BukkitCommandBridge`，並接上六個指令 | `commandRegistry.onPluginDisable()` | `commandRegistry::onPluginDisable` |
| 7 | `integrations` | 依 Vault/PAPI 啟用狀態建立模組並啟動 `ExternalIntegrationCoordinator` | `integrations.stop()` | `integrations::stop` |

`ioExecutor` 與 `persistence` 都會關閉；`JsonPersistenceBackend.close()` 具冪等性，`SqlBackend.close()` 會關閉 `Connection`／HikariCP `DataSource`，因此擁有者清理後再關閉一次也安全。AceLib 排程器／事件清理在建立後立即登記；`sessions` 使用 `SESSION_SHUTDOWN_DEADLINE_MILLIS = 5_000L` 的 5 秒有界沖寫。整合就緒狀態非 `READY` 時保持 `DISABLED`；`initialize()` 失敗會先呼叫 `shutdown()` 再標記為 `FAILED`。`join` 在 `ioExecutor` 建立帳戶，再經 `scheduler.runForPlayer()` 登入；`quit` 呼叫 `sessions.quit(uuid, deadline)`，玩家操作都經過 `SafeSchedulerFoliaContext`。

### `ModuleLifecycle`：start、rollback 與 stop

依註冊順序啟動。模組 N 失敗時先關閉 N 的擁有者，再對已成功的模組 0..N-1 反向呼叫 `stop()` 並關閉擁有者；原始失敗重新拋出，其餘錯誤以 `addSuppressed` 附上，並標記 `stopped = true`。正常關閉也是反向順序，先呼叫 `stop()` 再關閉擁有者；`stopAll()` 具冪等性；`startAll()` 後呼叫 `add()` 會拋出 `IllegalStateException`。

### `ResourceOwner`：cleanup 順序與冪等

`register(Runnable)` 登記回呼；`close()` 依反向註冊順序執行，每個回呼至多執行一次。`close()` 後再呼叫 `register` 會拋出 `IllegalStateException`。某個清理拋出 `RuntimeException` 時仍會執行其餘清理，第一個例外重新拋出，其餘以抑制方式附上。

### `AceLibModule` 與 `AceLibAccess`：facade 解析

`AceLibAccess.resolveReadyApi()` 每次都經 Bukkit `ServicesManager` 重新解析 `AceLibApi.AceLibProvider` 並檢查 `isReady()`，不快取過期的 facade；缺少註冊是正常的 `Optional.empty()` 分支。facade 未就緒時 `AceLibModule.start()` 拒絕啟動，觸發生命週期回溯；模組未啟動時呼叫 `api()`、`scheduler()`、`events()` 會拋出 `IllegalStateException`。

## Shadow JAR

### 打包內容

`AceLib:v1.2.1`、`VaultAPI:1.7.1`、`PlaceholderAPI:2.11.6` 是 `compileOnly`，不會打包。需要 shade 的 `implementation` 包含 HikariCP `5.1.0`、`slf4j-api:2.0.9`、`slf4j-nop:2.0.9`、SQLite JDBC `3.47.0.0`、MySQL Connector/J `9.1.0`。兩個 JDBC 驅動都會做 shade，並由 `minimize { }` 保留，再以 `mergeServiceFiles()` 合併 `META-INF/services/java.sql.Driver`；不需額外的驅動 JAR。以 `relocate` 改為 `com.zaxxer.hikari` → `com.smile.aceeconomy.libs.hikari`、`org.slf4j` → `com.smile.aceeconomy.libs.slf4j`，並排除 `META-INF/*.SF`、`*.DSA`、`*.RSA`。

`jar` 使用分類器 `slim`；`shadowJar` 使用空分類器取代預設 JAR；`assemble` 依賴 `shadowJar`。交付物是 `build/libs/AceEconomy-2.1.0.jar`。

### 目前 inspection 方式

驗收條件是產出 JAR 不包含 `com/smile/acelib/**`。儲存庫的 `src/test` 與 CI 沒有已提交的自動檢查測試或指令稿，目前以手動執行 `./gradlew shadowJar`、`jar tf build/libs/AceEconomy-2.1.0.jar`（或 `unzip -l`）確認沒有 `com/smile/acelib/**`，並確認有經 `relocate` 處理後的 `com/smile/aceeconomy/libs/hikari/**` 與 `com/smile/aceeconomy/libs/slf4j/**`。本次 `clean build` 與 `shadowJar` 成功，並完成產出 JAR 檢查。

## Clean-slate：已移除的 v1 元件

### 移除狀態

v2 是同一儲存庫的全新重寫（clean-slate）；v1 原始碼僅供行為與商業規則參考，不承諾二進位、schema、設定相容。v1 套件目錄只剩空目錄：`manager/`、`hook/`、`data/`、`event/`、`exception/`、`listener/`、`listeners/`、`migration/`、`service/`、`storage/implementation/`、`utils/`、`zz/`。

已移除集中式管理器（`CurrencyManager`、`ConfigManager`、`LogManager`、`LeaderboardManager`）、舊版儲存（`SQLiteImplementation`、`MySQLImplementation`、`JsonStorageHandler`、`SchemaManager`）、v1 指令（`PayCommand`、`RollbackCommand`、`BaltopCommand`、`AdminCommand`）、舊 GUI（`BankMenu`、`GUIListener`）、掛鉤（`VaultImpl`、`AceEcoExpansion`）、重複的事件／監聽器／webhook、原生 API（`EconomyProvider`、`EconomyTransactionEvent`）與反射指令註冊器。v2 以 AceLib `CommandSpec` 顯式建構，經 `CommandRegistryImpl`/`BukkitCommandBridge`/`V2CommandRegistry` 註冊，沒有反射路徑。以 `grep` 檢查 `src/main`，未發現上述 v1 類別名稱殘留；`VaultEconomyProvider` 是 v2 實作，`ConfigManager` 是 AceLib 公開 API。

### v2 取代項目

| v1 移除 | v2 取代 |
|---|---|
| `CurrencyManager` | `domain.CurrencyRegistry` + `application.EconomyService` |
| v1 `ConfigManager` | `infrastructure.acelib.ConfigLangAdapter`（AceLib `ConfigManager` + `V2ConfigSchema`） |
| v1 儲存三件 | `JsonPersistenceBackend`（v2.0.0 預設），或 `PersistenceBackendFactory` 的 `SqlBackend` + `SqliteDialect`/`MySqlDialect`；MySQL 使用 `storage.mysql.*` 與已做 shade 的 JDBC 驅動 |
| `EconomyProvider` | `api.v2.EconomyApi`，具型別的 `EconomyResult`／`EconomyError`／`TransactionEvent`，`javadoc` 明言不承諾 v1 二進位相容性 |
| v1 監聽器 | `bootstrap.PlayerSessionListener`，`join`／`quit` 使用 Folia 安全分派 |
| v1 掛鉤 | `VaultEconomyProvider`、`AceEconomyExpansion` |

## 資料與 config：不 migration、不 downgrade

`config.yml` 是 v2 schema，`version: "2.0"`；檔頭說明不讀取或遷移 v1 `config-version`。正式儲存由 `config.storage.type` 選擇，預設為 `plugins/AceEconomy/data-v2.json` 的 JSON；SQLite/MySQL 使用 `storage.type: sqlite`/`mysql`。v2 JSON 的 `schemaVersion` 目前為 `1`；不相容版本會拋出 `PersistenceException`，備份後需 `truncateAndRecreate()`。不提供 v1→v2 資料／設定遷移、v1 原生 API 二進位相容性或 v2→v1 降版。

`restore(InputStream)` 會先完整解析並驗證 JSON 格式完整且 `schemaVersion == 1`，才會動到正式資料；損壞的備份不會影響現有資料。JSON 與 SQL 共用 v2 邏輯模型，但舊 v1 備份不因此相容。

## 安裝與 rollback 注意事項

### 全新安裝

1. 使用 Java 25 的 Paper/Folia 26.1.2（`api-version 1.26`、`folia-supported: true`）。
2. 將 `AceLib-1.2.1.jar` 與 `AceEconomy-2.1.0.jar` 放入 `plugins/`；AceLib 是硬性相依。
3. Vault 或 VaultUnlocked、PlaceholderAPI 是選用整合，缺少時略過。
4. 重啟伺服器；不要用 Bukkit `/reload` 驗證生命週期。
5. 首次啟動建立 `config.yml`、`lang/<locale>.yml` 與 `data-v2.json`。

### 切換前備份

切換前做完整伺服器備份，至少包含 `plugins/AceEconomy/` 與 `config.yml`。v2 JSON 備份／還原使用儲存層的 `backup()`／`restore()`，JSON 與 SQL 共用 v2 JSON 模型；詳見 [`persistence.zh-TW.md`](persistence.zh-TW.md)。

### rollback

v2→v1 降版不受支援。v1.4.0 不會讀取 `data-v2.json` 或 `version: "2.0"` 設定。唯一回溯路徑是還原完整切換前備份（含 v1 資料與設定），並移除 v2 產生的 `data-v2.json`。依賴 v1 原生 API、banknote schema 或舊指令表面的第三方插件會失效。

### v2.0/v2.1 scope boundary

**v2.0.0 已接線：** JSON（預設）、SQLite、MySQL；`EconomyService`、`EconomyApiImpl`、`PersistentAuditSink`、`HistoryService`（`/aceeco history`）、`RollbackService`（`/aceeco rollback <transaction-id>`）、`LeaderboardService`（`/baltop`）、銀行票據（`/withdraw cash`）、銀行 GUI（`/bank`）與 Vault/PAPI。`V2CommandRegistry` 註冊六個指令與 `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore` 子指令；`rollback`、`restore` 是僅限主控台的破壞性操作，`backup` 可由授權玩家或主控台執行。

**仍待補驗：**實機 `history`／`rollback`、`/aceeco backup [label]`、`/aceeco restore <backup-id> confirm`、實機 MySQL、玩家／GUI、真實伺服器備份／還原、故障注入與更廣泛的 `PersistentIdempotencyGuard`。`restore` 只接受全小寫的 `confirm`；有玩家在線時拒絕執行；會先做 JSON／schema／records／currency 預檢並建立安全備份；成功後清除排行榜快取，但不熱刷新工作階段或 GUI；玩家回來前需要重啟。MySQL 邏輯快照不能取代原生傾印。`lang/messages_*.yml` 是 v1 留下的失效鍵，v2 語系為 `lang/<locale>.yml`（`en_US`／`zh_TW`／`zh_CN`）。Essentials／CMI 匯入已移除，保留的 `ImportService` 不代表正式可用性。

## v2.0.0 發布驗證：已完成與仍待補驗

共用 Folia 測試服在 Folia `26.2-4-ver/26.2.x`（Minecraft 26.2）、Java 25、AceLib `1.0.0`、AceEconomy `2.0.0`、Vault `2.20.2`、PlaceholderAPI `2.12.3` 完成全新啟動與第二次重啟；不代表 Folia 26.1.2 同版本證據。此歷史紀錄早於目前的 AceLib v1.2.1 執行時期基線；新安裝以[版本與 runtime 基線](#版本與-runtime-基線)中的 AceLib 版本為準。

已完成：僅啟用一個 `AceLib 1.0.0`、`AceEconomy 2.0.0` 且無 `MemorySection` 啟動錯誤，並建立 `plugins/AceEconomy/data-v2.json`；移除舊 `AceLib-0.5.0-SNAPSHOT.jar`；以 RCON 執行 `plugins`、`aceeco`、`money`、`pay`、`withdraw`、`baltop`、`bank` 的 `help`、`aceeco reload` 與 `stop`；保留解析器回歸紀錄與測試；完整套件 **291 項測試、0 失敗／錯誤**，解析器目標 **23 項測試、0 失敗／錯誤**；`clean build` 與 `shadowJar` 成功；JAR 檢查中 `com/smile/acelib/**`、`org/bukkit/**`、`net/milkbowl/**`、`me/clip/**` 為 0，SQLite、MySQL、Hikari 與服務標記存在。`SHA256SUMS` 已更新，本文不再重複雜湊值。

仍待完成：

- [ ] Folia 26.1.2 全新資料夾的同版本驗證。
- [ ] 實際連線實機 MySQL。
- [ ] 玩家登入與遊戲內 GUI 點擊。
- [ ] 真實伺服器備份／還原與清除後還原。
- [ ] 資料庫故障、AceLib 未就緒、外部整合失敗、關閉時進行中作業的故障注入。
- [ ] GitHub `tag`、發布與 `push` 仍需發布者明確授權。

## 證據來源

來源包括：`build.gradle.kts`、`src/main/resources/plugin.yml`、`AceEconomy.java`、`CompositionRoot.java`、`ModuleLifecycle.java`、`ResourceOwner.java`、`LifecycleModule.java`、`AceLibModule.java`、`AceLibAccess.java`、`AceLibConsumer.java`；persistence、config、API、command 與 integration 原始碼；`.opencode/plans/plan-20260816-aceeconomy-v2-acelib-rewrite.md`；`Test-Server-Folia/logs/v2-release-final.log`、`v2-memorysection-fix.log`、`v2-memorysection-restart.log`、`v2-release-start.log`。

## 未確定事項

Folia 26.1.2 同版本執行時期、實機 MySQL、玩家／GUI 流程、故障注入與真實伺服器備份／還原仍未驗證。Vault 相依文字必須以實際的 `softdepend` 為準。Essentials／CMI 匯入不屬於 v2.0.0 正式接線；`History` 與 `Rollback` 已接線，但仍缺實機伺服器／Bukkit 橋接證據。
