# AceEconomy v2 — Capability Baseline Matrix（v1 功能基線）

> 本文件為 v1 功能基線：在 clean-slate v2 重寫前，鎖定「仍要保留的產品功能與經濟商業規則」，
> 並標明哪些能力可破壞 v1 相容性、哪些刪除需另行決策。
> 證據來自工作區實際 source / resource / test，非歷史報告。
> 本文件以 v1 功能契約與 v2 保留決策為主，並列出目前 source／build／test 的證據；v2.0.0/v2.1 接線狀態、未驗證事項見 §6。

## 1. 驗證環境與證據基線

- 執行命令（系統預設 Java 為 25.0.4，Gradle 8.12 的 Kotlin DSL 無法解析；以 Java 21 執行）：
  `JAVA_HOME=<java21> ./gradlew clean test`
- 結果：**70 tests, 0 failures, 0 errors, 0 skipped**（v1 基線 49 + 本輪新增 capability 21）。
- 鎖定規則的測試位於 `src/test/java/com/smile/aceeconomy/capability/`：
  - `EconomyCapability.java` — v2 契約介面（不含任何 v1 class 名稱）。
  - `V1CurrencyManagerAdapter.java` — 唯一允許引用 v1 class 的測試用 adapter（anti-corruption seam）。
  - `EconomyCapabilityContractTest.java` — 餘額／交易／限制／取消核心情境。
  - `ConfigCapabilityTest.java` — config.yml 多貨幣／債務／語系／Discord／儲存契約。
  - `CommandSurfaceCapabilityTest.java` — plugin.yml 指令與權限表面契約。

## 2. 功能保留矩陣

狀態定義：
- **RETAIN** — v2 必須保留此產品能力（內部可重寫，行為契約需保持）。
- **RESET** — 能力保留，但 v2 可破壞 v1 相容性（binary / 資料 / config / banknote schema / API）。
- **EXCLUDE** — 計畫刪除產品功能（需另行決策，本基線不預設刪除）。

| # | 產品功能 | 狀態 | v1 證據（已確認） | 鎖定方式 |
|---|----------|------|-------------------|----------|
| 1 | 多貨幣系統 | RETAIN | `config.yml` `currencies:` dollar/token；`CurrencyManager` | ConfigCapabilityTest + ContractTest.currencyExists |
| 2 | 帳戶餘額 / 起始餘額 | RETAIN | `Account`、`ConfigManager.getStartBalance()`=1000 | ContractTest.testAccountStartsAtStartBalance |
| 3 | 存款 / 提款（原子交易） | RETAIN | `CurrencyManager.deposit/withdraw` | ContractTest deposit/withdraw |
| 4 | 轉帳 | RETAIN | `PayCommand`、`EconomyProvider`（v1 轉帳由指令層組合 withdraw+deposit） | 契約預留；v2 應有明確 transfer 入口（見風險 R3） |
| 5 | Vault 經濟整合 | RETAIN | `hook/VaultImpl.java` | plugin.yml `softdepend: Vault`（optional；v2.0 已接線，未安裝時略過） |
| 6 | PlaceholderAPI | RETAIN | `hook/AceEcoExpansion.java` | plugin.yml `softdepend: PlaceholderAPI` |
| 7 | SQLite 儲存 | RETAIN | `storage/implementation/SQLiteImplementation.java` | ConfigCapabilityTest.storage；v2.0.0 已接線（`storage.type: sqlite` → `SqlBackend + SqliteDialect`，檔案位於 plugin data folder；路徑越界會被 `StorageConfigParser` 拒絕） |
| 8 | MySQL 儲存 | RETAIN | `storage/implementation/MySQLImplementation.java` | ConfigCapabilityTest.storage；v2.0.0 已接線（`storage.type: mysql` → `SqlBackend + MySqlDialect` + HikariCP，JDBC driver 已 shade；連線／pool 來自 `storage.mysql.*`）；live MySQL 連線尚未驗證（v2.0.0 release gate） |
| 9 | JSON 儲存 | RETAIN | `storage/JsonStorageHandler.java` | 計畫列為保留；config 未預設啟用，v2 決定是否預設 |
| 10 | 交易紀錄 / 審計 | RETAIN | `LogManager`、`listeners/EconomyLogListener`、`AuditListener` | 契約預留（見風險 R4）；v2.0.0 已有 `PersistentAuditSink` 寫入（透過 `TransactionRepository.append`／`appendBatch`）；audit **查詢**已接線為唯讀 `/aceeco history [player] [currency] [page]`（`HistoryService` 經 `ProductionAdapters.History`，權限 `aceeconomy.admin.history`），live server 驗證仍待完成 |
| 11 | Rollback | RETAIN | `commands/RollbackCommand.java`；權限 `aceeconomy.admin.rollback` | CommandSurfaceCapabilityTest；v2 已接線：`RollbackService` 經 `ProductionAdapters.Rollback` 接入 `/aceeco rollback <transaction-id>`（console-only，root `aceeconomy.admin` + child `aceeconomy.admin.rollback`，atomic `StorageReversalExecutor`），live server 驗證仍待完成 |
| 12 | Banknote（支票） | RESET | `BanknoteInputListener`、`listeners/BanknoteListener` | 權限/指令表面保留；schema 可破壞 |
| 13 | 銀行 GUI | RETAIN | `gui/BankMenu.java`、`gui/GUIListener.java`；`/bank` | CommandSurfaceCapabilityTest |
| 14 | 排行榜 | RETAIN | `LeaderboardManager.java`、`BaltopCommand`；`/baltop` | CommandSurfaceCapabilityTest |
| 15 | Discord 通知 | RETAIN | `utils/DiscordWebhook.java`、`service/DiscordWebhook.java`；`config.discord` | ConfigCapabilityTest.discord |
| 16 | 三語系（en_US/zh_TW/zh_CN） | RETAIN | `lang/messages_*.yml`；`ConfigManager` locales | ConfigCapabilityTest.locale；v2 語系以 `lang/<locale>.yml`（`en_US`/`zh_TW`/`zh_CN`）為準，`messages_*.yml` 為 v1 殘留 dead keys |
| 17 | Essentials / CMI 匯入 | EXCLUDED | `migration/EssentialsMigrator.java`、`migration/CMIMigrator.java`（僅為 v1 歷史證據） | 已依使用者決策自 v2.0.0 與本 Plan 的 v2.1 follow-up 移除；不引入 vendor parser、import command/API 或 v1 → v2 migration 相容層；`ImportService`（若保留於 source）只代表一般化的 service/unit contract，不代表 Essentials/CMI 產品功能，後續可另行刪除或重新規劃 |
| 18 | 債務 / 負資產系統 | RETAIN | `config.economy.allow-negative-balance`、`default-debt-limit`；`CurrencyManager.getDebtLimit` | ContractTest DebtEnabled/Disabled |
| 19 | 權限契約（含 rollback / debt bypass） | RETAIN | `plugin.yml` permissions | CommandSurfaceCapabilityTest.permissions |

## 3. 經濟商業規則（已鎖定，來自 ContractTest）

1. 新帳戶以 `start-balance`（v1=1000）初始化。
2. 存款/提款為單一貨幣的原子交易；非正數金額被拒絕（交易取消）。
3. 餘額不足時提款拋出 `InsufficientFundsException`，餘額保持不變（交易取消）。
4. 債務關閉時：餘額不得低於 0（`setBalance` 負值被拒）。
5. 債務開啟時：餘額可為負，但受 `debt-limit` 上限約束；超過上限的提款被取消。
6. 貨幣 ID 比對為大小寫不敏感且空白安全（`currencyExists`）。

## 4. 非目標（本文件不處理）

- 不升級 Gradle / Java / Paper / AceLib。
- 不重寫 production domain / storage / plugin code。
- 不執行架構重寫或後續 v2 實作階段。
- 不發布、推送或建立外部狀態。
- 不保證 v1 binary / 資料 / config / banknote 相容（v2 可破壞，見 RESET 欄）。

## 5. 殘留風險與 v2 實作前置（詳見回傳報告）

- R1：本輪以 Java 21 執行；系統預設 Java 25 會導致 Gradle 8.12 Kotlin DSL 失敗。v2 實作需決定 CI/本機 `JAVA_HOME` 或升級 Gradle。
- R2：capability tests 透過 `V1CurrencyManagerAdapter` 映射 v1 行為；v2 需提供新 adapter，契約介面本身不變。
- R3：v1 沒有獨立 `transfer` 方法（轉帳由指令層組合），capability 契約目前以 deposit/withdraw 鎖定原子交易；v2 應明確 transfer 語意。
- R4：交易紀錄 / rollback 的審計規則邊界尚未以 capability test 鎖定（需 storage/log 層介入，超出本文件最小變更範圍）；建議 v2 實作補 `AuditCapability` 契約。

## 6. v2.0.0 / v2.1 scope boundary

本節以目前 source 與 build 設定為準（`docs/cutover.md` 有完整接線細節），避免把「v1 保留契約」或「unit tests」誤讀為 v2.0.0 production 可用：

- **v2.0.0 已接線**：
  - **Persistence（JSON 預設 / SQLite / MySQL）**：`CompositionRoot` 透過 `StorageConfigParser` → `PersistenceBackendFactory` 依 `config.storage.type` 選擇 backend。預設 `json`（`JsonPersistenceBackend`，`data-v2.json`）；`sqlite` 為 `SqlBackend + SqliteDialect`（檔案路徑必須位於 plugin data folder 內，越界會被 parser 拒絕）；`mysql` 為 `SqlBackend + MySqlDialect` + HikariCP（連線與 pool 設定全部來自 `storage.mysql.*`，JDBC driver 已 shade，service files 已 `mergeServiceFiles()`）。`JsonPersistenceBackend` 與 `SqlBackend` 共用 `AccountRepository`／`TransactionRepository`／`PersistenceLifecycle` 三個 port。
  - **Bank GUI deposit/redeem**：production durable binding 為 `CompositionRoot` → `BankUseCase` → `EconomyService.redeemBanknote`（per-account lock、pre-commit event、currency/amount/debt 驗證）→ `AtomicRedemptionStore.redeemPrepared`（由 application 準備的 `Account`/`Transaction`/`nonce` 做 balance/audit/nonce all-or-none）；JSON 為同 backend instance 的 `ReentrantLock` + copy-on-write + atomic rename（同 JVM 有效，無 OS file lock，故不宣稱跨進程），SQLite/MySQL 以同一 JDBC transaction + nonce 主鍵 `INSERT OR IGNORE`/`INSERT IGNORE` 達成跨進程 first-writer-wins；live 跨進程與 live MySQL 皆未驗證（release gate）。JSON／SQLite contract tests 已覆蓋 prepared 流程的 commit/replay/account-missing/restart。
  - `EconomyService`／`EconomyApiImpl`、`PersistentAuditSink`（交易紀錄寫入，透過 `TransactionRepository.append`／`appendBatch`）、`HistoryService`（唯讀 audit 查詢，經 `ProductionAdapters.History` 接入 `/aceeco history`，權限 `aceeconomy.admin.history`）、`RollbackService`（交易回滾，經 `ProductionAdapters.Rollback` 接入 `/aceeco rollback <transaction-id>`：console-only、root+child 權限、UUID 前置驗證、typed success/already-reverted/failure 回覆、atomic `StorageReversalExecutor` 保證 marker ownership）、`LeaderboardService`（`/baltop`，由 `RepositoryLeaderboardSource` 接線 `AccountRepository.listAll()`）、banknote（`/withdraw cash`）、bank GUI（`/bank`）、Vault/PAPI 整合（皆 optional，`plugin.yml` `softdepend`）。
  - `V2CommandRegistry` 註冊 `money`、`pay`、`withdraw`、`baltop`、`bank`、`aceeco` 六指令；`aceeco` 現含 `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore` 八個子指令。`backup` 可由授權玩家或主控台執行；`restore` 為 console-only destructive 操作。
- **已接線但仍待實機驗證**：
  - `/aceeco history` 與 `/aceeco rollback` 的 live server 實機驗證（unit/contract 測試已完成）。
  - **Backup / restore 管理操作**：canonical 指令為 `/aceeco backup [label]` 與 `/aceeco restore <backup-id> confirm`，沒有 `/backup` 或 `/restore` root command。`backup` 需要 `aceconomy.admin` 與 `aceconomy.admin.backup`；`restore` 需要 `aceconomy.admin` 與 `aceconomy.admin.restore`，只能由主控台執行，只接受精確小寫 `confirm`，且有玩家在線時會拒絕。restore 會先做 JSON/schema/records/currency preflight，再建立 safety backup；成功後清除 leaderboard cache，但不熱刷新 session/GUI，讓玩家回來前必須重啟伺服器。
  - JSON、SQLite、MySQL 共用 v2 logical JSON snapshot；MySQL 是 logical snapshot，不是 native dump，不能取代 `mysqldump`、`mariadb-dump` 或資料庫維運備份。
- **Essentials / CMI import 已從本 Plan 移除**：依使用者決策，Essentials/CMI balance import 不屬於 v2.0.0，也不再列入本 Plan 的 v2.1 follow-up；不引入 vendor parser、import command/API 或 v1 → v2 migration 相容層。`ImportService` 若保留於 source 只代表一般化的 service/unit contract，不代表 Essentials/CMI 產品功能。
- **未驗證事項**：
  - live Folia/Bukkit 驗證（Folia 26.1.2 fresh-install、RCON/遊戲內驗證、故障演練、backup/restore 實測）屬 **v2.0.0 release gate**，未通過則 v2.0.0 不視為已驗證可發布。
  - live MySQL 連線尚未執行；目前只有 offline contract tests（`SqlBackendContractTest`、`SqlBackendConcurrencyTest`、`V2SchemaContractTest`、`PersistenceBackendFactoryTest`、`StorageConfigParserTest`），屬 **v2.0.0 release gate**。
  - 跨 process smoke 尚未驗證，屬 **v2.0.0 release gate**。
- **unit tests / dead language keys 不代表可用性**：`src/test` 的 service contract tests 與 `lang/messages_*.yml`（v1 殘留、v2 未引用）不得視為 v2.0 production availability；v2 語系以 `lang/<locale>.yml` 為準。
