# 🎮 Commands & Permissions / 指令與權限

This page lists all available commands in AceEconomy and their corresponding permissions.
本頁面列出 AceEconomy 中所有可用的指令及其對應權限。

---

## 🏗️ Core Commands / 核心指令

These commands are the primary interaction points for players.
這些指令是玩家與經濟系統互動的主要方式。

### `/money`
**Alias**: `/bal`, `/balance`
**Permission**: `aceeconomy.use`

Check your current balance.
查看您的當前餘額。

**Example**:
- `/money`

### `/pay`
**Permission**: `aceeconomy.pay`

Send money to another online player.
轉帳給其他線上玩家。

**Usage**: `/pay <player> <amount>`
**用法**: `/pay <玩家> <金額>`

**Example**:
- `/pay Smile 500` - Sends 500 currency to Smile. (轉帳 500 元給 Smile)

### `/withdraw`
**Permission**: `aceeconomy.withdraw`

Withdraw money into a physical banknote item. Right-click the item to deposit it back.
將金錢提領為實體銀行支票物品。右鍵點擊該物品可存回。

**Usage**: `/withdraw <amount>`
**用法**: `/withdraw <金額>`

**Example**:
- `/withdraw 1000` - Receive a banknote worth 1000. (獲得一張價值 1000 的支票)

### `/baltop`
**Alias**: `/top`, `/balancetop`
**Permission**: `aceeconomy.command.baltop`

View the server's richest players. Requires SQL database (MySQL/SQLite) for optimal performance.
查看伺服器中最富有的玩家。建議使用 SQL 資料庫 (MySQL/SQLite) 以獲得最佳效能。

**Usage**: `/baltop [page]`
**用法**: `/baltop [頁碼]`

**Example**:
- `/baltop` - View page 1 (default). (查看第 1 頁)
- `/baltop 2` - View page 2. (查看第 2 頁)

---

## 🛠️ Admin Commands / 管理員指令

**Base Command**: `/aceeco` (Can be customized in `config.yml`)
**主指令**: `/aceeco` (可在 `config.yml` 中自訂)
**Permission**: `aceeconomy.admin` (Required for all subcommands below except reload)
**權限**: `aceeconomy.admin` (除重載外，所有子指令皆需要此權限)

### `give`
**Usage**: `/aceeco give <player> <amount>`
Give money to a player. Works even if the player is offline (requires SQL).
給予玩家金錢。即使玩家離線也能運作 (需使用 SQL)。

### `take`
**Usage**: `/aceeco take <player> <amount>`
Remove money from a player.
扣除玩家金錢。

### `set`
**Usage**: `/aceeco set <player> <amount>`
Set a player's balance to a specific amount.
設定玩家餘額為特定金額。

### `history`
**Usage**: `/aceeco history <player>`
View the recent transaction history for a player. Shows the last 10 transactions.
查看玩家近期的交易記錄。顯示最近 10 筆交易。

Includes:
- Transaction ID (交易 ID)
- Type (Deposit/Withdraw) (類型：存款/提款)
- Amount (金額)
- Time (時間)

### `rollback`
**Usage**: `/aceeco rollback <player> <transaction_id>`
Reverses a specific transaction. Useful for refunding accidental payments or correcting admin mistakes.
回溯特定交易。適用於退款意外轉帳或修正管理員錯誤。

**How it works**:
It calculates the reverse operation (e.g., if ID#5 was `+500`, rollback does `-500`) and logs a new `ROLLBACK` transaction.
**運作方式**：
系統計算反向操作 (例如 ID#5 是 `+500`，回溯將執行 `-500`) 並記錄一筆新的 `ROLLBACK` 交易。

### `reload`
**Permission**: `aceeconomy.command.reload`
**Usage**: `/aceeco reload`
Reloads `config.yml` and language files.
重新載入 `config.yml` 與語言檔案。

---

## 🔐 Permission Nodes Summary / 權限節點總結

| Node / 節點 | Default / 預設 | Description / 描述 |
|---|---|---|
| `aceeconomy.use` | true | Access `/money`. (使用 `/money`) |
| `aceeconomy.pay` | true | Access `/pay`. (使用 `/pay`) |
| `aceeconomy.withdraw` | true | Access `/withdraw`. (使用 `/withdraw`) |
| `aceeconomy.command.baltop` | true | Access `/baltop`. (使用 `/baltop`) |
| `aceeconomy.admin` | op | Access admin commands (`give`, `take`, `set`...). (使用管理指令) |
| `aceeconomy.command.reload` | op | Access `/aceeco reload`. (使用重載指令) |
