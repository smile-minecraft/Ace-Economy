# Player guide

AceEconomy is easiest to use when you start with the task in front of you. This guide keeps the commands short, explains when to add a currency, and points out the few input details that commonly cause a command to fail.

AceEconomy 的指令不多，先從你想完成的事情開始就好。本指南用情境整理操作方式，說明什麼時候需要指定貨幣，也把最常見的輸入問題放在最後，方便你直接回來查。

## Before you start

The v2 commands use an action word after the main command. For example, use `/money balance`, not an older shortened form. Text in angle brackets is a placeholder: replace `<player>`, `<amount>`, or `<currency>` with your value, and do not type the angle brackets.

v2 指令會在主指令後接一個動作。例如查詢餘額要用 `/money balance`，不要套用舊版的縮寫寫法。尖括號中的文字是 placeholder，請把 `<player>`、`<amount>` 或 `<currency>` 換成實際內容，不要連尖括號一起輸入。

If you leave out `[currency]`, AceEconomy uses the server's configured default currency. Currency IDs come from the server configuration; the display name players see can be different from the ID.

省略 `[currency]` 時，AceEconomy 會使用伺服器設定的預設貨幣。貨幣 ID 來自伺服器設定，玩家看到的顯示名稱可能和 ID 不同；不確定時，請向管理員確認要輸入哪個 ID。

## I want to check a balance

### My own balance

Use:

```text
/money balance
```

You will see your balance in the default currency. To check a named currency, include your player name followed by its ID:

```text
/money balance <player> <currency>
```

This command requires `aceeconomy.command.money`.

### Another player's balance

Use the player's name and, when needed, the currency ID:

```text
/money balance <player> [currency]
```

For example:

```text
/money balance Alex token
```

The same `aceeconomy.command.money` permission covers this command.

### 我想查詢餘額

#### 查自己的餘額

輸入：

```text
/money balance
```

畫面會顯示你在預設貨幣中的餘額。要查指定貨幣，請在自己的玩家名稱後面加上貨幣 ID：

```text
/money balance <player> <currency>
```

這個指令需要 `aceeconomy.command.money`。

#### 查其他玩家的餘額

輸入玩家名稱；需要時再加上貨幣 ID：

```text
/money balance <player> [currency]
```

例如：

```text
/money balance Alex token
```

這個查詢同樣使用 `aceeconomy.command.money` 權限。

## I want to pay another player

Use:

```text
/pay send <player> <amount> [currency]
```

For example:

```text
/pay send Alex 250
```

This pays `Alex` 250 units of the default currency. To pay in another currency, name it explicitly:

```text
/pay send Alex 10 token
```

`/pay` is player-only and requires `aceeconomy.command.pay`. The amount must be positive and valid for the selected currency. A successful transfer reports the amount and recipient; the receiving player gets a matching payment notification.

### 我想付款給其他玩家

輸入：

```text
/pay send <player> <amount> [currency]
```

例如：

```text
/pay send Alex 250
```

這會使用預設貨幣轉帳 250 給 `Alex`。如果要使用其他貨幣，請明確填入貨幣 ID：

```text
/pay send Alex 10 token
```

`/pay` 只能由玩家執行，並需要 `aceeconomy.command.pay`。金額必須大於 0，且符合該貨幣允許的小數位數。轉帳成功後，你會看到金額與收款人；收款玩家也會收到對應的入帳通知。

## I want to carry money as an item

### Withdraw a banknote

Use:

```text
/withdraw cash <amount> [currency]
```

For example:

```text
/withdraw cash 100
```

AceEconomy removes the amount from your balance and gives you a physical banknote. Keep an empty inventory slot available before you withdraw. This command is player-only and requires `aceeconomy.command.withdraw`.

### Redeem a banknote

Right-click the banknote to redeem it into your account. A banknote can be redeemed only once. If the item is damaged, invalid, or already used, keep it and ask a server administrator to help rather than trying to duplicate the transaction.

### 我想把錢變成可以攜帶的物品

#### 提領銀行支票

輸入：

```text
/withdraw cash <amount> [currency]
```

例如：

```text
/withdraw cash 100
```

AceEconomy 會從你的餘額扣除指定金額，並給你一張實體銀行支票。提領前請先留一格空背包欄位。這個指令只能由玩家執行，並需要 `aceeconomy.command.withdraw`。

#### 兌回銀行支票

手持銀行支票按右鍵，就會把金額存回你的帳戶。每張支票只能兌回一次；如果物品損壞、無效或已經使用過，請保留物品並找管理員處理，不要嘗試複製交易。

## I want to see the richest players

Use:

```text
/baltop top [currency]
```

Without a currency, the leaderboard uses the configured default currency. For a specific currency:

```text
/baltop top token
```

The command requires `aceeconomy.command.baltop`. The leaderboard displays player names, ranks, and balances for the selected currency.

### 我想查看富豪排行榜

輸入：

```text
/baltop top [currency]
```

不填貨幣時，排行榜會使用伺服器設定的預設貨幣。要查看特定貨幣，可以這樣輸入：

```text
/baltop top token
```

這個指令需要 `aceeconomy.command.baltop`，並會列出所選貨幣的排名、玩家名稱與餘額。

## I want to use the bank dashboard

Open the dashboard with:

```text
/bank open
```

The bank is a player-only menu. It gives you a single place to view your account area and use the available banknote withdrawal actions. Close the menu when you are done. The command requires `aceeconomy.command.bank`.

### 我想使用銀行面板

輸入：

```text
/bank open
```

銀行是玩家專用的介面，可以在同一個地方查看帳戶區域，並使用面板提供的銀行支票提領功能。操作完成後即可關閉面板。這個指令需要 `aceeconomy.command.bank`。

## I want to use more than one currency

Every command that shows `[currency]` accepts a configured currency ID. The default configuration includes `dollar` and `token`; your server may define more.

Use the default currency when the server's normal economy is enough. Add a currency ID when the activity uses a separate balance:

```text
/money balance <player> token
/pay send Alex 10 token
/withdraw cash 5 token
/baltop top token
```

Currency IDs are normalized for lookup, so differences in capitalization or surrounding spaces do not create a second currency. The amount still has to match that currency's configured scale. A currency with scale `0` accepts whole numbers; a currency with scale `2` accepts up to two decimal places.

### 我想使用多種貨幣

所有標示 `[currency]` 的指令都接受伺服器設定中的貨幣 ID。預設設定包含 `dollar` 與 `token`，伺服器也可以另外定義更多貨幣。

如果使用伺服器的一般經濟，就省略貨幣 ID；如果活動使用另一個獨立餘額，就把 ID 加上去：

```text
/money balance <player> token
/pay send Alex 10 token
/withdraw cash 5 token
/baltop top token
```

查找貨幣 ID 時，大小寫與前後空白不會造成另一個貨幣。不過金額仍需符合該貨幣設定的小數位數。`scale: 0` 的貨幣只能輸入整數；`scale: 2` 的貨幣最多可輸入兩位小數。

## I want to understand a balance change from an admin

Server administrators can give money, take money, or set a balance. When one of these actions affects you, AceEconomy can show a player-facing notification with the amount and currency. You can confirm the resulting value with:

```text
/money balance
```

If you expected a reward or correction but do not recognize the new balance, ask the server administrator for the transaction context. Admin command syntax and permissions are listed in [Commands & Permissions](commands.md).

### 我想了解管理員造成的餘額變化

伺服器管理員可以發放、扣除，或直接設定玩家餘額。這些操作影響到你時，AceEconomy 可以顯示包含金額與貨幣的玩家通知。你可以用下面的指令確認結果：

```text
/money balance
```

如果你預期會收到獎勵或修正，但看不懂餘額變化，請向伺服器管理員確認交易背景。管理指令語法與權限請參考[指令與權限](commands.md)。

## Common input problems

### The command says the amount is invalid

Check that the amount is a real number, greater than zero, and does not contain more decimal places than the selected currency allows. Extremely large amounts are rejected as well. Try a small value first, such as `10` or `10.50` when the currency uses scale `2`.

### 指令提示金額無效

請確認輸入的是有效數字、大於 0，而且小數位數沒有超過該貨幣的設定。過大的金額也會被拒絕。如果貨幣使用 `scale: 2`，可以先用 `10` 或 `10.50` 這類小額測試。

### The currency is not found

Use the currency ID configured by the server, not its display name or symbol. Currency suggestions appear while you type when command completion is available. If the ID is still rejected, ask an administrator which currencies are enabled.

### 找不到貨幣

請輸入伺服器設定的貨幣 ID，不要輸入顯示名稱或符號。支援補字的情況下，輸入指令時會出現貨幣建議；如果仍然被拒絕，請向管理員確認目前啟用的貨幣。

### The player cannot be found

Check the spelling and use the player's name exactly as the server recognizes it. For `/pay send`, also make sure you are running the command as a player and are not trying to pay yourself.

### 找不到玩家

請檢查拼字，並使用伺服器能辨識的玩家名稱。執行 `/pay send` 時，也請確認你是以玩家身分輸入，而且不是付款給自己。

### I do not have permission

Ask the server administrator to check the permission for the command:

| Command | Permission |
| --- | --- |
| `/money` | `aceeconomy.command.money` |
| `/pay` | `aceeconomy.command.pay` |
| `/withdraw` | `aceeconomy.command.withdraw` |
| `/baltop` | `aceeconomy.command.baltop` |
| `/bank` | `aceeconomy.command.bank` |

### 我沒有權限

請請伺服器管理員確認該指令的權限：

| 指令 | 權限 |
| --- | --- |
| `/money` | `aceeconomy.command.money` |
| `/pay` | `aceeconomy.command.pay` |
| `/withdraw` | `aceeconomy.command.withdraw` |
| `/baltop` | `aceeconomy.command.baltop` |
| `/bank` | `aceeconomy.command.bank` |

### My banknote did not appear

Check your inventory first. A full inventory prevents a banknote withdrawal. If the command reports an economy error, check the amount and your available balance, then ask an administrator if the problem continues.

### 銀行支票沒有出現在背包

先檢查背包是否已滿；沒有空位時無法取得銀行支票。如果指令顯示經濟系統錯誤，請先確認金額與可用餘額，問題持續時再請管理員協助。

## More help

For every command's full syntax, sender rules, aliases, and admin permissions, see [Commands & Permissions](commands.md). Server setup belongs in the [admin installation runbook](admin-install-runbook.md), while configuration details are in the [configuration guide](config.md). If a player-facing problem remains, see [Troubleshooting](troubleshooting.md) or ask your server administrator.

完整指令語法、執行者限制、別名與管理權限請看[指令與權限](commands.md)。伺服器安裝請參考[管理員安裝手冊](admin-install-runbook.md)，設定細節請看[設定指南](config.md)。玩家操作仍有問題時，可以查看[故障排除](troubleshooting.md)，或直接詢問伺服器管理員。
