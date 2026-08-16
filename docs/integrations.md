# AceEconomy v2 整合模組說明（Integrations）

本文件說明 v2 的四大外部整合：Vault、PlaceholderAPI、Discord、AceLib 協調器。
所有整合都位於 `infrastructure.integration.*`，與領域層（`domain`）、應用層（`application`）完全解耦；
領域與應用層不依賴任何外部插件。

> 注意：本文件描述的是**模組本身**的行為契約。實際在伺服器上啟用這些模組，
> 是由 `bootstrap` / CompositionRoot（Task 12）負責；本任務不修改插件入口。

---

## 1. Vault 經濟適配器

- 類別：`VaultEconomyProvider`（實作 Vault `Economy`）、`VaultEconomyLifecycle`、`VaultIntegrationModule`
- 作用：把 v2 型別化 `EconomyApi` 對映到 Vault 的同步 `Economy` 合約，讓依賴 Vault 的插件可用 AceEconomy 當作經濟後端。
- 貨幣：Vault 沒有「多貨幣」概念，適配器**永遠只操作註冊表預設貨幣**（`CurrencyRegistry.defaultCurrencyId()`）。
- 失敗語意（fail-closed，絕不謊報成功）：
  - `deposit` / `withdraw` 成功 → `EconomyResponse` 帶新餘額、`SUCCESS`。
  - `deposit` / `withdraw` 失敗 → `EconomyResponse` 帶 `amount=0`、目前（或零）餘額、`FAILURE` 與 v2 訊息；**不重試**。
  - 餘額 / `has` 在帳號不存在時 → `0.0` / `false`（安全預設，不拋例外）。
  - 以玩家名稱（非 `OfflinePlayer`）呼叫的帳號方法 → 一律 `false` / `FAILURE`（v2 以 UUID 為主鍵）。
  - 銀行功能 → `NOT_IMPLEMENTED`。
- 生命週期：`VaultEconomyLifecycle` 擁有註冊所有權，且**冪等**——重複 `start()` 不會重複註冊；`stop()` 一定會反註冊。
  註冊失敗（例如 Vault 不存在）時，不會留下半初始化狀態。

---

## 2. PlaceholderAPI 命名空間 `aceeco`

- 類別：`PlaceholderResolver`（純邏輯、無外部依賴）、`AceEconomyExpansion`（PAPI 生命週期適配）、`PlaceholderLifecycle`、`PlaceholderIntegrationModule`
- 命名空間識別字：**`aceeco`**（見 `AceEconomyExpansion.IDENTIFIER`）
- 支援的占位符（全部小寫，針對預設或具名貨幣求值）：

  | 占位符 | 說明 |
  | --- | --- |
  | `%aceeco_balance%` | 預設貨幣餘額，原始數值（例如 `100.00`） |
  | `%aceeco_balance_formatted%` | 預設貨幣餘額，含符號（例如 `$100.00`） |
  | `%aceeco_balance_<currency>%` | 具名貨幣原始餘額 |
  | `%aceeco_balance_<currency>_formatted%` | 具名貨幣餘額，含符號 |

- 失敗語意（fail-closed）：任何**未知占位符名稱、畸形貨幣 id（非 `[a-z0-9_]+`）、未知貨幣、或帳號不可用**都解析為 `null`。
  PAPI 收到 `null` 會保留原始占位符文字，而不是顯示錯誤值。
- 生命週期：`PlaceholderLifecycle` 擁有註冊所有權且**冪等**；`stop()` 一定反註冊。

---

## 3. Discord 通知（已提交事件的盡力而為通知）

- 類別：`DiscordNotifier`、`TransactionDiscordMapper`、`DiscordPayload`、`DiscordPayloadFilter`、`DiscordTransport`（介面）、`HttpDiscordTransport`（生產綁定）、`DiscordNotificationRequest`
- 契約：呼叫 `DiscordNotifier.notify(...)` 時，**底層經濟交易已經提交**。通知器嚴格「盡力而為」：
  - **非同步**：投遞交給注入的 `Executor`，方法立即返回，絕不因網路而阻塞。
  - **有界**：`Executor` 由呼叫方提供（通常是固定大小執行緒池 + 有界佇列），事件爆量也不會無限成長。
  - **盡力而為 / 不否決**：任何對映錯誤、傳輸拒絕、逾時或投遞失敗都會被吞掉。通知器不持有已提交結果的參考，也**絕不能回滾或否決**該結果。
- 敏感資訊：`TransactionDiscordMapper` 會透過 `DiscordPayloadFilter.sanitize(value, MAX_*, secrets)` 對每個欄位做長度限制與密鑰遮蔽；**Webhook URL 永遠不會進入 payload**。
- 生產綁定：`HttpDiscordTransport` 使用 `HttpClient.sendAsync` 發送；測試使用 `FakeDiscordTransport`（SUCCESS / FAIL / HANG 模式）驗證非否決與非阻塞行為。

---

## 4. AceLib 協調器（外部就緒門控 + 停用）

- 類別：`ExternalIntegrationCoordinator`、`ExternalServiceReadiness`（介面）、`AceLibExternalServiceReadiness`（AceLib 實作）、`IntegrationModule`、`ModuleState`、`Readiness`
- 作用：擁有所有 v2 整合模組的生命週期，並以外部服務就緒狀態作為閘門。
- 契約：
  - 模組的 `requiredExternalModule()` 非 null 時，會透過 `ExternalServiceReadiness.probe(...)` 探測；只有 `Readiness.READY` 才初始化。
    其他任何就緒狀態都讓模組保持 `DISABLED`——不註冊任何 provider，也不留下半初始化服務。
  - `requiredExternalModule()` 為 null 的模組（例如 Discord）一律初始化（盡力而為）。
  - 若 `initialize()` 拋例外，協調器會對該模組呼叫 `shutdown()` 以保證無殘留，並標記為 `FAILED`；已初始化的兄弟模組不受影響。
  - `start()` 對每個模組**冪等**（已初始化者保持不變）。
  - `stop()` **冪等**，且對每個已初始化模組恰好拆除一次。
- `Readiness` 取值：`READY`、`NOT_INSTALLED`、`NOT_ENABLED`、`VERSION_UNSUPPORTED`、`INIT_FAILED`、`UNAVAILABLE`。
- `ModuleState` 取值：`NOT_STARTED`、`INITIALIZED`、`DISABLED`、`FAILED`。
- `status()` 回傳 `Map<String, ModuleState>` 快照，可用於運行時診斷。

---

## 5. 運行時限制（本任務範圍外）

- 本任務**不**修改 `AceEconomy.java`、bootstrap / CompositionRoot、`plugin.yml`、設定檔、舊版指令 / 管理員 / hook / 服務 / 儲存層。
- 在真實伺服器上啟用這些模組、注入 `Executor`、提供 Webhook URL、串接 AceLib 探測，都屬於 Task 12（CompositionRoot）與後續發布驗證。
- 本任務的測試使用可控的 fake 與執行緒池，**不觸發任何真實網路或睡眠**，因此可在 CI / 離線環境重現。
