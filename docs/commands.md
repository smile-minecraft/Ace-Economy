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
| `/aceeco` | `give`, `take`, `set`, `reload` | See the administrator reference | `reload`: console only; other subcommands: player or console | `aceeconomy.admin` plus the subcommand node | None |

`<required>` values must be supplied. `[optional]` values may be omitted. If `currency` is
omitted, the configured default currency is used. Currency IDs are matched without regard to
letter case.

Amounts must be valid numbers, greater than zero, within the currency's decimal scale, and no
greater than `1,000,000,000,000,000`.

The only v2 root alias listed by the command specification is `/balance` for `/money`. It uses the
same `balance` subcommand: `/balance balance [player] [currency]`.

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

## For administrators

The administrator root is `/aceeco`, with no alias listed by the v2 command specification. The
root permission is `aceeconomy.admin`; each operation also declares its own permission node. The
mutation commands accept the same player, amount, and optional currency pattern as the player
commands. `reload` is the exception: it takes no arguments and is console-only.

| Subcommand | Usage | Sender | Subcommand permission | Alias |
|---|---|---|---|---|
| `give` | `/aceeco give <player> <amount> [currency]` | Player or console | `aceeconomy.admin.give` | None |
| `take` | `/aceeco take <player> <amount> [currency]` | Player or console | `aceeconomy.admin.take` | None |
| `set` | `/aceeco set <player> <amount> [currency]` | Player or console | `aceeconomy.admin.set` | None |
| `reload` | `/aceeco reload` | Console only | `aceeconomy.admin.reload` | None |

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

### Reload the economy configuration: `/aceeco reload`

Run this from the console after changing economy configuration. It takes no arguments and cannot
be run by a player.

Example: `/aceeco reload`

## Common command errors

| What happened | What to check |
|---|---|
| Permission denied | The sender does not have the permission shown for the root or subcommand. |
| Wrong sender | Run player-only commands in game, or run `/aceeco reload` from the console. |
| Missing or extra arguments | Use the exact subcommand and usage line. For example, `/baltop` needs `top`; it does not take a page number. |
| Unknown player | Check the player name and try again. |
| Unknown currency | Use a configured currency ID. Omitting it uses the configured default. |
| Invalid amount | Use a number that is positive, within the currency scale, and within the command limit. |
| Economy operation rejected | Read the returned error and correct the account or economy condition, such as insufficient funds or a debt limit. |

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
| `/aceeco` | `give`、`take`、`set`、`reload` | 見管理員參考 | `reload` 僅限主控台；其他子指令可由玩家或主控台執行 | `aceeconomy.admin` 加上子指令權限 | 無 |

`<required>` 代表必填值；`[optional]` 代表可省略的值。省略 `currency` 時，使用設定的
預設貨幣。貨幣 ID 不分大小寫。

金額必須是有效數字、大於零、符合該貨幣的小數位數，而且不得超過
`1,000,000,000,000,000`。

目前 v2 command specification 列出的 root alias 只有 `/money` 的 `/balance`。它仍然要接
`balance` 子指令：`/balance balance [player] [currency]`。

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

## 管理員查權限

管理員 root command 是 `/aceeco`，v2 command specification 沒有列出 alias。root 權限為
`aceeconomy.admin`，每個操作也有自己的權限節點。變更餘額的子指令沿用玩家名稱、金額與
可選貨幣的格式。`reload` 不同：它不接受參數，而且只能由主控台執行。

| 子指令 | 用法 | 執行者 | 子指令權限 | Alias |
|---|---|---|---|---|
| `give` | `/aceeco give <player> <amount> [currency]` | 玩家或主控台 | `aceeconomy.admin.give` | 無 |
| `take` | `/aceeco take <player> <amount> [currency]` | 玩家或主控台 | `aceeconomy.admin.take` | 無 |
| `set` | `/aceeco set <player> <amount> [currency]` | 玩家或主控台 | `aceeconomy.admin.set` | 無 |
| `reload` | `/aceeco reload` | 僅限主控台 | `aceeconomy.admin.reload` | 無 |

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

### 重載經濟設定：`/aceeco reload`

修改經濟設定後，從主控台執行這個指令。它不接受參數，玩家不能執行。

範例：`/aceeco reload`

## 常見指令錯誤

| 狀況 | 檢查方式 |
|---|---|
| 沒有權限 | 確認執行者擁有 root 或子指令列出的權限。 |
| 執行者類型不對 | 玩家限定指令請在遊戲內執行；`/aceeco reload` 請從主控台執行。 |
| 參數少了或多了 | 對照正確的 subcommand 和用法。例如 `/baltop` 必須接 `top`，不接受頁碼。 |
| 找不到玩家 | 檢查玩家名稱後再試一次。 |
| 找不到貨幣 | 使用已設定的貨幣 ID；省略貨幣時會使用設定的預設值。 |
| 金額格式錯誤 | 請輸入大於零、符合貨幣小數位數且未超過指令上限的數字。 |
| 經濟操作被拒絕 | 依回傳的錯誤修正帳戶或經濟條件，例如餘額不足或超過債務上限。 |
