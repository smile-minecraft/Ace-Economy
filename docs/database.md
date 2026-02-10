# 🗄️ Database & Rollback System / 資料庫與回溯系統

AceEconomy uses a robust SQL schema to ensure data integrity and traceability.
AceEconomy 使用穩健的 SQL 架構以確保資料完整性與可追溯性。

## 📊 Database Schema / 資料庫架構

The system consists of three main tables (besides the schema history).
系統由三個主要資料表組成（除版本紀錄表外）。

### 1. `ace_balances` (Player Balances / 玩家餘額)
Stores the current balance for each player and currency.
儲存每位玩家在每種貨幣下的當前餘額。

| Column | Type | Description |
|---|---|---|
| `uuid` | VARCHAR(36) | Player's UUID (玩家 UUID) [PK] |
| `currency_id` | VARCHAR(32) | Currency ID (e.g., `dollar`) [PK] |
| `balance` | DOUBLE | Current amount (當前金額) |
| `username` | VARCHAR(16) | Cached username (快取的使用者名稱) |
| `last_updated` | TIMESTAMP | Last modification time (最後修改時間) |

### 2. `ace_users` (User Cache / 使用者快取)
A lightweight table to map UUIDs to Usernames for offline lookups.
一個輕量級的表，用於將 UUID 對應到使用者名稱，以便進行離線查詢。

| Column | Type | Description |
|---|---|---|
| `uuid` | VARCHAR(36) | Player's UUID [PK] |
| `username` | VARCHAR(16) | Last known username |
| `last_seen` | BIGINT | Timestamp of last login |

### 3. `ace_transaction_logs` (Audit Logs / 稽核日誌)
An immutable record of every financial action.
每一筆財務變動的不可變紀錄。

| Column | Type | Description |
|---|---|---|
| `log_id` | INT | Auto-increment ID |
| `transaction_id` | VARCHAR(36) | Unique Transaction UUID (交易唯一碼) |
| `timestamp` | DATETIME | Time of occurrence |
| `sender_uuid` | VARCHAR(36) | Who initiated the transaction |
| `receiver_uuid` | VARCHAR(36) | Who received/lost money |
| `currency_id` | VARCHAR(32) | Which currency was used |
| `amount` | DOUBLE | The amount transferred |
| `type` | VARCHAR(32) | Type: `PAY`, `ADMIN`, `WITHDRAW`, `DEPOSIT`... |
| `reverted` | BOOLEAN | Has this been rolled back? (是否已回溯) |

---

## ↩️ Rollback System / 回溯系統

AceEconomy features a "Smart Rollback" system to correct mistakes without wiping entire databases.
AceEconomy 配備了「智慧回溯」系統，可修正錯誤而不需清空整個資料庫。

### How it works (運作原理)

1. **Locate**: The admin identifies the target transaction using `/aceeco history <player>`.
   **定位**：管理員使用 `/aceeco history` 找到目標交易。
2. **Verify**: The system checks if the transaction has already been rolled back (`reverted` flag).
   **驗證**：系統檢查該交易是否已被回溯（檢查 `reverted` 標記）。
3. **Reverse**: The system applies the **exact opposite** amount to the affected player's balance.
   **反轉**：系統將**完全相反**的金額應用於受影響玩家的餘額。
   - If user *received* $500, rollback will *take* $500.
   - 若使用者 *收到* 500，回溯將 *扣除* 500。
4. **Mark**: The original transaction is marked as `reverted = true` to prevent double rollbacks.
   **標記**：原始交易被標記為 `reverted = true` 以防止重複回溯。
5. **Log**: A new transaction with type `ROLLBACK` is created to record this correction action.
   **記錄**：建立一筆類型為 `ROLLBACK` 的新交易以記錄此修正動作。

### Example (範例)

**Scenario**: Player A accidentally paid Player B $50,000 instead of $500.
**情境**：玩家 A 不小心轉匯 $50,000 給玩家 B，原本只想轉 $500。

1. Admin runs `/aceeco history PlayerB`.
2. Admin sees Transaction ID `#abc-123` for +$50,000.
3. Admin runs `/aceeco rollback PlayerB #abc-123`.
4. System deducts $50,000 from PlayerB.
5. System marks transaction `#abc-123` as reverted.
   (此時玩家 A 仍需拿回錢，可能需要管理員手動給予或同樣rollback A 的支出記錄，視具體邏輯而定。通常 Rollback 是針對單一帳戶的變更進行反轉)

> **Note**: Rollback affects the *target player's* balance state. It corrects the specific balance change associated with that transaction ID.
> **注意**：回溯僅影響 *目標玩家* 的餘額狀態。它修正與該交易 ID 關聯的特定餘額變動。
