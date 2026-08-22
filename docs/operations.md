# v2 Operations：稽核、Rollback、排行榜與匯入

本文說明 v2 `operations` 層的 service/API 與行為契約，讀者為後續 wiring、release operator 與維護者。

**狀態邊界**：本文以目前原始碼與測試為準。v2.0.0 cutover 只接 v2.0 scope 的功能；`HistoryService`、`RollbackService` 的 production wiring、command/API surface 與真實環境驗證刻意延後至 v2.1，**不屬於 v2.0.0 production 可用範圍**。Essentials/CMI `ImportService` 已自本 Plan 移除（v2.0.0 與 v2.1 均不納入）；`ImportService` 若存在於 source 只代表一般化的 service/unit contract，不代表 Essentials/CMI 產品功能，亦不承諾在任何 v2.x 版本上線。`LeaderboardService` 已由 CompositionRoot 接線。文中所有簽章皆存在於 `src/main/java/com/smile/aceeconomy/operations/**` 與 `ports/operations/**`；未驗證事項列於最後一節。

---

## 1. HistoryService — 稽核查詢（唯讀）

> **v2.1 follow-up**：本 service 的 production wiring、command/API surface 與真實環境驗證刻意延後至 v2.1，v2.0.0 不提供 audit 查詢。以下契約為 service/API 層的 unit contract（`HistoryServiceTest`），不代表 v2.0.0 production 可用。

```java
HistoryService(TransactionRepository transactions)
AuditPage query(AuditQuery q)
```

- **唯讀語意**：`query` 只呼叫 `transactions.loadAll()`，從不寫入 repository；回傳的 `AuditPage.entries()` 是 `List.copyOf` 的不可變副本。
- **過濾**：所有 filter 以 AND 組合，未設定的欄位代表「不限制」。

| Field | 比對方式 |
|---|---|
| `accountId` | 與 `Transaction.accountId()` 精確 UUID 相等 |
| `counterparty` | 與 `Transaction.counterparty()` 精確 UUID 相等 |
| `currencyId` | 兩側都經 `Currency.normalizeId`（trim + lowercase）後相等 |
| `types` | `Set<TransactionType>`；空集合 = 不限制，否則必須包含 `Transaction.type()` |
| `reasonContains` | 對 `reason` 做 case-insensitive substring；`null`／blank = 不限制 |
| `from` / `to` | 時間區間，`from` 含端點（`timestamp >= from`）、`to` 含端點（`timestamp <= to`） |

- **排序 tie-break**：先 `timestamp`，再 `id`（UUID 字典序）。`AuditQuery.Builder.ascending(boolean)` 預設 `false` → 降冪（最新在前，同時間則 id 大者在前）；設 `true` 則升冪。同一個 query 永遠得到相同順序。
- **page / limit**：0-based `page`、嚴格正數 `limit`（Builder 預設 `page=0`、`limit=50`）。`build()` 對 `page < 0` 或 `limit <= 0` 丟 `IllegalArgumentException`，不做 clamp。
- **越界頁**：`start = page * limit`；`start < 0 || start > filtered.size()` 時回傳空 `entries`，但 `total()` 仍是完整符合筆數，不會報錯。
- **`AuditPage`**：`entries()`（不可變）、`total()`（`long`）、`page()`、`limit()`。

---

## 2. RollbackService — 交易回退

> **v2.1 follow-up**：本 service 的 production wiring、command/API surface 與真實環境驗證刻意延後至 v2.1，v2.0.0 不提供 rollback 指令或 API。以下契約為 service/API 層的 unit contract（`RollbackServiceTest`），不代表 v2.0.0 production 可用；其中 `MARK_FAILED`／atomicity 風險是 v2.1 上線前必須解決的已知缺口。

```java
RollbackService(TransactionRepository transactions, ReversalExecutor executor)
RollbackResult rollback(UUID transactionId)
```

### 2.1 流程與失敗行為

`rollback` 依序執行：找目標 → 檢查 reverted marker → 分類 → 建 reversal plan → 執行 reversal → 寫 marker。各失敗點：

| 情境 | 結果 |
|---|---|
| `transactionId == null` | `failure(INVALID_REQUEST, ...)` |
| `loadAll()` 拋 `RuntimeException` | `failure(EXECUTION_FAILED, ...)` |
| 找不到該 id | `failure(UNKNOWN_TRANSACTION, ...)` |
| `isReverted` 檢查拋 `PersistenceException` | `failure(EXECUTION_FAILED, ...)` |
| 已標記 reverted | `success` + `isAlreadyReverted() == true`（no-op，不重跑 executor） |
| transfer 找不到 counterpart leg | `failure(COUNTERPART_NOT_FOUND, ...)` |
| executor 執行失敗 | `failure(EXECUTION_FAILED, ...)`；**不寫 marker** |
| reversal 成功但 `markReverted` 拋 `PersistenceException` | `failure(MARK_FAILED, ...)`；reversal 效果已發生 |
| 全部成功 | `success(reversalTransactionIds)` |

> 注意：`RollbackError.ALREADY_REVERTED` 是列舉成員，但 `rollback()` 實際上以「成功 + `isAlreadyReverted()`」回傳，不會把已回退當成失敗。

### 2.2 RollbackCategory 與 reversal 方向

`categorize(Transaction)` 依 `TransactionType` 決定類別，`ReversalPlan` 的 delta 方向如下：

| Category | 對應 type | Reversal delta |
|---|---|---|
| `DEPOSIT` | `DEPOSIT` | `-amount`（把存入金額扣回） |
| `WITHDRAW` | `WITHDRAW` | `+amount`（把提領金額加回） |
| `SET` | `SET` | `balanceBefore - balanceAfter`（恢復先前餘額） |
| `TRANSFER` | `TRANSFER_OUT` / `TRANSFER_IN` | sender `+amount`、receiver `-amount`，兩腿一起處理 |

**Transfer counterpart 比對**：另一腿必須 type 互換、`accountId`/`counterparty` 互換、normalized currency 相同、`amount` 相同、`timestamp` 相同；任一條件不符即 `COUNTERPART_NOT_FOUND`。成功時 `markerIds` 同時包含兩腿，兩筆都會標記 reverted。

### 2.3 ReversalExecutor 與 marker persistence 的界線

- **執行**：`ReversalExecutor.execute(ReversalPlan)` 負責套用所有 `AccountDelta` 並以 `appendBatch` 原子寫入 reversal audit records（reason 格式為 `rollback:<category>`，例如 `rollback:deposit`）。目前唯一實作是 `InMemoryReversalExecutor`。
- **marker**：`RollbackService` 在 executor **成功之後**才呼叫 `transactions.markReverted(markerId)`。executor 不碰 marker，service 不碰餘額變更。
- **executor 契約**：實作必須「全部生效或全部不生效」；`InMemoryReversalExecutor` 是循序執行（先 `appendBatch` 再存帳戶），對 in-memory repository 原子，但**不滿足 production 契約**。v2.1 必要前置條件是 transactional rollback executor：把餘額變更與 record append 包進單一 storage transaction，否則 2.4 的 `EXECUTION_FAILED` 重試與 `MARK_FAILED` 風險無法在真實環境安全排除。

### 2.4 Retry / idempotency 風險

- `EXECUTION_FAILED` 不寫 marker → 設計上可重試。但若 executor 在失敗前已部分生效（例如 record 已 append、帳戶未存），重試會重複 append；這取決於注入的 executor 是否真的原子。
- `MARK_FAILED` 是最危險的狀態：**reversal 已生效但 marker 沒寫入**，重跑 `rollback` 會再次執行 reversal，造成雙重扣/加。operator 遇到 `MARK_FAILED` 應先人工核對帳戶與 audit records，再決定是否手動補 marker，不要直接重試。
- 已回退的 no-op 是安全冪等：`isAlreadyReverted()` 的結果不會再呼叫 executor。

---

## 3. LeaderboardService — 排行榜

```java
LeaderboardService(LeaderboardSource source, Clock clock, LeaderboardCache cache, Duration ttl)
LeaderboardPage query(String currencyId, int page, int limit)
void invalidate(String currencyId)
void invalidateAll()
```

- **參數驗證**：`currencyId` 為 `null`／blank、`page < 0`、`limit <= 0` 一律丟 `IllegalArgumentException`；建構子的 `ttl` 不得為負。
- **排序（deterministic）**：`source.rows(currencyId)` → 依 `balance` 降冪，同餘額以 `accountId`（UUID）升冪 tie-break；`rank` 從 1 開始。同一份資料永遠得到相同排名。
- **分頁**：0-based `page`、嚴格正數 `limit`。`totalPages = ceil(total / limit)`（`total == 0` 時為 0）；`start >= total` 時回傳空 `entries`，不報錯。
- **`LeaderboardPage`**：`entries()`（不可變）、`page()`、`limit()`、`totalEntries()`、`totalPages()`。`LeaderboardEntry` 為 record（`rank`、`accountId`、`ownerName`、`balance`），`ownerName` 為 `null` 時 fallback 成 `accountId.toString()`。

### 3.1 LeaderboardCache — TTL 與失效

- 每個 currency 一個不可變 ranking snapshot + `computedAt`，存於 `ConcurrentHashMap`。
- `getIfFresh(currencyId, ttl, now)`：快取存在且 `computedAt + ttl` 未嚴格早於 `now` 時回傳不可變副本，否則 `null`（等於 TTL 邊界仍算新鮮）。
- 重新計算時機：快取缺失、TTL 過期、`invalidate(currencyId)`、`invalidateAll()`。
- 永不對外暴露內部可變集合；所有回傳都是 `List.copyOf`。

### 3.2 LeaderboardSource — SQL/JSON 共用契約

排行榜邏輯只依賴 port：

```java
List<LeaderboardRow> rows(String currencyId)
```

`LeaderboardRow` 是標準化 row（`accountId`、`ownerName`、`balance`）。v2.0.0 由 `bootstrap/ProductionAdapters.RepositoryLeaderboardSource` 接線，透過 `AccountRepository.listAll()` 讀取 persistence 資料；`InMemoryLeaderboardSource` 保留為測試替身與記憶體模式來源。ranking、cache、分頁邏輯與後端無關。

---

## 4. ImportService — Essentials/CMI 匯入（已自本 Plan 移除）

> **Essentials / CMI import 不屬於本 Plan 範圍**：依使用者決策，Essentials/CMI balance import 不屬於 v2.0.0，也不再列入本 Plan 的 v2.1 follow-up；本 Plan 不引入 vendor parser、import command/API 或 v1 → v2 migration 相容層。`ImportService` 若保留於 source，僅是一般化的 service/unit contract（`ImportServiceTest`），不代表 Essentials/CMI 產品功能，亦不承諾在任何 v2.x 版本上線；後續可另行刪除或重新規劃，不阻擋本 Plan。
>
> 本節以下契約僅為讀者理解 source 結構之用，不代表 v2.0.0 或 v2.1 承諾項目；production wiring 與 command/API surface 都不在範圍內。

```java
ImportService(CurrencyRegistry currencies, AccountRepository accounts,
              TransactionRepository transactions, Clock clock, IdempotencyGuard idempotency)
ImportReport importRecords(List<ImportRecord> records, ImportOptions options)
```

### 4.1 輸入契約（normalized input）

- `ImportRecord(source, sourceRecordId, accountUuid, ownerName, currencyId, amount)`：`source` 為 `ImportSource`（`ESSENTIALS`／`CMI`）；`sourceRecordId` 是 source 內的 idempotency key；`ownerName` 可為 `null`（建立帳戶時 fallback 成 uuid 字串）；`amount` 是要**設定**的目標餘額。
- `ImportOptions(dryRun, createMissingAccounts)`。
- **vendor parsing 界線**：本 service 只接受已標準化的 `ImportRecord`；Essentials/CMI export 檔的解析與 vendor 檔案探索**不在本 Plan 範圍內，亦不承諾實作**。本 service 也不做任何自動 v1 → v2 資料 migration。

### 4.2 逐筆流程

每筆 record 依序：currency 存在性 → amount 非負 → idempotency key → dry-run／apply。idempotency key 為 `UUID.nameUUIDFromBytes((source.name() + ":" + sourceRecordId).getBytes(UTF_8))`。

| 情境 | 結果 |
|---|---|
| 未知 currency | `FAILED`（`unknown currency: ...`） |
| `amount == null` 或負數 | `FAILED`（`amount must be non-negative`） |
| dry-run 且 key 已消費 | `SKIPPED_DUPLICATE`（`already applied (dry-run)`） |
| dry-run 且 key 未消費 | `APPLIED`（`would apply (dry-run)`），**零寫入** |
| 非 dry-run 且 key 已消費 | `SKIPPED_DUPLICATE`（`already applied`） |
| 帳戶存在 | `setBalance` + 存帳戶 + append SET record + 消費 key → `APPLIED(txId)` |
| 帳戶不存在且 `createMissingAccounts=false` | `FAILED`（`account does not exist and createMissingAccounts=false`） |
| 帳戶不存在且 `createMissingAccounts=true` | 建立帳戶（所有 currency 設 0、本 currency 設匯入值）+ append SET record + 消費 key → `APPLIED(txId)` |
| apply 過程拋 `RuntimeException` | `FAILED`（`apply failed: ...`）；**不消費 key**，可重試 |

**dry-run 注意**：dry-run 只驗證 currency、amount 與 idempotency 狀態，**不檢查帳戶存在性／`createMissingAccounts`**，因此「真實模式會因缺帳戶而失敗」的 record 在 dry-run 仍會回報 `would apply`。dry-run 是驗證預覽，不是完整預測。

### 4.3 匯入的 audit record（metadata reason）

每筆成功匯入會 append 一筆 `TransactionType.SET` record，`counterparty = null`，`reason = "import:" + source.name()`（例如 `import:ESSENTIALS`、`import:CMI`），`amount` 為匯入的目標餘額，`balanceBefore`/`balanceAfter` 反映變更前後。

### 4.4 失敗隔離與報告

- 單筆失敗不中斷其他 record；`ImportRecordResult.Status` 為 `APPLIED`／`SKIPPED_DUPLICATE`／`FAILED`。
- `ImportReport`：`dryRun()`、`appliedCount()`、`skippedCount()`、`failedCount()`、`results()`（不可變）。
- `fullySuccessful()` 只有在 `failedCount == 0` 時為 `true`；任何失敗都不會回報整體成功。
- 失敗的 record 不消費 idempotency key → 修正後可安全重跑。

---

## 5. 操作注意事項

- **Rollback 前先備份**。Rollback 會改餘額、append audit records，實務上不可逆；備份／restore 程序見 `docs/persistence.md`（`backup()` / `restore()`，JSON 與 SQL 後端互通）。
- **`MARK_FAILED` 不要直接重試**（見 2.4）：先人工核對帳戶與 audit records。
- **v2.1 follow-up（刻意 deferred）**：`HistoryService`、`RollbackService` 的 production wiring、command/API surface 與真實環境驗證刻意延後至 v2.1，不屬於 v2.0.0 production 可用範圍；本文所載契約以原始碼與 unit contract 為準，不得視為已上線功能。
- **v2.1 必要前置條件（History/Rollback 上線前）**：transactional rollback executor（見 2.3，把餘額變更與 audit record append 包進單一 storage transaction）、production persistent `IdempotencyGuard`、CompositionRoot wiring、commands/API 與 integration/runtime tests；補齊後才可移除上述 deferred 狀態。
- **Essentials/CMI import 不在範圍**：本文件 §4 僅記錄 source 中的 service/unit contract；vendor parsing、import command/API 不屬於 v2.0.0 亦非 v2.1 承諾項目。
- **本文件沒有 live 驗證**：MySQL 路徑只有 offline contract tests（見 `docs/persistence.md`），本環境沒有 live DB 或 Folia runtime；所有行為來自原始碼與單元測試。
- **真實伺服器驗證未執行**：Folia fresh-install 啟動、RCON／遊戲內驗證、故障演練、backup/restore 實測與發布文件皆尚未執行；屬 v2.0.0 release gate，不屬 v2.1 follow-up。