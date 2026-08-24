# AceEconomy v2.0.0 Cutover：Entrypoint、生命週期、依賴與安裝/回退注意事項

本文以**目前實際 source 與 build 設定**為準，記錄 v2.0.0 的 cutover 事實：`CompositionRoot` 與薄 `AceEconomy` entrypoint 的 start/stop/reverse teardown/resource ownership、runtime 基線與外部依賴語意、shadow JAR 內容、v1 clean-slate 移除狀態，以及安裝與 rollback 的操作注意事項。讀者為 release operator 與維護者。

**狀態邊界**：本文同時記錄原始碼、build 設定與本次實機驗證。共用 Folia 測試服已在 Folia `26.2-4-ver/26.2.x`、Java 25、AceLib `1.0.0`、AceEconomy `2.0.0`、Vault `2.20.2`、PlaceholderAPI `2.12.3` 上完成 fresh start、restart 與 RCON 基本檢查；這些結果不等同於 Folia 26.1.2 的同版本驗證。尚未完成的項目列於最後一節。

---

## 1. 版本與 runtime 基線

| 項目 | 值 | 來源 |
|---|---|---|
| 插件版本 | `2.0.0` | `build.gradle.kts` `version = "2.0.0"` |
| Java | 25（toolchain `JavaLanguageVersion.of(25)`；`compileJava` 與 `compileV2Foundation` 皆 `options.release.set(25)`） | `build.gradle.kts` |
| Paper/Folia | 26.1.2（paperweight dev bundle `26.1.2.build.74-stable`；`plugin.yml` `api-version: 1.26`、`folia-supported: true`） | `build.gradle.kts`、`plugin.yml` |
| AceLib | `com.github.smile-minecraft:AceLib:v1.0.0`（`compileOnly`，runtime 由外部 JAR 提供，**禁止 shade**） | `build.gradle.kts` |
| Vault | `com.github.MilkBowl:VaultAPI:1.7.1`（`compileOnly`） | `build.gradle.kts` |
| PlaceholderAPI | `me.clip:placeholderapi:2.11.6`（`compileOnly`） | `build.gradle.kts` |

### 1.1 外部依賴的 runtime 語意

- **AceLib：runtime hard dependency**。`plugin.yml` `depend: [AceLib]`；缺少 AceLib 時 Paper 不會啟用本插件。此外 `CompositionRoot.requireApi()` 在 facade 未就緒時丟 `IllegalStateException("AceLib is missing or not ready")`，`AceEconomy.onEnable()` 捕捉後呼叫 `disablePlugin(this)`。兩層防護皆存在。
- **Vault：optional**。`plugin.yml` `softdepend: [Vault, PlaceholderAPI]`；`CompositionRoot.startIntegrations()` 只在 `Bukkit.getPluginManager().isPluginEnabled("Vault")` 為真時建立 `VaultIntegrationModule`。未安裝/未啟用時該模組直接略過，不註冊 provider。
- **PlaceholderAPI：optional**。同上，只在 `isPluginEnabled("PlaceholderAPI")` 為真時建立 `PlaceholderIntegrationModule`。

> 注意：計畫文件 §2 曾寫「Vault 預設維持 hard dependency」，但實際 `plugin.yml` 為 `softdepend`。本文以實際 source 為準。

---

## 2. Entrypoint 與 CompositionRoot

### 2.1 薄 entrypoint：`AceEconomy`

`src/main/java/com/smile/aceeconomy/AceEconomy.java`（28 行）只做三件事：

```java
public final class AceEconomy extends JavaPlugin {
    private CompositionRoot root;

    @Override
    public void onEnable() {
        root = new CompositionRoot(this);
        try {
            root.start();
        } catch (Exception failure) {
            getLogger().severe("AceEconomy cannot start: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (root != null) {
            root.stop();
            root = null;
        }
    }
}
```

- 主 class 不保存任何經濟規則或 service 欄位；所有 v2 建構與 teardown 都在 `CompositionRoot`。
- `start()` 失敗 → severe log + `disablePlugin`；`stop()` 只在 `root != null` 時執行一次。

### 2.2 `CompositionRoot` 的模組註冊順序與資源所有權

`CompositionRoot.registerModules()` 依**依賴順序**註冊七個模組。每個模組透過 `ResourceOwner` 登記外部資源的 cleanup callback；`ModuleLifecycle` 負責反向 teardown。

| 順序 | 模組 | start 動作 | stop 動作 | 登記到 ResourceOwner 的資源 |
|---|---|---|---|---|
| 1 | `configuration` | `config.load()`（`ConfigLangAdapter`，v2 schema `version: "2.0"`） | 無 | — |
| 2 | `persistence` | 建立 `ioExecutor`（fixed pool 2、daemon thread `aceeconomy-v2-io`）；讀 `config.storage`，由 `StorageConfigParser.parse(...)` 轉成 typed `StorageConfig`，再交 `PersistenceBackendFactory.create(...)` 接線 backend：`json`（預設）→ `JsonPersistenceBackend`，`sqlite` → `SqlBackend + SqliteDialect`，`mysql` → `SqlBackend + MySqlDialect`（HikariCP 由 `storage.mysql.*` 提供） | `stopPersistence()` → `persistence.close()` | `ioExecutor::shutdown`、`persistence::close` |
| 3 | `application` | `buildCurrencies()`、`Clock`、`InMemoryTransactionEventPublisher`、`DebtPolicy`、`EconomyService`、`EconomyApiImpl` | 無 | — |
| 4 | `acelib-runtime`（`RuntimeModule extends AceLibModule`） | 解析 ready facade；建立 `SafeScheduler`、`SafeEventRegistry`；取得 `GuiService`；註冊 GUI listener | `onStop()`：`runtimeGui.shutdown()`、`scheduler = null` | `scheduler::cancelAll`、`events::unregisterAll`、`HandlerList.unregisterAll(guiListener)` |
| 5 | `sessions` | `SafeSchedulerFoliaContext(scheduler)`、`AsyncAccountSessionStore(persistence, ioExecutor)`、`PlayerSessionManager(store, folia, 5000ms)`、註冊 `PlayerSessionListener`（join/quit） | `stopSessions()` → `sessions.disable(5000ms)` | `HandlerList.unregisterAll(listener)` |
| 6 | `presentation` | `V2BanknoteFactory`、`ProductionAdapters.*`、`CommandServices`、`V2CommandRegistry`、`CommandRegistryImpl(BukkitReplySink)`、`BukkitCommandBridge` 逐一 attach 六個 command | `stopPresentation()` → `commandRegistry.onPluginDisable()` | `commandRegistry::onPluginDisable` |
| 7 | `integrations` | 依 Vault/PAPI 啟用狀態建立對應 `IntegrationModule`；`ExternalIntegrationCoordinator.start()` | `stopIntegrations()` → `integrations.stop()` | `integrations::stop` |

資源所有權重點：

- **`ioExecutor` 與 persistence backend**：`ioExecutor::shutdown` 與 `persistence::close` 都登記在 owner；`stopPersistence()` 也會直接 `close()`。`JsonPersistenceBackend.close()` 是冪等的（只把 `initialized` 設為 `false`），`SqlBackend.close()` 會關閉持有的 `Connection` / HikariCP `DataSource`（`DataSource` 透過 `AutoCloseable` 介面 best-effort 關閉），因此 stop 後 owner cleanup 再 close 一次是安全的。
- **`acelib-runtime`**：`AceLibModule.start()` 先建立 scheduler 並**立即**把 `scheduler::cancelAll` 登記到 owner，再建立 event registry（也立即登記 `events::unregisterAll`）；若後續 `onStart` 失敗，scheduler/event 仍會被 teardown。
- **sessions**：shutdown 有 5 秒 deadline（`SESSION_SHUTDOWN_DEADLINE_MILLIS = 5_000L`），`PlayerSessionManager.disable(deadline)` 負責 bounded flush。
- **integrations**：`ExternalIntegrationCoordinator` 對每個模組先 probe AceLib external-service readiness；非 `READY` 的模組保持 `DISABLED`，不留下半初始化 service；`initialize()` 拋例外時會先 `shutdown()` 再標記 `FAILED`。
- **PlayerSessionListener**：join 事件在 `ioExecutor` 上 `economy.createAccount(...)`，再 `scheduler.runForPlayer(...)` 做 `sessions.login(...)`；quit 事件直接 `sessions.quit(uuid, deadline)`。所有玩家操作都經 Folia-safe scheduler dispatch（`SafeSchedulerFoliaContext`）。

### 2.3 `ModuleLifecycle`：start / rollback / stop 契約

`src/main/java/com/smile/aceeconomy/bootstrap/ModuleLifecycle.java`：

- **start**：依註冊順序啟動；每個模組拿到自己的 `ResourceOwner`。
- **部分啟動失敗（rollback）**：模組 N 啟動失敗時——
  1. 先 close 失敗模組自己的 `ResourceOwner`（釋放它在 throw 前已登記的資源）；
  2. 對已成功啟動的模組 0..N-1 依**反向順序**呼叫 `stop()`，再 close 各自的 owner；
  3. 原始 failure 被 rethrow，所有 stop/close 錯誤以 `addSuppressed` 附上，不吞掉；
  4. 失敗後 `stopped = true`，之後的 `stopAll()` 是 no-op（不會重複 teardown）。
- **正常 stop**：依反向順序，每個模組先 `stop()` 再 close owner；`stopAll()` 冪等（第二次呼叫 no-op）；第一個錯誤 rethrow，其餘 suppressed。
- `add()` 在 `startAll()` 之後呼叫會丟 `IllegalStateException`。

### 2.4 `ResourceOwner`：cleanup 順序與冪等

`src/main/java/com/smile/aceeconomy/bootstrap/ResourceOwner.java`：

- `register(Runnable)` 登記 cleanup callback；`close()` 依**反向註冊順序**執行，每個 callback 至多一次。
- close 之後再 `register()` 會丟 `IllegalStateException`（防止已停止的模組洩漏新資源）。
- 某個 cleanup 拋 `RuntimeException` 時，其餘 cleanup 仍會執行；第一個例外 rethrow，其餘 suppressed。

### 2.5 `AceLibModule` 與 `AceLibAccess`：facade 解析

- `AceLibAccess.resolveReadyApi()` 每次呼叫都經 Bukkit `ServicesManager` 重新解析 `AceLibApi.AceLibProvider` 並 gate `isReady()`；**不長期快取 stale facade**，缺 registration 是正常分支（`Optional.empty()`），不是錯誤。
- `AceLibModule.start()` 在 facade 未 ready 時拒絕啟動（`IllegalStateException`），讓 lifecycle 反向 rollback 先前模組。
- `AceLibModule` 的 accessor（`api()` / `scheduler()` / `events()`）在模組未啟動時丟 `IllegalStateException`。

---

## 3. Shadow JAR：compileOnly 外部依賴不打包與 inspection 方式

### 3.1 打包內容

`build.gradle.kts`：

- **compileOnly（不打包，runtime 由外部提供）**：`AceLib:v1.0.0`、`VaultAPI:1.7.1`、`PlaceholderAPI:2.11.6`。
- **implementation（shaded）**：`HikariCP:5.1.0`、`slf4j-api:2.0.9`、`slf4j-nop:2.0.9`、`org.xerial:sqlite-jdbc:3.47.0.0`、`com.mysql:mysql-connector-j:9.1.0`。
  - 兩個 JDBC driver 皆為 `implementation` 依賴並 shade 進 plugin JAR；`mergeServiceFiles()` 合併 `META-INF/services/java.sql.Driver`，`minimize { }` 排除兩者不被移除（見 `build.gradle.kts` 註解與 `exclude(dependency(...))`）。operator 不需額外放置 driver JAR。
  - relocate：`com.zaxxer.hikari` → `com.smile.aceeconomy.libs.hikari`；`org.slf4j` → `com.smile.aceeconomy.libs.slf4j`。
  - `mergeServiceFiles()`（SLF4J 2.x relocate 後需要合併 provider service files）。
  - `minimize { }` 排除 HikariCP、slf4j-nop、slf4j-api 不被移除。
  - 排除 `META-INF/*.SF`、`*.DSA`、`*.RSA` 簽章 metadata。
- **產物**：`jar` task 設 classifier `slim`；`shadowJar` 設 classifier `""`（取代預設 jar）；`assemble` 依賴 `shadowJar`。交付物為 `build/libs/AceEconomy-2.0.0.jar`。

### 3.2 目前 inspection 方式

- 驗收條件（計畫 §7）：**consumer JAR 不包含 `com/smile/acelib/**`**。
- 目前 repo **沒有**已提交的自動化 inspection 測試或 script（`src/test` 與 CI 中皆無）；現行方式為手動 CLI 檢查：
  1. `./gradlew shadowJar`
  2. `jar tf build/libs/AceEconomy-2.0.0.jar`（或 `unzip -l`）
  3. 確認**沒有** `com/smile/acelib/**` entry（AceLib 未被打包）；
  4. 確認**有** relocate 後的 `com/smile/aceeconomy/libs/hikari/**` 與 `com/smile/aceeconomy/libs/slf4j/**`（HikariCP/SLF4J 已打包）。
- 本次已執行 `clean build` 與 `shadowJar`，兩者皆成功；並以產出的 JAR 完成上述 inspection。

---

## 4. Clean-slate：已移除的 v1 元件

v2 是**同 repository 的 clean-slate 重寫**（計畫 §1），v1 原始碼只當功能與商業規則參考，不承諾任何 binary/schema/config 相容。

### 4.1 移除狀態（以目前 worktree 為準）

- v1 時代的套件目錄**只存在空目錄**，無任何 production class：`manager/`、`hook/`、`data/`、`event/`、`exception/`、`listener/`、`listeners/`、`migration/`、`service/`、`storage/implementation/`、`utils/`、`zz/`。
- 已移除的 v1 元件（依計畫與原始碼比對）：
  - **v1 managers**：`CurrencyManager`、`ConfigManager`、`LogManager`、`LeaderboardManager` 等集中式 manager。
  - **legacy storage**：`SQLiteImplementation`、`MySQLImplementation`、`JsonStorageHandler`、`SchemaManager`（v1 schema migration）。
  - **v1 commands**：`PayCommand`、`RollbackCommand`、`BaltopCommand`、`AdminCommand` 等指令層。
  - **v1 gui**：`BankMenu`、`GUIListener`（舊銀行 GUI）。
  - **hooks**：`VaultImpl`、`AceEcoExpansion`（v1 hook 包）。
  - **v1 events / listeners / webhook**：`EconomyLogListener`、`AuditListener`、`BanknoteListener`、`BanknoteInputListener`、`DiscordWebhook`（`utils`/`service` 兩份）等重複 event/listener。
  - **native API**：`EconomyProvider`、`EconomyTransactionEvent`（v1 async API 與 event）。
  - **reflection command registrar**：v2 指令一律以 AceLib `CommandSpec` 顯式建構並經 `CommandRegistryImpl`/`BukkitCommandBridge` 註冊（`V2CommandRegistry`），**無 reflection 註冊路徑**。
- 以 grep 核對 `src/main`：上述 v1 class 名稱全部無殘留（唯一同名的 `VaultEconomyProvider` 是 v2 實作，`ConfigManager` 是 AceLib 公開 API）。

### 4.2 v2 取代對照

| v1 移除 | v2 取代 |
|---|---|
| `CurrencyManager` | `domain.CurrencyRegistry` + `application.EconomyService` |
| `ConfigManager`（v1） | `infrastructure.acelib.ConfigLangAdapter`（AceLib `ConfigManager` + `V2ConfigSchema`） |
| v1 storage 三件 | `infrastructure.persistence.json.JsonPersistenceBackend`（v2.0.0 預設）、`infrastructure.persistence.sql.SqlBackend` + `SqliteDialect`／`MySqlDialect`（v2.0.0 透過 `PersistenceBackendFactory` 依 `storage.type` 接線；MySQL 連線與 HikariCP 設定來自 `storage.mysql.*`，JDBC driver 已 shade） |
| `EconomyProvider` | `api.v2.EconomyApi`（typed `EconomyResult`/`EconomyError`/`TransactionEvent`；javadoc 明言**不承諾 v1 binary compatibility**） |
| v1 listeners | `bootstrap.PlayerSessionListener`（join/quit，Folia-safe dispatch） |
| v1 hooks | `infrastructure.integration.vault.VaultEconomyProvider`、`infrastructure.integration.placeholder.AceEconomyExpansion` |

---

## 5. 資料與 config：不 migration、不 downgrade

- **config**：`config.yml` 為 v2 schema，`version: "2.0"`（`V2ConfigSchema.V2 = new ConfigVersion(2, 0)`）；檔頭註解明言「本檔不讀取、不遷移 v1 的 config-version 表面」。v2 adapter 從不讀取或遷移 v1 config。
- **資料**：production persistence 由 `config.storage.type` 選擇 backend，預設為 JSON（資料檔 `plugins/AceEconomy/data-v2.json`）；SQLite／MySQL 由 `storage.type: sqlite`／`mysql` 啟用，細節見 §6.4。v2 JSON model 有 `schemaVersion`（目前為 1）；`initialize()` 遇到不相容版本會丟 `PersistenceException`，需 `truncateAndRecreate()`（先備份）。
- **不提供**：v1 → v2 自動資料 migration、舊 config migration、v1 native API binary compatibility、v2 → v1 downgrade（計畫 §2 Non-goals）。
- **restore 安全**：`restore(InputStream)` 會先完整 parse 並驗證 snapshot（well-formed JSON + `schemaVersion == 1`）才動 live data；壞備份不會破壞現有資料（見 `docs/persistence.md`）。
- **文件狀態提醒**：`docs/database.md` 仍需另行整理；`docs/release-v2.0.0.md` 已更新安裝與回退說明。v2 不提供 v1 → v2 自動資料 migration 或舊 config migration。

---

## 6. 安裝與 rollback 操作注意事項

### 6.1 全新安裝（fresh install）

1. 伺服器需為 **Java 25** 的 Paper/Folia **26.1.2**（`api-version 1.26`、`folia-supported: true`）。
2. 放置 `AceLib-1.0.0.jar` 與 `AceEconomy-2.0.0.jar` 到 `plugins/`。**AceLib 是 hard dependency**，缺它插件不會啟用。
3. （選用）Vault 或 VaultUnlocked、PlaceholderAPI；未安裝時對應整合自動略過。
4. 重啟伺服器（**不要用 Bukkit `/reload` 驗證生命週期**；計畫明確不使用 `/reload`，AceLib facade 每次重新解析）。
5. 首次啟動會建立 `config.yml`、`lang/<locale>.yml` 與 `data-v2.json`。

### 6.2 升級前備份

- cutover 前先做**整份伺服器備份**（至少含 `plugins/AceEconomy/` 與 `config.yml`）。
- v2 資料備份/restore 使用 persistence 層的 `backup()` / `restore()`（JSON 與 SQL 後端互通，皆為 v2 JSON model）；程序見 `docs/persistence.md`。

### 6.3 rollback（回退）注意事項

- **v2 → v1 downgrade 不受支援**：v1.4.0 不會讀取 `data-v2.json` 或 `version: "2.0"` 的 config；直接換回 v1 jar 等同於**資料不可見**，不是可回復路徑。
- 若需要回到 v1 狀態，唯一方式是**還原 cutover 前的整份備份**（含 v1 資料檔與 config），並移除 v2 產生的 `data-v2.json`。
- 已移除的 v1 元件不存在於 v2 jar 中，任何依賴 v1 native API / 舊 banknote schema / 舊指令表面的第三方插件都會失效。

### 6.4 v2.0 / v2.1 scope boundary（operator 應知道）

**v2.0.0 已接線（production 可用）**：

- **Persistence**：JSON（預設）、SQLite、MySQL 三種 backend 皆已透過 `StorageConfigParser` + `PersistenceBackendFactory` 接入 `CompositionRoot`，由 `config.storage.type` 選擇（JSON 為單 backend instance / 單 JVM `ReentrantLock` + copy-on-write + atomic rename，不宣稱跨進程 first-writer-wins；SQLite/MySQL 以同一 JDBC transaction + nonce 主鍵 `INSERT OR IGNORE` 達成跨進程 first-writer-wins，JSON 跨進程需 OS file lock/CAS，列 release gate）；`EconomyService`、`EconomyApiImpl`、`PersistentAuditSink`（交易紀錄寫入）、`HistoryService`（唯讀 audit 查詢，經 `ProductionAdapters.History` 接入 `/aceeco history`）、`RollbackService`（交易回滾，經 `ProductionAdapters.Rollback` 接入 `/aceeco rollback <transaction-id>`，底層為 atomic `StorageReversalExecutor`）、`LeaderboardService`（`/baltop`）、banknote（`/withdraw cash`）、bank GUI（`/bank`）與 Vault/PAPI 整合皆已接入 entrypoint。
- `V2CommandRegistry` 註冊 `money`、`pay`、`withdraw`、`baltop`、`bank`、`aceeco` 六個指令，對應 `plugin.yml` commands/permissions；`aceeco` 現含 `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore` 八個子指令。`rollback` 與 `restore` 為 console-only destructive 操作，`backup` 可由授權玩家或主控台執行；三者分別使用 root `aceconomy.admin` 與 child `aceeconomy.admin.rollback`、`aceeconomy.admin.backup`、`aceeconomy.admin.restore`。

**仍待補驗（不把測試契約當成實機證據）**：

- `/aceeco history` 與 `/aceeco rollback` 的 unit/contract 測試已完成，但**尚未在 live server 實機驗證**（見 §7）；rollback 的 live 驗證需含 player sender 拒絕、invalid UUID、already-reverted no-op 與 marker failure 路徑。
- **Backup / restore 管理操作**：canonical 指令是 `/aceeco backup [label]` 與 `/aceeco restore <backup-id> confirm`，沒有獨立的 `/backup` 或 `/restore` 根指令。`backup` 需要 `aceconomy.admin` 與 `aceconomy.admin.backup`；`restore` 需要 `aceconomy.admin` 與 `aceconomy.admin.restore`，且只能由主控台執行。`restore` 只接受精確小寫 `confirm`，有玩家在線時會拒絕；還原前會完成 JSON/schema/records/currency preflight，再建立 safety backup，任一 gate 失敗都不觸碰 live state。成功後會清除 leaderboard cache，但不會熱刷新 session/GUI；讓玩家回來前必須重啟伺服器。
- **MySQL 限制**：JSON、SQLite、MySQL 共用 v2 logical JSON snapshot；MySQL 使用 logical snapshot，不是 native dump，不能取代 `mysqldump`、`mariadb-dump` 或資料庫維運的實體／災難復原備份。live MySQL、live Folia 與真實伺服器 backup/restore proof 尚未完成。
- **persistent `IdempotencyGuard`**：`ports.IdempotencyGuard` 是 interface，production 已有 `PersistentIdempotencyGuard` binding 用於 banknote replay guard；更廣泛的 idempotency 應用範圍擴充屬後續工作。
- **unit tests 與 dead language keys 不代表可用性**：`src/test` 的 service contract tests 與 `lang/messages_*.yml`（v1 殘留、v2 未引用）不得視為 v2.0 production availability；v2 語系以 `lang/<locale>.yml`（`en_US`／`zh_TW`／`zh_CN`）為準。

**Essentials / CMI import 已從本 Plan 移除**：依使用者決策，Essentials/CMI balance import 不屬於 v2.0.0，也不再列入本 Plan 的 v2.1 follow-up；不引入 vendor parser、import command/API 或 v1 → v2 migration 相容層。`ImportService` 若保留於 source 只代表一般化的 service/unit contract，不代表 Essentials/CMI 產品功能；後續可另行刪除或重新規劃，不阻擋本 Plan。

**驗證狀態**：共用 Folia 測試服的 Folia `26.2-4-ver/26.2.x` fresh start、restart 與 RCON 基本檢查已完成；Folia 26.1.2 同版本實機、玩家／GUI、live MySQL、真實伺服器故障注入與 backup/restore 實測仍待完成，詳見 §7。

---

## 7. v2.0.0 release validation：已完成驗證與仍待補驗

本次已在共用 Folia 測試服完成一次 fresh start 與第二次 restart。測試服使用 Folia `26.2-4-ver/26.2.x`（Minecraft 26.2）、Java 25、AceLib `1.0.0`、AceEconomy `2.0.0`、Vault `2.20.2` 與 PlaceholderAPI `2.12.3`。這是 Folia 26.2 的驗證結果，不能代替 Folia 26.1.2 的同版本實機驗證。

已完成的檢查：

- 最後一次啟動的插件清單中只有一個 AceLib，即 `AceLib 1.0.0`；`AceEconomy 2.0.0` 啟用成功，沒有再出現 `MemorySection` 啟動錯誤，並建立 `plugins/AceEconomy/data-v2.json`。
- 舊的 `AceLib-0.5.0-SNAPSHOT.jar` 已停用並移除於最後一次啟動。較早的啟動紀錄曾同時載入 `AceLib 0.5.0-SNAPSHOT` 與 `AceLib 1.0.0`，產生 ambiguous plugin warning；這段歷史 log 保留作為測試服清理環境時的教訓，不代表最後結果。
- 第二次 restart 後，以 RCON 執行 `plugins`、`aceeco`、`money`、`pay`、`withdraw`、`baltop`、`bank` 的 `help` 檢查，並執行 `aceeco reload` 與 RCON stop。log 記錄 `AceEconomy reloaded`，以及 AceEconomy、AceLib 的 disable；未有玩家登入，也未做遊戲內 GUI click。
- parser regression 已保留原始 `MemorySection` 啟動失敗的真實 log；root／nested `ConfigurationSection` 與 nested scalar fail-fast 均有測試。最後 full suite 為 291 tests、0 failures/errors，parser target 為 23 tests、0 failures/errors；`clean build` 與 `shadowJar` 皆成功。
- JAR inspection 以 `jar tf` 完成：`com/smile/acelib/**`、`org/bukkit/**`、`net/milkbowl/**`、`me/clip/**` 均為 0；SQLite、MySQL、Hikari 與 service markers 均存在。`SHA256SUMS` 已更新，hash 不在本文重複列出。

尚未完成的檢查：

- [ ] 在 Folia 26.1.2 以全新 data folder 重做同版本實機驗證；26.2 的結果不可直接替代 26.1.2。
- [ ] 實際連線 live MySQL，確認該路徑在真實伺服器上的行為。
- [ ] 以玩家登入及遊戲內 GUI click 驗證玩家流程；本次未做這兩項操作。
- [ ] 在真實伺服器執行 backup/restore 操作與清除後 restore；現有 unit/contract tests 不等同於實機演練。
- [ ] 在真實伺服器做 DB failure、AceLib non-ready、external integration failure 與 shutdown in-flight 的 failure injection；目前只有對應的 unit/contract tests。
- [ ] GitHub tag、release 與 push 仍需發布者明確授權。

---

## 8. 事實來源

- `build.gradle.kts`：版本、Java 25、paperweight 26.1.2、compileOnly/implementation、shadowJar 設定。
- `src/main/resources/plugin.yml`：`depend: AceLib`、`softdepend: Vault/PlaceholderAPI`、commands/permissions、`folia-supported`。
- `src/main/java/com/smile/aceeconomy/AceEconomy.java`：薄 entrypoint。
- `src/main/java/com/smile/aceeconomy/bootstrap/CompositionRoot.java`：模組註冊順序、資源所有權、Vault/PAPI runtime gate。
- `src/main/java/com/smile/aceeconomy/bootstrap/ModuleLifecycle.java`、`ResourceOwner.java`、`LifecycleModule.java`：reverse teardown 契約。
- `src/main/java/com/smile/aceeconomy/acelib/AceLibModule.java`、`AceLibAccess.java`、`AceLibConsumer.java`：facade 解析與 scheduler/event 所有權。
- `src/main/java/com/smile/aceeconomy/infrastructure/persistence/json/JsonPersistenceBackend.java`：`data-v2.json`、close 冪等、restore 驗證。
- `src/main/java/com/smile/aceeconomy/infrastructure/persistence/StorageConfig.java`、`StorageConfigParser.java`、`PersistenceBackendFactory.java`、`StorageBackendKind.java`：typed `StorageConfig`（sealed `Json`／`Sqlite`／`Mysql`）、預設 JSON、SQLite 路徑越界拒絕、MySQL 由 `storage.mysql.*` 提供 JDBC URL 與 HikariCP 設定。
- `src/main/java/com/smile/aceeconomy/infrastructure/persistence/sql/SqlBackend.java`、`SqliteDialect.java`、`MySqlDialect.java`、`V2Schema.java`、`SchemaVersion.java`：JDBC 後端共用實作、SQLite/MySQL dialect、synchronized 序列化所有 JDBC 存取、`schemaVersion` 與 `needsRecreation`。
- `src/main/java/com/smile/aceeconomy/infrastructure/acelib/V2ConfigSchema.java`、`ConfigLangAdapter.java`：v2 config schema `2.0`。
- `src/main/java/com/smile/aceeconomy/api/v2/EconomyApi.java`：v2 native API 與 v1 不相容聲明。
- `src/main/java/com/smile/aceeconomy/commands/v2/V2CommandRegistry.java`：六指令表面、無 reflection registrar。
- `src/main/java/com/smile/aceeconomy/infrastructure/integration/acelib/ExternalIntegrationCoordinator.java`：整合模組 readiness gate。
- `.opencode/plans/plan-20260816-aceeconomy-v2-acelib-rewrite.md`：v2.0/v2.1 範圍、全域驗收條件、§13 Essentials/CMI scope removal。
- `src/main/java` 目錄結構與 grep：v1 空目錄、v1 class 名稱無殘留。
- `Test-Server-Folia/logs/v2-release-final.log`、`v2-memorysection-fix.log`、`v2-memorysection-restart.log`：Folia 26.2 實機啟動、restart、RCON、reload、stop 與 plugin disable 紀錄；`v2-release-start.log` 保留雙 AceLib 與 `MemorySection` 問題的歷史紀錄。

## 9. 未確定事項

- **Folia 版本差異**：已完成的是 Folia `26.2-4-ver/26.2.x` 實機驗證；Folia 26.1.2 尚未有同版本實機，因此不能宣稱 26.1.2 已通過。
- **live MySQL**：本次沒有連線 live MySQL；`SqlBackend`、HikariCP、JSON model 與 JDBC 往返仍只有 source、unit/contract tests 與 JAR marker 檢查可供核對。
- **玩家與 GUI**：本次沒有玩家登入或遊戲內 GUI click，不能把 RCON `help` 結果解讀成完整玩家流程驗證。
- **failure injection**：backup/restore、DB failure、AceLib non-ready、external integration failure、shutdown in-flight 都已有 unit/contract tests，但尚未在真實伺服器做 failure injection；這兩類證據不能混寫。
- **backup/restore 實機操作**：備份、清除後 restore 的真實伺服器流程尚未完成；現有測試不代表已做 live 演練。
- **Vault 依賴語意分歧**：目前 `plugin.yml` 使用 `softdepend`；文件其他位置若仍寫成 hard dependency，應以實際 source 為準另行校正。
- **v2.0.0 邊界**：Essentials/CMI import 的 production wiring 不屬於 v2.0.0；source 中保留的 service 或 contract tests 不代表這些功能已交付。History 與 Rollback 已接入 production composition（`/aceeco history`、`/aceeco rollback <transaction-id>`，見 §6.4），但尚未做 live server／Bukkit bridge 驗證。
