# Commands & Permissions

This page lists all available commands in AceEconomy and their corresponding permissions.

## v2 AceLib command surface

The v2 presentation layer registers the following AceLib subcommands. Argument validation is
typed, currency names are completed case-insensitively, and player replies are routed through
AceLib's Folia-safe reply sink.

| Command | Usage | Sender policy | Permission |
|---|---|---|---|
| `/money` | `balance [player] [currency]` | Player for own balance; console may specify a player | `aceeconomy.command.money` |
| `/pay` | `send <player> <amount> [currency]` | Player only | `aceeconomy.command.pay` |
| `/withdraw` | `cash <amount> [currency]` | Player only | `aceeconomy.command.withdraw` |
| `/baltop` | `top [currency]` | Player or console | `aceeconomy.command.baltop` |
| `/bank` | `open` | Player only | `aceeconomy.command.bank` |
| `/aceeco` | `give`, `take`, `set` `<player> <amount> [currency]`; `reload` | Admin mutations; `reload` console only | `aceeconomy.admin.*` |

Amounts must be positive, fit the configured currency scale, and remain below the command
overflow cap. Unknown currencies, players, and typed economy failures are returned as typed
command errors rather than being matched from display text.

---

## Player Commands

These commands are the primary interaction points for players.

### `/money`

| Property | Value |
|----------|-------|
| Aliases | `/bal`, `/balance` |
| Permission | `aceeconomy.command.money` |
| Default | true |

Check your current balance.

**Example:**
- `/money`

---

### `/pay`

| Property | Value |
|----------|-------|
| Permission | `aceeconomy.command.pay` |
| Default | true |

Transfer money to another online player.

**Usage:** `/pay <player> <amount>`

**Example:**
- `/pay Smile 500` — Sends 500 to player Smile

---

### `/withdraw`

| Property | Value |
|----------|-------|
| Permission | `aceeconomy.command.withdraw` |
| Default | true |

Withdraw money as a physical banknote item. Right-click the item to deposit it back into your account.

**Usage:** `/withdraw <amount>`

**Example:**
- `/withdraw 1000` — Receive a banknote worth 1000

---

### `/baltop`

| Property | Value |
|----------|-------|
| Aliases | `/top`, `/balancetop` |
| Permission | `aceeconomy.command.baltop` |
| Default | true |

View the server's richest players. Requires SQL database (MySQL/SQLite).

**Usage:** `/baltop [page]`

**Example:**
- `/baltop` — View page 1
- `/baltop 2` — View page 2

---

### `/bank` (Dashboard)

| Property | Value |
|----------|-------|
| Aliases | `/menu`, `/bankmenu` |
| Permission | `aceeconomy.command.bank` |
| Default | true |

Open the AceEconomy Dashboard menu.

**Usage:** `/bank`

---

## Admin Commands

| Base Command | `/aceeco` (customizable in config.yml) |
|--------------|----------------------------------------|
| Permission | `aceeconomy.admin` (for all subcommands except reload) |

---

### `/aceeco give`

Give money to a player. Works even if the player is offline (requires SQL).

**Usage:** `/aceeco give <player> <amount>`

**Example:**
- `/aceeco give Smile 10000`

---

### `/aceeco take`

Remove money from a player.

**Usage:** `/aceeco take <player> <amount>`

**Example:**
- `/aceeco take Smile 5000`

---

### `/aceeco set`

Set a player's balance to a specific amount.

**Usage:** `/aceeco set <player> <amount>`

**Example:**
- `/aceeco set Smile 1000` — Set Smile's balance to exactly 1000

---

### `/aceeco history`

View the recent transaction history for a player. Shows the last 10 transactions.

**Usage:** `/aceeco history <player>`

**Information displayed:**
- Transaction ID
- Type (PAY, ADMIN, WITHDRAW, DEPOSIT, ROLLBACK)
- Amount
- Timestamp
- Whether reverted

---

### `/aceeco rollback`

Reverse a specific transaction. Useful for refunding accidental payments or correcting admin mistakes.

**Usage:** `/aceeco rollback <player> <transaction_id>`

**How it works:**
1. System verifies the transaction hasn't already been rolled back
2. System calculates the opposite amount (if +500, rollback does -500)
3. System applies the opposite amount to the player's balance
4. Original transaction is marked as `reverted = true`
5. A new `ROLLBACK` transaction is logged

---

### `/aceeco reload`

| Property | Value |
|----------|-------|
| Permission | `aceeconomy.admin.reload` |
| Default | op |

Reload `config.yml` and language files without restarting the server.

**Usage:** `/aceeco reload`

---

## Permission Nodes Summary

| Permission Node | Default | Description |
|-----------------|---------|-------------|
| `aceeconomy.command.money` | true | Access `/money` |
| `aceeconomy.command.pay` | true | Access `/pay` |
| `aceeconomy.command.withdraw` | true | Access `/withdraw` |
| `aceeconomy.command.baltop` | true | Access `/baltop` |
| `aceeconomy.command.bank` | true | Access `/bank` dashboard |
| `aceeconomy.admin` | op | Admin (wildcard) |
| `aceeconomy.admin.give` | op | Access `/aceeco give` |
| `aceeconomy.admin.take` | op | Access `/aceeco take` |
| `aceeconomy.admin.set` | op | Access `/aceeco set` |
| `aceeconomy.admin.history` | op | Access `/aceeco history` |
| `aceeconomy.admin.rollback` | op | Access `/aceeco rollback` |
| `aceeconomy.admin.reload` | op | Access `/aceeco reload` |
| `aceeconomy.bypass.debt` | op | Bypass debt limits |

---

---

# 指令與權限

本頁面列出 AceEconomy 中所有可用的指令及其對應權限。

---

## 玩家指令

這些指令是玩家與經濟系統互動的主要方式。

### `/money`

| 屬性 | 值 |
|------|-----|
| 別名 | `/bal`、`/balance` |
| 權限 | `aceeconomy.command.money` |
| 預設 | true |

查看您的當前餘額。

**範例：**
- `/money`

---

### `/pay`

| 屬性 | 值 |
|------|-----|
| 權限 | `aceeconomy.command.pay` |
| 預設 | true |

轉帳給其他線上玩家。

**用法：** `/pay <玩家> <金額>`

**範例：**
- `/pay Smile 500` — 轉帳 500 給玩家 Smile

---

### `/withdraw`

| 屬性 | 值 |
|------|-----|
| 權限 | `aceeconomy.command.withdraw` |
| 預設 | true |

將金錢提領為實體銀行支票物品。右鍵點擊該物品可存回帳戶。

**用法：** `/withdraw <金額>`

**範例：**
- `/withdraw 1000` — 獲得一張價值 1000 的支票

---

### `/baltop`

| 屬性 | 值 |
|------|-----|
| 別名 | `/top`、`/balancetop` |
| 權限 | `aceeconomy.command.baltop` |
| 預設 | true |

查看伺服器中最富有的玩家。需要 SQL 資料庫（MySQL/SQLite）。

**用法：** `/baltop [頁碼]`

**範例：**
- `/baltop` — 查看第 1 頁
- `/baltop 2` — 查看第 2 頁

---

### `/bank`（儀表板）

| 屬性 | 值 |
|------|-----|
| 別名 | `/menu`、`/bankmenu` |
| 權限 | `aceeconomy.command.bank` |
| 預設 | true |

開啟 AceEconomy 儀表板選單。

**用法：** `/bank`

---

## 管理員指令

| 主指令 | `/aceco`（可在 config.yml 中自訂）|
|--------|----------------------------------|
| 權限 | `aceeconomy.admin`（除重載外所有子指令）|

---

### `/aceeco give`

給予玩家金錢。即使玩家離線也能運作（需使用 SQL）。

**用法：** `/aceeco give <玩家> <金額>`

**範例：**
- `/aceeco give Smile 10000`

---

### `/aceeco take`

扣除玩家金錢。

**用法：** `/aceeco take <玩家> <金額>`

**範例：**
- `/aceeco take Smile 5000`

---

### `/aceeco set`

設定玩家餘額為特定金額。

**用法：** `/aceeco set <玩家> <金額>`

**範例：**
- `/aceeco set Smile 1000` — 將 Smile 的餘額設為 1000

---

### `/aceeco history`

查看玩家近期的交易記錄。顯示最近 10 筆交易。

**用法：** `/aceeco history <玩家>`

**顯示資訊：**
- 交易 ID
- 類型（PAY、ADMIN、WITHDRAW、DEPOSIT、ROLLBACK）
- 金額
- 時間戳記
- 是否已回溯

---

### `/aceeco rollback`

回溯特定交易。適用於退款意外轉帳或修正管理員錯誤。

**用法：** `/aceeco rollback <玩家> <交易ID>`

**運作方式：**
1. 系統驗證該交易尚未被回溯
2. 系統計算反向金額（若為 +500，回溯執行 -500）
3. 系統將反向金額應用於玩家餘額
4. 原始交易標記為 `reverted = true`
5. 建立一筆新的 `ROLLBACK` 交易記錄

---

### `/aceeco reload`

| 屬性 | 值 |
|------|-----|
| 權限 | `aceeconomy.admin.reload` |
| 預設 | op |

重新載入 `config.yml` 與語言檔案，無需重啟伺服器。

**用法：** `/aceeco reload`

---

## 權限節點總結

| 權限節點 | 預設 | 說明 |
|----------|------|------|
| `aceeconomy.command.money` | true | 使用 `/money` |
| `aceeconomy.command.pay` | true | 使用 `/pay` |
| `aceeconomy.command.withdraw` | true | 使用 `/withdraw` |
| `aceeconomy.command.baltop` | true | 使用 `/baltop` |
| `aceeconomy.command.bank` | true | 使用 `/bank` 儀表板 |
| `aceeconomy.admin` | op | 管理員（萬用字元）|
| `aceeconomy.admin.give` | op | 使用 `/aceeco give` |
| `aceeconomy.admin.take` | op | 使用 `/aceeco take` |
| `aceeconomy.admin.set` | op | 使用 `/aceeco set` |
| `aceeconomy.admin.history` | op | 使用 `/aceeco history` |
| `aceeconomy.admin.rollback` | op | 使用 `/aceeco rollback` |
| `aceeconomy.admin.reload` | op | 使用 `/aceeco reload` |
| `aceeconomy.bypass.debt` | op | 繞過負債限制 |
