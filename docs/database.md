# Database & Rollback System

AceEconomy uses a robust data storage system to ensure data integrity and traceability. This document covers SQL databases and the JSON fallback.

---

## SQL Database Schema

When using MySQL or SQLite, AceEconomy creates three main tables:

### 1. `ace_balances` — Player Balances

Stores the current balance for each player and currency.

| Column | Type | Description |
|--------|------|-------------|
| `uuid` | VARCHAR(36) | Player's UUID (Primary Key) |
| `currency_id` | VARCHAR(32) | Currency ID (e.g., `dollar`) (Primary Key) |
| `balance` | DOUBLE | Current amount |
| `username` | VARCHAR(16) | Cached username |
| `last_updated` | TIMESTAMP | Last modification time |

---

### 2. `ace_users` — User Cache

A lightweight table mapping UUIDs to usernames for offline lookups.

| Column | Type | Description |
|--------|------|-------------|
| `uuid` | VARCHAR(36) | Player's UUID (Primary Key) |
| `username` | VARCHAR(16) | Last known username |
| `last_seen` | BIGINT | Timestamp of last login |

---

### 3. `ace_transaction_logs` — Audit Logs

An immutable record of every financial action.

| Column | Type | Description |
|--------|------|-------------|
| `log_id` | INT | Auto-increment ID |
| `transaction_id` | VARCHAR(36) | Unique transaction UUID |
| `timestamp` | DATETIME | Time of occurrence |
| `sender_uuid` | VARCHAR(36) | Who initiated the transaction |
| `receiver_uuid` | VARCHAR(36) | Who received/lost money |
| `currency_id` | VARCHAR(32) | Which currency was used |
| `amount` | DOUBLE | The amount transferred |
| `type` | VARCHAR(32) | Type: `PAY`, `ADMIN`, `WITHDRAW`, `DEPOSIT`, `ROLLBACK` |
| `reverted` | BOOLEAN | Has this been rolled back? |

---

## HikariCP Connection Pool

AceEconomy uses HikariCP for database connection pooling, providing:

- **Efficient connection reuse** — Reduces overhead from creating new connections
- **Configurable pool size** — Control maximum concurrent connections
- **Connection validation** — Automatic detection of dead connections

Default pool settings in `config.yml`:

```yaml
storage:
  pool-size: 10           # Maximum connections
  max-lifetime: 1800000  # 30 minutes in milliseconds
```

| Setting | Default | Description |
|---------|---------|-------------|
| `pool-size` | 10 | Maximum number of connections in the pool |
| `max-lifetime` | 1800000 | Maximum connection lifetime (ms) |

> **Recommendation:** For small servers, 5-10 connections is sufficient. For large networks with high concurrency, consider increasing to 15-20.

---

## JSON Storage (Fallback)

For servers that cannot use SQL databases, AceEconomy provides a JSON-based fallback:

**File Location:** `plugins/AceEconomy/data/players.json`

**Structure:**
```json
{
  "player-uuid-1": {
    "dollar": {
      "balance": 1000.0,
      "last_updated": 1700000000000
    }
  },
  "player-uuid-2": {
    "dollar": {
      "balance": 500.0,
      "last_updated": 1700000001000
    },
    "token": {
      "balance": 10.0,
      "last_updated": 1700000001000
    }
  }
}
```

> **Limitation:** JSON storage does not support:
> - Leaderboards (`/baltop`)
> - Offline player balance modifications
> - Transaction history / Rollback

---

## Rollback System

AceEconomy features a "Smart Rollback" system to correct mistakes without wiping entire databases.

### How It Works

1. **Locate**: Admin identifies the target transaction using `/aceeco history <player>`
2. **Verify**: System checks if the transaction has already been rolled back (`reverted` flag)
3. **Reverse**: System applies the exact opposite amount:
   - If player *received* +$500, rollback *takes* $500
   - If player *sent* -$100, rollback *gives* $100
4. **Mark**: Original transaction is marked as `reverted = true`
5. **Log**: A new transaction with type `ROLLBACK` is created

### Step-by-Step Example

**Scenario:** Player A accidentally paid Player B $50,000 instead of $500.

1. Admin runs `/aceeco history PlayerB`
2. Admin finds Transaction ID `#abc-123` for +$50,000
3. Admin runs `/aceeco rollback PlayerB #abc-123`
4. System deducts $50,000 from PlayerB
5. System marks transaction `#abc-123` as reverted
6. System logs a new `ROLLBACK` transaction

> **Note:** Rollback affects only the *receiver's* balance. To reverse the sender's loss, perform a separate rollback on the sender's account.

---

---

# 資料庫與回溯系統

AceEconomy 使用穩健的資料儲存系統以確保資料完整性與可追溯性。本文件涵蓋 SQL 資料庫與 JSON 備用方案。

---

## SQL 資料庫架構

使用 MySQL 或 SQLite 時，AceEconomy 會建立三個主要資料表：

### 1. `ace_balances` — 玩家餘額

儲存每位玩家在每種貨幣下的當前餘額。

| 欄位 | 類型 | 說明 |
|------|------|------|
| `uuid` | VARCHAR(36) | 玩家 UUID（主鍵）|
| `currency_id` | VARCHAR(32) | 貨幣 ID（如 `dollar`）（主鍵）|
| `balance` | DOUBLE | 當前金額 |
| `username` | VARCHAR(16) | 快取的使用者名稱 |
| `last_updated` | TIMESTAMP | 最後修改時間 |

---

### 2. `ace_users` — 使用者快取

輕量級表格，將 UUID 對應到使用者名稱以便離線查詢。

| 欄位 | 類型 | 說明 |
|------|------|------|
| `uuid` | VARCHAR(36) | 玩家 UUID（主鍵）|
| `username` | VARCHAR(16) | 最後已知的使用者名稱 |
| `last_seen` | BIGINT | 最後登入的時間戳記 |

---

### 3. `ace_transaction_logs` — 稽核日誌

每一筆財務變動的不可變紀錄。

| 欄位 | 類型 | 說明 |
|------|------|------|
| `log_id` | INT | 自動遞增 ID |
| `transaction_id` | VARCHAR(36) | 唯一交易 UUID |
| `timestamp` | DATETIME | 發生時間 |
| `sender_uuid` | VARCHAR(36) | 發起交易者 |
| `receiver_uuid` | VARCHAR(36) | 收款/扣款者 |
| `currency_id` | VARCHAR(32) | 使用的貨幣 |
| `amount` | DOUBLE | 轉帳金額 |
| `type` | VARCHAR(32) | 類型：`PAY`、`ADMIN`、`WITHDRAW`、`DEPOSIT`、`ROLLBACK` |
| `reverted` | BOOLEAN | 是否已回溯 |

---

## HikariCP 連線池

AceEconomy 使用 HikariCP 進行資料庫連線池管理，提供：

- **高效的連線重用** — 減少建立新連線的開銷
- **可設定的池大小** — 控制最大並發連線數
- **連線驗證** — 自動偵測失效連線

`config.yml` 中的預設池設定：

```yaml
storage:
  pool-size: 10           # 最大連線數
  max-lifetime: 1800000    # 30 分鐘（毫秒）
```

| 設定 | 預設 | 說明 |
|------|------|------|
| `pool-size` | 10 | 連線池中的最大連線數 |
| `max-lifetime` | 1800000 | 連線最大生命週期（毫秒）|

> **建議：** 小型伺服器 5-10 個連線即可。大型網路高並發情境可考慮提高至 15-20。

---

## JSON 儲存（備用方案）

對於無法使用 SQL 資料庫的伺服器，AceEconomy 提供 JSON 基底備用方案：

**檔案位置：** `plugins/AceEconomy/data/players.json`

**結構：**
```json
{
  "player-uuid-1": {
    "dollar": {
      "balance": 1000.0,
      "last_updated": 1700000000000
    }
  },
  "player-uuid-2": {
    "dollar": {
      "balance": 500.0,
      "last_updated": 1700000001000
    },
    "token": {
      "balance": 10.0,
      "last_updated": 1700000001000
    }
  }
}
```

> **限制：** JSON 儲存不支援：
> - 排行榜（`/baltop`）
> - 離線玩家餘額修改
> - 交易記錄 / 回溯

---

## 回溯系統

AceEconomy 配備了「智慧回溯」系統，可修正錯誤而不需清空整個資料庫。

### 運作原理

1. **定位**：管理員使用 `/aceeco history <玩家>` 找到目標交易
2. **驗證**：系統檢查該交易是否已被回溯（`reverted` 標記）
3. **反轉**：系統套用完全相反的金額：
   - 若玩家 *收到* +$500，回溯將 *扣除* $500
   - 若玩家 *轉出* -$100，回溯將 *給予* $100
4. **標記**：原始交易標記為 `reverted = true`
5. **記錄**：建立一筆類型為 `ROLLBACK` 的新交易

### 步驟範例

**情境：** 玩家 A 不小心轉匯 $50,000 給玩家 B，原本只想轉 $500。

1. 管理員執行 `/aceeco history PlayerB`
2. 管理員找到交易 ID `#abc-123`，金額為 +$50,000
3. 管理員執行 `/aceeco rollback PlayerB #abc-123`
4. 系統扣除 PlayerB 的 $50,000
5. 系統將交易 `#abc-123` 標記為已回溯
6. 系統記錄一筆新的 `ROLLBACK` 交易

> **注意：** 回溯僅影響 *收款者* 的餘額。若要逆轉匯款者的損失，須在匯款者帳戶上執行獨立的回溯操作。
