# AceEconomy v2 — Capability Baseline Matrix (Task 1)

> 本文件由 Task 1（凍結 v1 功能規則，產出 v2 capability baseline）產出。
> 目的：在 clean-slate v2 重寫前，鎖定「仍要保留的產品功能與經濟商業規則」，
> 並標明哪些能力可破壞 v1 相容性、哪些刪除需另行決策。
> 本輪所有證據來自工作區實際 source / resource / test，非歷史報告。

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
| 5 | Vault 經濟整合 | RETAIN | `hook/VaultImpl.java` | plugin.yml `depend: Vault` |
| 6 | PlaceholderAPI | RETAIN | `hook/AceEcoExpansion.java` | plugin.yml `softdepend: PlaceholderAPI` |
| 7 | SQLite 儲存 | RETAIN | `storage/implementation/SQLiteImplementation.java` | ConfigCapabilityTest.storage |
| 8 | MySQL 儲存 | RETAIN | `storage/implementation/MySQLImplementation.java` | ConfigCapabilityTest.storage |
| 9 | JSON 儲存 | RETAIN | `storage/JsonStorageHandler.java` | 計畫列為保留；config 未預設啟用，v2 決定是否預設 |
| 10 | 交易紀錄 / 審計 | RETAIN | `LogManager`、`listeners/EconomyLogListener`、`AuditListener` | 契約預留（見風險 R4） |
| 11 | Rollback | RETAIN | `commands/RollbackCommand.java`；權限 `aceeconomy.admin.rollback` | CommandSurfaceCapabilityTest |
| 12 | Banknote（支票） | RESET | `BanknoteInputListener`、`listeners/BanknoteListener` | 權限/指令表面保留；schema 可破壞 |
| 13 | 銀行 GUI | RETAIN | `gui/BankMenu.java`、`gui/GUIListener.java`；`/bank` | CommandSurfaceCapabilityTest |
| 14 | 排行榜 | RETAIN | `LeaderboardManager.java`、`BaltopCommand`；`/baltop` | CommandSurfaceCapabilityTest |
| 15 | Discord 通知 | RETAIN | `utils/DiscordWebhook.java`、`service/DiscordWebhook.java`；`config.discord` | ConfigCapabilityTest.discord |
| 16 | 三語系（en_US/zh_TW/zh_CN） | RETAIN | `lang/messages_*.yml`；`ConfigManager` locales | ConfigCapabilityTest.locale |
| 17 | Essentials / CMI 匯入 | RETAIN | `migration/EssentialsMigrator.java`、`migration/CMIMigrator.java` | 計畫列為保留 |
| 18 | 債務 / 負資產系統 | RETAIN | `config.economy.allow-negative-balance`、`default-debt-limit`；`CurrencyManager.getDebtLimit` | ContractTest DebtEnabled/Disabled |
| 19 | 權限契約（含 rollback / debt bypass） | RETAIN | `plugin.yml` permissions | CommandSurfaceCapabilityTest.permissions |

## 3. 經濟商業規則（已鎖定，來自 ContractTest）

1. 新帳戶以 `start-balance`（v1=1000）初始化。
2. 存款/提款為單一貨幣的原子交易；非正數金額被拒絕（交易取消）。
3. 餘額不足時提款拋出 `InsufficientFundsException`，餘額保持不變（交易取消）。
4. 債務關閉時：餘額不得低於 0（`setBalance` 負值被拒）。
5. 債務開啟時：餘額可為負，但受 `debt-limit` 上限約束；超過上限的提款被取消。
6. 貨幣 ID 比對為大小寫不敏感且空白安全（`currencyExists`）。

## 4. 非目標（本任務不處理）

- 不升級 Gradle / Java / Paper / AceLib。
- 不重寫 production domain / storage / plugin code。
- 不開始 Task 2（架構重寫）或 Task 3。
- 不發布、推送或建立外部狀態。
- 不保證 v1 binary / 資料 / config / banknote 相容（v2 可破壞，見 RESET 欄）。

## 5. 殘留風險與 Task 2 前置（詳見回傳報告）

- R1：本輪以 Java 21 執行；系統預設 Java 25 會導致 Gradle 8.12 Kotlin DSL 失敗。Task 2 需決定 CI/本機 `JAVA_HOME` 或升級 Gradle。
- R2：capability tests 透過 `V1CurrencyManagerAdapter` 映射 v1 行為；v2 需提供新 adapter，契約介面本身不變。
- R3：v1 沒有獨立 `transfer` 方法（轉帳由指令層組合），capability 契約目前以 deposit/withdraw 鎖定原子交易；v2 應明確 transfer 語意。
- R4：交易紀錄 / rollback 的審計規則邊界尚未以 capability test 鎖定（需 storage/log 層介入，超出本輪最小變更範圍）；建議 Task 2 補 `AuditCapability` 契約。
