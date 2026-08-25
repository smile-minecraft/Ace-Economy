# AceEconomy v2——能力基線矩陣

[English](v2-capability-matrix.md) · [简体中文](v2-capability-matrix.zh-CN.md) · 繁體中文

本文是 clean-slate v2 重寫前使用的 v1 能力基線，記錄需要保留的產品能力與經濟規則，指出 v2 可以破壞哪些 v1 相容性，並標記需要另外決策的刪除項目。證據來自工作區中的實際 source、resource 與 test，而不是歷史報告。目前 v2.0.0/v2.1 的接線狀態與未驗證事項見[v2.0.0/v2.1 範圍邊界](#v200-v21-範圍邊界)。

## 目錄

- [驗證環境與證據基線](#驗證環境與證據基線)
- [能力保留矩陣](#能力保留矩陣)
- [經濟規則](#經濟規則)
- [非目標](#非目標)
- [剩餘風險與 v2 前置條件](#剩餘風險與-v2-前置條件)
- [v2.0.0/v2.1 範圍邊界](#v200-v21-範圍邊界)

## 驗證環境與證據基線

- 執行命令（系統 Java 為 25.0.4，而 Gradle 8.12 Kotlin DSL 在該設定下無法解析，因此使用 Java 21）：`JAVA_HOME=<java21> ./gradlew clean test`。
- 結果：**70 tests, 0 failures, 0 errors, 0 skipped**（49 個 v1 基線測試，加上本輪新增的 21 個 capability 測試）。
- 鎖定規則的測試位於 `src/test/java/com/smile/aceeconomy/capability/`：`EconomyCapability.java`（不含 v1 class 名稱的 v2 契約介面）、`V1CurrencyManagerAdapter.java`（唯一允許引用 v1 class 的測試 adapter）、`EconomyCapabilityContractTest.java`（餘額、交易、限制與取消情境）、`ConfigCapabilityTest.java`（config.yml 多貨幣、債務、語系、Discord 與儲存契約）、`CommandSurfaceCapabilityTest.java`（plugin.yml 指令與權限表面契約）。

## 能力保留矩陣

- **RETAIN**——v2 必須保留該產品能力；內部可以重寫，但行為契約維持不變。
- **RESET**——保留能力，但 v2 可以破壞 v1 binary、data、config、banknote schema 與 API 相容性。
- **EXCLUDE**——計畫刪除該產品能力；需要另外決策，本基線不自行假定刪除。

| # | 產品能力 | 狀態 | 已確認的 v1 證據 | 鎖定方式 |
|---|---|---|---|---|
| 1 | 多貨幣系統 | RETAIN | `config.yml` `currencies:` dollar/token；`CurrencyManager` | ConfigCapabilityTest + ContractTest.currencyExists |
| 2 | 帳戶與起始餘額 | RETAIN | `Account`、`ConfigManager.getStartBalance()`=1000 | ContractTest.testAccountStartsAtStartBalance |
| 3 | 存款與提款（原子交易） | RETAIN | `CurrencyManager.deposit/withdraw` | ContractTest deposit/withdraw |
| 4 | 轉帳 | RETAIN | `PayCommand`、`EconomyProvider`（v1 在指令層組合 withdraw+deposit） | 契約已預留；v2 應提供明確的 transfer 入口（見風險 R3） |
| 5 | Vault 經濟整合 | RETAIN | `hook/VaultImpl.java` | plugin.yml `softdepend: Vault`（選用；v2.0 已接線，缺少時略過） |
| 6 | PlaceholderAPI | RETAIN | `hook/AceEcoExpansion.java` | plugin.yml `softdepend: PlaceholderAPI` |
| 7 | SQLite 儲存 | RETAIN | `storage/implementation/SQLiteImplementation.java` | ConfigCapabilityTest.storage；v2.0.0 已接線（`storage.type: sqlite` → `SqlBackend + SqliteDialect`，檔案位於插件資料夾內；`StorageConfigParser` 拒絕路徑越界） |
| 8 | MySQL 儲存 | RETAIN | `storage/implementation/MySQLImplementation.java` | ConfigCapabilityTest.storage；v2.0.0 已接線（`storage.type: mysql` → `SqlBackend + MySqlDialect` + HikariCP，JDBC driver 已 shade；連線／pool 來自 `storage.mysql.*`）；live MySQL 連線仍未驗證（v2.0.0 release gate） |
| 9 | JSON 儲存 | RETAIN | `storage/JsonStorageHandler.java` | 列為保留；v1 config 未預設啟用，v2 再決定是否預設 |
| 10 | 交易紀錄與審計 | RETAIN | `LogManager`、`listeners/EconomyLogListener`、`AuditListener` | 契約已預留（見風險 R4）；v2.0.0 透過 `PersistentAuditSink` 與 `TransactionRepository.append`/`appendBatch` 寫入；唯讀查詢經 `HistoryService` 與 `ProductionAdapters.History` 接入 `/aceeco history [player] [currency] [page]`，權限為 `aceeconomy.admin.history`；live server 驗證仍待完成 |
| 11 | Rollback | RETAIN | `commands/RollbackCommand.java`；權限 `aceeconomy.admin.rollback` | v2 已經 `RollbackService` 與 `ProductionAdapters.Rollback` 接入 `/aceeco rollback <transaction-id>`（僅主控台、root `aceeconomy.admin` 加 child `aceeconomy.admin.rollback`、atomic `StorageReversalExecutor`）；live server 驗證仍待完成 |
| 12 | 銀行票據 | RESET | `BanknoteInputListener`、`listeners/BanknoteListener` | 保留權限／指令表面；schema 可以不相容 |
| 13 | 銀行 GUI | RETAIN | `gui/BankMenu.java`、`gui/GUIListener`；`/bank` | CommandSurfaceCapabilityTest |
| 14 | 排行榜 | RETAIN | `LeaderboardManager`、`BaltopCommand`；`/baltop` | CommandSurfaceCapabilityTest |
| 15 | Discord 通知 | RETAIN | `utils/DiscordWebhook`、`service/DiscordWebhook`；`config.discord` | ConfigCapabilityTest.discord |
| 16 | 三種語系（en_US/zh_TW/zh_CN） | RETAIN | `lang/messages_*.yml`；`ConfigManager` locales | ConfigCapabilityTest.locale；v2 使用 `lang/<locale>.yml`，`messages_*.yml` 是 v1 dead keys |
| 17 | Essentials / CMI 匯入 | EXCLUDED | `migration/EssentialsMigrator.java`、`migration/CMIMigrator.java`（僅為 v1 歷史證據） | 依使用者決策從 v2.0.0 與 v2.1 follow-up 移除；不引入 vendor parser、import command/API 或 v1 → v2 migration 相容層；保留的 `ImportService` 只代表一般 service/unit contract，不代表產品功能 |
| 18 | 債務／負餘額 | RETAIN | `config.economy.allow-negative-balance`、`default-debt-limit`；`CurrencyManager.getDebtLimit` | ContractTest DebtEnabled/Disabled |
| 19 | 權限契約（含 rollback/debt bypass） | RETAIN | `plugin.yml` permissions | CommandSurfaceCapabilityTest.permissions |

## 經濟規則

以下規則由 `ContractTest` 鎖定：

1. 新帳戶以 `start-balance`（v1=1000）初始化。
2. 存款與提款是單一貨幣的原子交易；非正數金額會被拒絕，交易取消。
3. 餘額不足的提款拋出 `InsufficientFundsException`，餘額保持不變，交易取消。
4. 關閉債務時餘額不能低於 0；負值 `setBalance` 會被拒絕。
5. 開啟債務時餘額可以為負，但受 `debt-limit` 限制；超過上限的提款會取消。
6. 貨幣 ID 比對不分大小寫，並會安全處理空白（`currencyExists`）。

## 非目標

- 不升級 Gradle、Java、Paper 或 AceLib。
- 不重寫 production domain、storage 或 plugin code。
- 不執行架構重寫或後續 v2 實作階段。
- 不發布、推送或建立外部狀態。
- 不承諾 v1 binary、data、config 或 banknote 相容性（標示為 RESET 的項目可以不相容）。

## 剩餘風險與 v2 前置條件

- R1：本輪使用 Java 21；系統 Java 25 會導致 Gradle 8.12 Kotlin DSL 失敗。v2 實作需要決定 CI／本機 `JAVA_HOME` 策略或升級 Gradle。
- R2：capability tests 透過 `V1CurrencyManagerAdapter` 映射 v1 行為；v2 需要新的 adapter，契約介面不變。
- R3：v1 沒有獨立的 `transfer` 方法（由指令層組合操作）；目前契約鎖定 atomic deposit/withdraw，因此 v2 應明確定義 transfer 語意。
- R4：審計與 rollback 邊界尚未由 capability tests 鎖定（需要 storage/log 參與，超出本文最小變更範圍）；建議 v2 實作加入 `AuditCapability` 契約。

## v2.0.0/v2.1 範圍邊界

本節以目前 source 與 build 設定為準。完整接線細節見[cutover](cutover.zh-TW.md)；不要把 v1 保留契約或 unit tests 當成 v2.0.0 production availability 的證明。

- **v2.0.0 已接線：** JSON（預設）、SQLite 與 MySQL persistence；bank GUI deposit/redeem；`EconomyService`、`EconomyApiImpl`、`PersistentAuditSink`、`HistoryService`、`RollbackService`、`LeaderboardService`、banknotes、bank GUI 與選用的 Vault/PAPI 整合；六個指令 `money`、`pay`、`withdraw`、`baltop`、`bank`、`aceeco`，以及八個 `aceeco` 子指令 `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore`。
- **已接線但仍待 live 驗證：** `/aceeco history`、`/aceeco rollback` 與管理式 backup/restore。canonical 指令是 `/aceeco backup [label]` 與 `/aceeco restore <backup-id> confirm`，沒有 `/backup` 或 `/restore` 根指令。`restore` 僅限主控台，拒絕有線上玩家的情況，只接受小寫 `confirm`，會先做 JSON/schema/records/currency preflight、建立 safety backup，成功後清除 leaderboard cache，但不會熱刷新 session 或 GUI。
- JSON、SQLite 與 MySQL 共用 v2 logical JSON snapshot。MySQL 使用 logical snapshot，不是 native dump，因此不能取代 `mysqldump`、`mariadb-dump` 或資料庫維運備份。
- **Essentials/CMI import：** 已從本 Plan 與 v2.0.0 移除；保留的 `ImportService` 不代表產品可用性。
- **未驗證：** live Folia/Bukkit（包含 Folia 26.1.2 fresh install、RCON／遊戲內檢查、故障演練與 backup/restore 演練）、live MySQL 與跨程序 smoke 仍是 v2.0.0 release gate。unit tests 與 v1 `lang/messages_*.yml` dead keys 不代表 production availability；v2 語系檔是 `lang/<locale>.yml`。
