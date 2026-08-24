# Commands and permissions

You are checking your balance, sending money, or opening the bank menu. The command surface is
split into player tools and administrator tools so the command you need is easy to find. This
reference uses the current v2 syntax: every command is followed by its named subcommand.

## Quick reference

| Root command | Subcommand | Usage | Sender | Permission | Alias |
|---|---|---|---|---|---|
| `/money` | `balance` | `[player] [currency]` | Player or console; a console sender must provide `player` | `aceeconomy.command.money` | `/balance` |
| `/pay` | `send` | `<player> <amount> [currency]` | Player only | `aceeconomy.command.pay` | None |
| `/withdraw` | `cash` | `<amount> [currency]` | Player only | `aceeconomy.command.withdraw` | None |
| `/baltop` | `top` | `[currency]` | Player or console | `aceeconomy.command.baltop` | None |
| `/bank` | `open` | no arguments | Player only | `aceeconomy.command.bank` | None |
| `/aceeco` | `give`, `take`, `set`, `history`, `reload`, `rollback`, `backup`, `restore` | See the administrator reference | `reload`, `rollback`, and `restore`: console only; `backup` and other subcommands: player or console | `aceeconomy.admin` plus the subcommand node | None |

`<required>` values must be supplied. `[optional]` values may be omitted. If `currency` is
omitted, the configured default currency is used. Currency IDs are matched without regard to
letter case.

Amounts must be valid numbers, greater than zero, within the currency's decimal scale, and no
greater than `1,000,000,000,000,000`.

The only v2 root alias listed by the command specification is `/balance` for `/money`. It uses the
same `balance` subcommand: `/balance balance [player] [currency]`. There are no separate `/backup`
or `/restore` root commands; use the `/aceeco` subcommands shown below.

## For players

### Check a balance: `/money balance`

Use this when you want to see your own balance. Add a player name when looking up another account;
the console must use that form because it has no player account of its own.

| Item | Details |
|---|---|
| Usage | `/money balance [player] [currency]` |
| Sender | Player or console; a player may omit `player`, while console must provide it |
| Permission | `aceeconomy.command.money` |
| Alias | `/balance` for the root command; keep the `balance` subcommand |

Examples:

```text
/money balance
/money balance Alex <currency>
/balance balance Alex <currency>
```

### Send money: `/pay send`

Use this to transfer money to another player. The command must be run by a player.

| Item | Details |
|---|---|
| Usage | `/pay send <player> <amount> [currency]` |
| Sender | Player only |
| Permission | `aceeconomy.command.pay` |
| Alias | None listed by the v2 command specification |

Example: `/pay send Alex 25 <currency>`

### Withdraw a banknote: `/withdraw cash`

Use this to withdraw an amount as a banknote. The command must be run by a player.

| Item | Details |
|---|---|
| Usage | `/withdraw cash <amount> [currency]` |
| Sender | Player only |
| Permission | `aceeconomy.command.withdraw` |
| Alias | None listed by the v2 command specification |

Example: `/withdraw cash 100 <currency>`

### View the leaderboard: `/baltop top`

Use this to list the highest balances. A player or the console can run it; choose a currency when
you do not want to use the configured default.

| Item | Details |
|---|---|
| Usage | `/baltop top [currency]` |
| Sender | Player or console |
| Permission | `aceeconomy.command.baltop` |
| Alias | None listed by the v2 command specification |

Examples:

```text
/baltop top
/baltop top <currency>
```

### Open the bank: `/bank open`

Use this to open the AceEconomy bank interface. The command must be run by a player and takes no
arguments.

| Item | Details |
|---|---|
| Usage | `/bank open` |
| Sender | Player only |
| Permission | `aceeconomy.command.bank` |
| Alias | None listed by the v2 command specification |

The current GUI action contract is:

- `DEPOSIT`: slot `4` (the upper-middle cell).
- `WITHDRAW`: the existing slots `11` and `13` (the `100` and `500` withdrawal buttons).
- `CLOSE`: slot `15`.

For a valid v2 banknote, durable replay protection and credit complete before the banknote is
removed or its stack is reduced. Invalid, replayed, or credit-failed banknotes remain in the
player's inventory. Right-click redemption is not implemented.

## For administrators

The administrator root is `/aceeco`, with no alias listed by the v2 command specification. The
root permission is `aceeconomy.admin`; each operation also declares its own permission node. The
mutation commands accept the same player, amount, and optional currency pattern as the player
commands. `history` reads recorded transactions instead of changing balances. `reload`, `rollback`,
and `restore` are console-only; `rollback` takes a transaction id and `restore` takes a backup id plus
the exact confirmation word `confirm`.

| Subcommand | Usage | Sender | Subcommand permission | Alias |
|---|---|---|---|---|
| `give` | `/aceeco give <player> <amount> [currency]` | Player or console | `aceeconomy.admin.give` | None |
| `take` | `/aceeco take <player> <amount> [currency]` | Player or console | `aceeconomy.admin.take` | None |
| `set` | `/aceeco set <player> <amount> [currency]` | Player or console | `aceeconomy.admin.set` | None |
| `history` | `/aceeco history [player] [currency] [page]` | Player or console | `aceeconomy.admin.history` | None |
| `reload` | `/aceeco reload` | Console only | `aceeconomy.admin.reload` | None |
| `rollback` | `/aceeco rollback <transaction-id>` | Console only | `aceeconomy.admin.rollback` | None |
| `backup` | `/aceeco backup [label]` | Player or console | `aceeconomy.admin.backup` | None |
| `restore` | `/aceeco restore <backup-id> confirm` | Console only | `aceeconomy.admin.restore` | None |

The declared defaults are `true` for the player command permissions and `op` for the
administrator permissions. The plugin also declares `aceeconomy.bypass.debt` with an `op` default
for debt-limit bypass access.

### Add to a balance: `/aceeco give`

Use `give` when an administrator needs to add an amount to a player's balance.

Example: `/aceeco give Alex 500 <currency>`

### Remove from a balance: `/aceeco take`

Use `take` when an administrator needs to subtract an amount from a player's balance.

Example: `/aceeco take Alex 125 <currency>`

### Set a balance: `/aceeco set`

Use `set` when an administrator needs to assign a player's balance to a specific amount.

Example: `/aceeco set Alex 1000 <currency>`

### Query the transaction history: `/aceeco history`

Use `history` when an administrator needs to review recorded balance changes. It is read-only:
it never changes balances or audit records. Omitting `player` lists transactions for every
account; omitting `currency` uses the configured default currency; `page` is 0-based and each
page shows 10 entries. An empty page, an unknown player, and a page number below zero each get
an explicit reply.

| Item | Details |
|---|---|
| Usage | `/aceeco history [player] [currency] [page]` |
| Sender | Player or console |
| Permission | `aceeconomy.admin.history` |
| Ordering | Newest first, with a stable tie-break so repeated queries list rows in the same order |

Examples:

```text
/aceeco history
/aceeco history Alex
/aceeco history Alex <currency>
/aceeco history Alex <currency> 2
```

### Reload the economy configuration: `/aceeco reload`

Run this from the console after changing economy configuration. It takes no arguments and cannot
be run by a player.

Example: `/aceeco reload`

### Roll back a transaction: `/aceeco rollback`

Use `rollback` when an administrator needs to reverse a recorded transaction by its id. This is a
destructive administrative action, so it is console-only and requires both `aceeconomy.admin` and
`aceeconomy.admin.rollback`. The transaction id is the UUID shown for the transaction; it must be
a valid UUID or the command is rejected before anything is touched.

Outcomes are reported explicitly:

| Outcome | Reply |
|---|---|
| Success | Names the rolled back transaction and lists the reversal audit record ids. |
| Already reverted | States the transaction was already reverted and no changes were made; re-running the same id is a safe no-op and never duplicates balance effects or audit records. |
| Unknown transaction | Typed error: no transaction with that id exists. |
| Invalid id | Typed error: the argument is not a valid UUID; nothing is looked up. |
| Missing transfer counterpart | Typed error: one leg of a transfer could not be located, so a safe reversal is impossible. |
| Execution failed | Typed error: the reversal was not applied and the transaction stays retryable. |
| Marker persist failed | Typed error stating the reversal may already be applied while the reverted marker is missing; inspect storage and reconcile manually before any retry. |

Example: `/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b`

The rollback command is wired into the production command surface and covered by automated
contract tests, but it has **not** been verified on a live server yet: Folia/Bukkit bridge
execution, live MySQL storage, and fault-injection drills with real data are still open release
gates. Treat the outcomes above as the designed contract until that validation is done.

### Create a logical backup: `/aceeco backup`

Use this to create a v2 logical JSON snapshot while the server is running. The optional label is
written into the generated backup id and is restricted to safe filename characters.

| Item | Details |
|---|---|
| Usage | `/aceeco backup [label]` |
| Sender | Player or console |
| Permission | `aceconomy.admin.backup` (with the root `aceconomy.admin`) |
| Storage | Plugin-controlled `<plugin data folder>/backups` directory |
| Output | Atomic, never-overwriting snapshot; the command reports its backup id |

The snapshot is a logical v2 JSON model. It includes accounts, balances, transactions, reverted
markers, and consumed nonces, but not database passwords or webhook URLs. There is no separate root
`/backup` command.

### Restore a logical backup: `/aceeco restore`

Restore replaces the live economy state, so it is console-only and requires both the root and child
permissions. The confirmation token is case-sensitive: only lowercase `confirm` is accepted.

| Item | Details |
|---|---|
| Usage | `/aceeco restore <backup-id> confirm` |
| Sender | Console only; restore is rejected while any player is online |
| Permission | `aceconomy.admin.restore` (with the root `aceeconomy.admin`) |
| Preflight | JSON shape, schema version, records, and configured-currency compatibility are checked before live data is touched |
| Safety | A safety backup of the current state is created first; if it fails, restore is aborted |
| Success boundary | The leaderboard cache is cleared, but sessions and GUIs are not hot-refreshed. Restart the server before players return. |

Example: `/aceeco restore 20260824T093000-aaaa1111 confirm`

## Common command errors

| What happened | What to check |
|---|---|
| Permission denied | The sender does not have the permission shown for the root or subcommand. |
| Wrong sender | Run player-only commands in game. Run `/aceeco reload`, `/aceeco rollback`, and `/aceeco restore` from the console; `restore` also requires no players to be online. |
| Missing or extra arguments | Use the exact subcommand and usage line. For example, `/baltop` needs `top`; it does not take a page number. |
| Unknown player | Check the player name and try again. |
| Unknown currency | Use a configured currency ID. Omitting it uses the configured default. |
| Invalid amount | Use a number that is positive, within the currency scale, and within the command limit. |
| Economy operation rejected | Read the returned error and correct the account or economy condition, such as insufficient funds or a debt limit. |
| Rollback rejected | Check the typed error: a valid transaction UUID is required, an already reverted transaction is a no-op, and a marker failure needs manual reconciliation before retrying. |
| Restore confirmation rejected | Use the exact lowercase word `confirm`: `/aceeco restore <backup-id> confirm`. `CONFIRM`, `Confirm`, and other spellings are rejected. |
| Restore rejected because players are online | Run restore from the console after all players have left. |
| Restore safety or snapshot check failed | Keep the live data unchanged, inspect the typed error, and do not delete the current store to force a restore. |

---

# 指令與權限

你可能只是想查餘額、轉一筆錢，或打開銀行介面。這頁把玩家指令和管理員指令分開，方便
直接找到要用的指令。以下是目前 v2 語法：每個 root command 後面都要接指定的
subcommand。

## 快速查表

| Root command | Subcommand | 用法 | 執行者 | 權限 | Alias |
|---|---|---|---|---|---|
| `/money` | `balance` | `[player] [currency]` | 玩家或主控台；主控台必須提供 `player` | `aceeconomy.command.money` | `/balance` |
| `/pay` | `send` | `<player> <amount> [currency]` | 僅限玩家 | `aceeconomy.command.pay` | 無 |
| `/withdraw` | `cash` | `<amount> [currency]` | 僅限玩家 | `aceeconomy.command.withdraw` | 無 |
| `/baltop` | `top` | `[currency]` | 玩家或主控台 | `aceeconomy.command.baltop` | 無 |
| `/bank` | `open` | 不接受參數 | 僅限玩家 | `aceeconomy.command.bank` | 無 |
| `/aceeco` | `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore` | 見管理員參考 | `reload`、`rollback`、`restore` 僅限主控台；`backup` 與其他子指令可由玩家或主控台執行 | `aceeconomy.admin` 加上子指令權限 | 無 |

`<required>` 代表必填值；`[optional]` 代表可省略的值。省略 `currency` 時，使用設定的
預設貨幣。貨幣 ID 不分大小寫。

金額必須是有效數字、大於零、符合該貨幣的小數位數，而且不得超過
`1,000,000,000,000,000`。

目前 v2 command specification 列出的 root alias 只有 `/money` 的 `/balance`。它仍然要接
`balance` 子指令：`/balance balance [player] [currency]`。沒有獨立的 `/backup` 或 `/restore`
根指令，請使用 `/aceeco` 管理子指令。

## 玩家查用法

### 查詢餘額：`/money balance`

想看自己的餘額時直接省略玩家名稱。查其他帳戶時加上玩家名稱；主控台沒有自己的玩家帳戶，
所以必須使用帶有 `player` 的完整形式。

| 項目 | 說明 |
|---|---|
| 用法 | `/money balance [player] [currency]` |
| 執行者 | 玩家或主控台；玩家可省略 `player`，主控台必須提供 `player` |
| 權限 | `aceeconomy.command.money` |
| Alias | root command 可用 `/balance`，但仍要保留 `balance` 子指令 |

範例：

```text
/money balance
/money balance Alex <currency>
/balance balance Alex <currency>
```

### 轉帳：`/pay send`

把錢轉給其他玩家。這個指令必須在遊戲內由玩家執行。

| 項目 | 說明 |
|---|---|
| 用法 | `/pay send <player> <amount> [currency]` |
| 執行者 | 僅限玩家 |
| 權限 | `aceeconomy.command.pay` |
| Alias | v2 command specification 沒有列出 alias |

範例：`/pay send Alex 25 <currency>`

### 提領銀行票據：`/withdraw cash`

把指定金額提領成銀行票據。這個指令必須由玩家執行。

| 項目 | 說明 |
|---|---|
| 用法 | `/withdraw cash <amount> [currency]` |
| 執行者 | 僅限玩家 |
| 權限 | `aceeconomy.command.withdraw` |
| Alias | v2 command specification 沒有列出 alias |

範例：`/withdraw cash 100 <currency>`

### 查看排行榜：`/baltop top`

查看餘額最高的玩家。玩家和主控台都能執行；不想使用預設貨幣時，再指定貨幣即可。

| 項目 | 說明 |
|---|---|
| 用法 | `/baltop top [currency]` |
| 執行者 | 玩家或主控台 |
| 權限 | `aceeconomy.command.baltop` |
| Alias | v2 command specification 沒有列出 alias |

範例：

```text
/baltop top
/baltop top <currency>
```

### 開啟銀行：`/bank open`

開啟 AceEconomy 銀行介面。這個指令不接受參數，必須由玩家執行。

| 項目 | 說明 |
|---|---|
| 用法 | `/bank open` |
| 執行者 | 僅限玩家 |
| 權限 | `aceeconomy.command.bank` |
| Alias | v2 command specification 沒有列出 alias |

目前 GUI 的 action contract 如下：

- `DEPOSIT`：slot `4`（上方中間格）。
- `WITHDRAW`：既有的 slot `11` 與 `13`（`100` 與 `500` 提款按鈕）。
- `CLOSE`：slot `15`。

存入有效 v2 banknote 時，必須先完成 durable replay protection 與 credit，之後才會移除
banknote 或減少 stack 數量。invalid、replay 或 credit failure 時，物品會保留在玩家物品欄。
右鍵兌回尚未實作。

## 管理員查權限

管理員 root command 是 `/aceeco`，v2 command specification 沒有列出 alias。root 權限為
`aceeconomy.admin`，每個操作也有自己的權限節點。變更餘額的子指令沿用玩家名稱、金額與
可選貨幣的格式。`history` 只讀取已記錄的交易，不會變更餘額。`reload`、`rollback` 與
`restore` 只能由主控台執行；`rollback` 要帶交易 ID，`restore` 則要帶 backup ID 與精確的
`confirm`。

| 子指令 | 用法 | 執行者 | 子指令權限 | Alias |
|---|---|---|---|---|
| `give` | `/aceeco give <player> <amount> [currency]` | 玩家或主控台 | `aceeconomy.admin.give` | 無 |
| `take` | `/aceeco take <player> <amount> [currency]` | 玩家或主控台 | `aceeconomy.admin.take` | 無 |
| `set` | `/aceeco set <player> <amount> [currency]` | 玩家或主控台 | `aceeconomy.admin.set` | 無 |
| `history` | `/aceeco history [player] [currency] [page]` | 玩家或主控台 | `aceeconomy.admin.history` | 無 |
| `reload` | `/aceeco reload` | 僅限主控台 | `aceeconomy.admin.reload` | 無 |
| `rollback` | `/aceeco rollback <transaction-id>` | 僅限主控台 | `aceeconomy.admin.rollback` | 無 |
| `backup` | `/aceeco backup [label]` | 玩家或主控台 | `aceeconomy.admin.backup` | 無 |
| `restore` | `/aceeco restore <backup-id> confirm` | 僅限主控台 | `aceeconomy.admin.restore` | 無 |

plugin 宣告的預設值是：玩家指令權限為 `true`，管理員權限為 `op`。另外，plugin 也宣告
`aceeconomy.bypass.debt`，預設值為 `op`，用於債務上限 bypass 權限。

### 增加餘額：`/aceeco give`

需要替玩家增加餘額時使用 `give`。

範例：`/aceeco give Alex 500 <currency>`

### 扣除餘額：`/aceeco take`

需要從玩家餘額扣除金額時使用 `take`。

範例：`/aceeco take Alex 125 <currency>`

### 設定餘額：`/aceeco set`

需要把玩家餘額直接設成指定金額時使用 `set`。

範例：`/aceeco set Alex 1000 <currency>`

### 查詢交易歷史：`/aceeco history`

管理員需要檢視已記錄的餘額變更時使用 `history`。這個指令是唯讀的：不會改變餘額或稽核紀錄。
省略 `player` 時列出所有帳戶的交易；省略 `currency` 時使用設定的預設貨幣；`page` 從 0 開始，
每頁顯示 10 筆。空頁、找不到玩家、頁碼小於零都會有明確回覆。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco history [player] [currency] [page]` |
| 執行者 | 玩家或主控台 |
| 權限 | `aceeconomy.admin.history` |
| 排序 | 由新到舊，並有穩定的同秒排序依據，重複查詢的順序一致 |

範例：

```text
/aceeco history
/aceeco history Alex
/aceeco history Alex <currency>
/aceeco history Alex <currency> 2
```

### 重載經濟設定：`/aceeco reload`

修改經濟設定後，從主控台執行這個指令。它不接受參數，玩家不能執行。

範例：`/aceeco reload`

### 回滾交易：`/aceeco rollback`

管理員需要依 ID 復原一筆已記錄的交易時使用 `rollback`。這是具破壞性的管理操作，因此僅限
主控台執行，而且需要同時擁有 `aceeconomy.admin` 與 `aceeconomy.admin.rollback`。交易 ID 是
該筆交易的 UUID；格式不正確時會在查詢前就被拒絕。

各種結果都有明確回覆：

| 結果 | 回覆 |
|---|---|
| 成功 | 指出被回滾的交易，並列出 reversal 稽核紀錄 ID。 |
| 已回滾過 | 說明該交易已回滾、未做任何變更；重送同一個 ID 是安全的 no-op，不會重複餘額效果或稽核紀錄。 |
| 找不到交易 | Typed error：沒有這個 ID 的交易。 |
| ID 格式錯誤 | Typed error：參數不是有效 UUID，不會進行任何查詢。 |
| 轉帳對應腿缺失 | Typed error：轉帳的另一腿找不到，無法安全回滾。 |
| 執行失敗 | Typed error：回滾未生效，該交易仍可重試。 |
| Marker 寫入失敗 | Typed error：回滾效果可能已發生但 reverted marker 缺失；先檢查儲存並人工核對，再考慮重試。 |

範例：`/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b`

rollback 指令已接入 production command surface，也有自動化 contract tests 覆蓋，但**尚未在
live server 驗證**：Folia/Bukkit bridge 實機執行、live MySQL 儲存與真實資料的故障注入演練
都還是未完成的 release gate。在該驗證完成前，上表內容應視為設計契約，而非實測結果。

### 建立邏輯備份：`/aceeco backup`

這個指令會在伺服器運作中建立 v2 logical JSON snapshot。`label` 可省略，若提供則只能使用安全的
檔名字元。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco backup [label]` |
| 執行者 | 玩家或主控台 |
| 權限 | `aceconomy.admin.backup`（另需 root `aceeconomy.admin`） |
| 儲存位置 | 插件控制的 `<plugin data folder>/backups` |
| 輸出 | 原子寫入且不覆寫既有檔案，並回報 backup ID |

snapshot 是 v2 邏輯 JSON 模型，包含帳戶、餘額、交易、reverted marker 與已消耗 nonce，不包含資料庫密碼
或 webhook URL。沒有獨立的 `/backup` 根指令。

### 還原邏輯備份：`/aceeco restore`

還原會替換正式經濟資料，因此只能從主控台執行，且需要 root 與子指令權限。確認字串區分大小寫，只接受
小寫 `confirm`。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco restore <backup-id> confirm` |
| 執行者 | 僅限主控台；有任何玩家在線時會拒絕 |
| 權限 | `aceconomy.admin.restore`（另需 root `aceeconomy.admin`） |
| Preflight | 動到正式資料前檢查 JSON 結構、schema 版本、紀錄與設定貨幣相容性 |
| 安全備份 | 先備份目前狀態；安全備份失敗時中止還原 |
| 成功後 | 清除排行榜快取，但不會熱刷新 session 或 GUI。讓玩家回來前必須重啟伺服器。 |

範例：`/aceeco restore 20260824T093000-aaaa1111 confirm`

## 常見指令錯誤

| 狀況 | 檢查方式 |
|---|---|
| 沒有權限 | 確認執行者擁有 root 或子指令列出的權限。 |
| 執行者類型不對 | 玩家限定指令請在遊戲內執行；`/aceeco reload`、`/aceeco rollback` 與 `/aceeco restore` 請從主控台執行；`restore` 另要求沒有玩家在線。 |
| 參數少了或多了 | 對照正確的 subcommand 和用法。例如 `/baltop` 必須接 `top`，不接受頁碼。 |
| 找不到玩家 | 檢查玩家名稱後再試一次。 |
| 找不到貨幣 | 使用已設定的貨幣 ID；省略貨幣時會使用設定的預設值。 |
| 金額格式錯誤 | 請輸入大於零、符合貨幣小數位數且未超過指令上限的數字。 |
| 經濟操作被拒絕 | 依回傳的錯誤修正帳戶或經濟條件，例如餘額不足或超過債務上限。 |
| 回滾被拒絕 | 依 typed error 判別：需要有效的交易 UUID、已回滾的交易是 no-op、marker 失敗需先人工核對再重試。 |
| 還原確認被拒絕 | 必須使用精確的小寫 `confirm`：`/aceeco restore <backup-id> confirm`。`CONFIRM`、`Confirm` 與其他拼法都會被拒絕。 |
| 還原時有玩家在線 | 先讓所有玩家離線，再從主控台重試。 |
| 還原的安全備份或 snapshot 檢查失敗 | 保留正式資料，依 typed error 檢查原因；不要刪除目前儲存內容來強行還原。 |
