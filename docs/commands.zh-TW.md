# 指令與權限

[English](commands.md) · [简体中文](commands.zh-CN.md) · 繁體中文

AceEconomy v2 的指令一定要接子指令，例如 `/money balance`，不能只打 `/money`。這頁把玩家指令與管理員指令分開，列出語法、執行者、權限與別名。

## 目錄

- [快速查表](#快速查表)
- [玩家用法](#玩家用法)
- [管理員權限](#管理員權限)
- [常見指令錯誤](#常見指令錯誤)

## 快速查表

| 主指令 | 子指令 | 用法 | 執行者 | 權限 | 別名 |
|---|---|---|---|---|---|
| `/money` | `balance` | `[player] [currency]` | 玩家或主控台；主控台必須提供 `player` | `aceeconomy.command.money` | `/balance` |
| `/money` | `history` | `[currency] [page]` | 僅限玩家 | `aceeconomy.command.history` | `/balance` |
| `/pay` | `send` | `<player> <amount> [currency]` | 僅限玩家 | `aceeconomy.command.pay` | 無 |
| `/withdraw` | `cash` | `<amount> [currency]` | 僅限玩家 | `aceeconomy.command.withdraw` | 無 |
| `/baltop` | `top` | `[currency]` | 玩家或主控台 | `aceeconomy.command.baltop` | 無 |
| `/bank` | `open` | 不接受參數 | 僅限玩家 | `aceeconomy.command.bank` | 無 |
| `/aceeco` | `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore`、`import` | 見管理員參考 | `reload`、`rollback`、`restore`、`import` 僅限主控台；`backup` 與其他子指令可由玩家或主控台執行 | `aceeconomy.admin` 加上子指令權限 | 無 |

`<必填>` 代表必須提供的值；`[可選]` 代表可省略的值。省略 `currency` 時，使用設定的預設貨幣。貨幣 ID 不區分大小寫。

金額必須為有效正數、符合該貨幣定義之小數位數上限，且不得超過 `1,000,000,000,000,000`。格式不符時將拒絕執行，詳見[常見指令錯誤](#常見指令錯誤)。

目前 v2 指令規格列出的主指令別名只有 `/money` 的 `/balance`。它仍然要接 `balance` 子指令：`/balance balance [player] [currency]`。查明細也一樣：`/balance history [currency] [page]`。沒有獨立的 `/backup` 或 `/restore` 主指令，請使用 `/aceeco` 的管理子指令。

## 玩家用法

### 查詢餘額：`/money balance`

查詢自身或指定玩家的帳戶餘額。玩家執行時若省略玩家名稱則查詢本人；主控台無自身帳戶，必須明確指定目標玩家。

| 項目 | 說明 |
|---|---|
| 用法 | `/money balance [player] [currency]` |
| 執行者 | 玩家或主控台；玩家可省略 `player`，主控台必須提供 `player` |
| 權限 | `aceeconomy.command.money` |
| 別名 | 主指令可用 `/balance`，但仍要保留 `balance` 子指令 |

範例：

```text
/money balance
/money balance Alex <currency>
/balance balance Alex <currency>
```

### 查詢交易明細：`/money history`

分頁檢視執行者本人的歷史交易紀錄。僅限玩家執行且僅顯示自身帳戶；不接受玩家參數，傳入額外參數將直接拒絕並回傳錯誤。

| 項目 | 說明 |
|---|---|
| 用法 | `/money history [currency] [page]` |
| 執行者 | 僅限玩家；主控台沒有自己的玩家帳戶 |
| 權限 | `aceeconomy.command.history`（另需主權限 `aceeconomy.command.money`） |
| 別名 | 主指令可用 `/balance`，但仍要保留 `history` 子指令 |

省略 `currency` 時，使用設定的預設貨幣。`page` 從 0 開始，每頁顯示 10 筆，由新到舊——分頁、排序與欄位都和 `/aceeco history` 一致。空頁會有明確回覆。未知的貨幣與無效的頁碼會被拒絕並回傳錯誤。

範例：

```text
/money history
/money history <currency>
/money history <currency> 2
```

### 轉帳：`/pay send`

將指定金額轉帳至目標玩家帳戶。此指令僅限遊戲內線上玩家執行。

| 項目 | 說明 |
|---|---|
| 用法 | `/pay send <player> <amount> [currency]` |
| 執行者 | 僅限玩家 |
| 權限 | `aceeconomy.command.pay` |
| 別名 | v2 指令規格未提供別名 |

範例：`/pay send Alex 25 <currency>`

### 提領銀行支票：`/withdraw cash`

從帳戶提領指定金額並轉化為實體支票物品。此指令僅限玩家執行；執行前請確認物品欄具備可用空格。

| 項目 | 說明 |
|---|---|
| 用法 | `/withdraw cash <amount> [currency]` |
| 執行者 | 僅限玩家 |
| 權限 | `aceeconomy.command.withdraw` |
| 別名 | v2 指令規格未提供別名 |

範例：`/withdraw cash 100 <currency>`

### 查看排行榜：`/baltop top`

查詢伺服器財富排行榜。支援玩家與主控台執行；若未指定貨幣則查詢預設貨幣。

| 項目 | 說明 |
|---|---|
| 用法 | `/baltop top [currency]` |
| 執行者 | 玩家或主控台 |
| 權限 | `aceeconomy.command.baltop` |
| 別名 | v2 指令規格未提供別名 |

範例：

```text
/baltop top
/baltop top <currency>
```

### 開啟銀行：`/bank open`

開啟銀行互動介面。此指令不接受額外參數，僅限玩家執行。

| 項目 | 說明 |
|---|---|
| 用法 | `/bank open` |
| 執行者 | 僅限玩家 |
| 權限 | `aceeconomy.command.bank` |
| 別名 | v2 指令規格未提供別名 |

Java 玩家開啟的是箱子選單，按鈕與格子對應如下：

- 存入（DEPOSIT）：第 4 格（上方中間）。
- 提領（WITHDRAW）：第 11 格與第 13 格（分別是 `100` 與 `500` 提領按鈕）。
- 關閉（CLOSE）：第 15 格。

基岩版玩家（Geyser + Floodgate）開啟的是原生表單：首頁顯示餘額與存入、提領、關閉三個按鈕；提領要輸入金額與幣別，再經過確認步驟才會執行。關閉表單、輸入無效的值、離線，或在回應前遇到重新載入／斷線，都不會執行任何交易。兩種介面走同一條存入與提領路徑，一次性序號、防重播與區域執行緒保證兩邊都成立。Floodgate 缺席時所有玩家都維持箱子選單。

想把支票存進帳戶，先認清這條安全線：存入一張有效的 v2 銀行支票時，伺服器一定先完成防重播紀錄與入帳，之後才會移除支票或減少堆疊數量。無效、用過或入帳失敗的支票會留在你的物品欄裡。手持支票按右鍵可直接兌回，走與銀行面板存入按鈕相同的入帳路徑。如果入帳已經完成卻拿不掉支票，這筆錢仍然算數，伺服器稽核紀錄會記下支票編號、玩家與實際入帳金額，全程可追查。多張支票是用複本扣掉一張再一次寫回，寫入失敗時整疊會留在手上：收好並聯絡管理員。單張支票的清除是一次寫入，失敗後格子狀態無法從外部確認，因此只保證稽核紀錄可追查、不保證票還在：把票根留好，不要重試（錢已經算過，重送會被擋下），直接聯絡管理員。管理員會拿支票編號去對稽核紀錄，先收回或作廢重複的支票，再用例如 `/aceeco give` 把錢補上。

## 管理員權限

管理員的主指令是 `/aceeco`，v2 指令規格未提供別名。想動餘額就用 `give`、`take`、`set`；想查紀錄就用 `history`，它只讀取已記錄的交易，不會變更餘額。`reload`、`rollback`、`restore` 與 `import` 只能由主控台執行；`rollback` 要帶交易 ID，`restore` 要帶備份 ID 與精確的 `confirm`，`import` 要帶來源與路徑（寫入時需精確的 `apply confirm`）。會動到餘額的六個操作（`give`、`take`、`set`、`rollback`、`restore`、`import`）都可在尾段加上可選的 `reason` 留作稽核；權限維持不變。`freeze`、`unfreeze`、`readonly` 是安全控制：玩家或主控台都能執行，各自有獨立權限，每次成功都會在獨立的控制稽核裡記下操作者與原因，絕不會偽造一筆金錢交易。搞混執行者或參數就看[常見指令錯誤](#常見指令錯誤)。

| 子指令 | 用法 | 執行者 | 子指令權限 | 別名 |
|---|---|---|---|---|
| `give` | `/aceeco give <player> <amount> [currency] [reason]` | 玩家或主控台 | `aceeconomy.admin.give` | 無 |
| `take` | `/aceeco take <player> <amount> [currency] [reason]` | 玩家或主控台 | `aceeconomy.admin.take` | 無 |
| `set` | `/aceeco set <player> <amount> [currency] [reason]` | 玩家或主控台 | `aceeconomy.admin.set` | 無 |
| `history` | `/aceeco history [player] [currency] [page]` | 玩家或主控台 | `aceeconomy.admin.history` | 無 |
| `reload` | `/aceeco reload` | 僅限主控台 | `aceeconomy.admin.reload` | 無 |
| `rollback` | `/aceeco rollback <transaction-id> [reason]` | 僅限主控台 | `aceeconomy.admin.rollback` | 無 |
| `backup` | `/aceeco backup [label]` | 玩家或主控台 | `aceeconomy.admin.backup` | 無 |
| `restore` | `/aceeco restore <backup-id> confirm [reason]` | 僅限主控台 | `aceeconomy.admin.restore` | 無 |
| `import` | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm] [reason]` | 僅限主控台 | `aceeconomy.admin.import` | 無 |
| `freeze` | `/aceeco freeze <player> [reason]` | 玩家或主控台 | `aceeconomy.admin.freeze` | 無 |
| `unfreeze` | `/aceeco unfreeze <player> [reason]` | 玩家或主控台 | `aceeconomy.admin.unfreeze` | 無 |
| `readonly` | `/aceeco readonly <on\|off> [reason]` | 玩家或主控台 | `aceeconomy.admin.readonly` | 無 |

每個操作除了主權限 `aceeconomy.admin`，還要各自的子指令權限。插件宣告的預設值是：玩家指令權限為 `true`，管理員權限為 `op`，上面三個安全控制節點也是 `op`。另外，插件也宣告 `aceeconomy.bypass.debt`，預設值為 `op`，用於跳過債務上限的權限。三個安全控制權限互相獨立：只有 `freeze` 不能解凍，也不能切換唯讀。

### 管理操作稽核：操作者與原因

上述六個操作（`give`、`take`、`set`、`rollback`、`restore`、`import`）的稽核規則是：每次成功執行都要記下誰做的、為什麼做。操作者一律從指令執行者自動取得，不能用參數指定：玩家執行時記下其 UUID 與當時的顯示名稱，主控台執行時記下固定的主控台標記，因此無法冒充別的操作者。可選的尾段 `reason` 是單一 token（不可含空白）；空白的原因會被拒絕，超過 256 個字元會被拒絕，看起來像敏感資料的值（例如 IPv4 或 IPv6 位址的壓縮、完整、中括號、映射或區域形式、網址或 token）會被拒絕且不會把原文回顯出來，也不會做任何網路查詢。完整形式的 IPv6 位址即使嵌在較長的 token 裡（例如帶有 `ip=` 前綴）也會同樣被拒，一般含單一冒號的 token 不受影響。還原快照若帶有損壞的操作紀錄，會在動到線上資料之前被拒，診斷只會指出紀錄的位置與欄位，不會回顯儲存的值，線上資料保持不動。這層檢查只是基本的過濾，不宣稱能識別所有敏感字串。省略 `reason` 時，紀錄會記下明確的預設值 `unspecified`，不會留空。同時給貨幣與原因時，貨幣在前、原因在最後。稽核欄位只在交易成功寫入時跟交易放在同一次原子提交裡存檔；失敗或回滾不會留下孤立的稽核紀錄。`restore` 會另外留一筆獨立的操作紀錄，記下來源備份與安全備份兩個 ID，並跟還原放在同一次提交裡，不會另外產生一筆零元存入；這筆紀錄只在內部保存，不新增公開查詢指令。一般操作的原由跟交易存在一起，仍可透過 `/aceeco history` 的 `reasonContains` 過濾命中；獨立的還原操作紀錄不會顯示為明細項目。

### 增加餘額：`/aceeco give`

需要替玩家增加餘額時使用 `give`。尾段可加上可選的 `reason` 留作稽核，規則見上方稽核一節。

範例：`/aceeco give Alex 500 <currency>`。附原因：`/aceeco give Alex 500 <currency> monthly-reward`。

### 扣除餘額：`/aceeco take`

需要從玩家餘額扣除金額時使用 `take`。尾段可加上可選的 `reason` 留作稽核，規則見上方稽核一節。

範例：`/aceeco take Alex 125 <currency>`。附原因：`/aceeco take Alex 125 <currency> penalty-case`。

### 設定餘額：`/aceeco set`

需要把玩家餘額直接設成指定金額時使用 `set`。尾段可加上可選的 `reason` 留作稽核，規則見上方稽核一節。

範例：`/aceeco set Alex 1000 <currency>`。附原因：`/aceeco set Alex 1000 <currency> balance-fix`。

### 凍結帳號：`/aceeco freeze`

懷疑帳號被盜或需要立刻止血時使用 `freeze`。用法：`/aceeco freeze <player> [reason]`。玩家或主控台持有 `aceeconomy.admin.freeze` 就能執行。玩家不存在會明確拒絕，不動任何狀態；重複凍結已經凍結的帳號是冪等的，狀態與版本號都不變。成功會記一條控制稽核（可按原因查到），絕不偽造金錢交易。變更在玩家查找之後走共用 IO 執行緒，指令會立刻返回，回覆只在完成後發一次，慢控制落盤不會在控制路徑上卡住地域執行緒；已通過 permit 的普通金流在控制寫鎖落盤期間仍會等待（已接受的線性化代價，慢盤量測留給整合驗證）。凍結期間該帳號不能發起任何餘額變更（轉出、提領、兌票、`give`／`take`／`set` 都沒有例外），也不能接收（轉入、外部存入都會明確失敗）；被拒絕的轉帳一分錢都不會扣，被拒絕的兌票也不會燒掉序號。查餘額、查明細、排行榜不受影響。

範例：`/aceeco freeze Alex incident-case`。拒絕訊息走 `general.account-frozen`。

### 解凍帳號：`/aceeco unfreeze`

調查結束後使用 `unfreeze` 恢復。用法：`/aceeco unfreeze <player> [reason]`。它需要獨立的 `aceeconomy.admin.unfreeze` 權限，只有凍結權限是不夠的。未知玩家與重複解凍的處理與 `freeze` 相同（明確拒絕、冪等無操作），成功同樣記一條控制稽核。

範例：`/aceeco unfreeze Alex cleared-case`。

### 全服唯讀：`/aceeco readonly`

需要一鍵停住全服金流時使用 `readonly`。用法：`/aceeco readonly <on|off> [reason]`。玩家或主控台持有 `aceeconomy.admin.readonly` 就能執行。打開後所有帳號的餘額變更都會被拒絕，訊息走 `general.global-readonly`；查詢、預覽、備份不受影響，管理員隨時可以關掉。重複執行目前狀態是冪等的。切換跟 `freeze` 一樣走共用 IO 執行緒，指令立刻返回，回覆只在完成後發一次。已通過 permit 的普通金流在控制寫鎖落盤期間仍會等待（已接受的線性化代價，慢盤量測留給整合驗證）。控制檔案損毀時伺服器本來就已經 fail-closed 到唯讀，這時關閉會被忽略（先修檔案再重啟），絕不會繞過保護。每次切換都記一條控制稽核。

範例：

```text
/aceeco readonly on emergency-hold
/aceeco readonly off recovered
```

### 查詢交易歷史：`/aceeco history`

管理員需要檢視已記錄的餘額變更時使用 `history`。這個指令是唯讀的：不會改變餘額或稽核紀錄。省略 `player` 時列出所有帳戶的交易；省略 `currency` 時使用設定的預設貨幣；`page` 從 0 開始，每頁顯示 10 筆。空頁、找不到玩家、頁碼小於零都會有明確回覆。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco history [player] [currency] [page]` |
| 執行者 | 玩家或主控台 |
| 權限 | `aceeconomy.admin.history` |
| 排序 | 由新到舊，並以穩定的次序處理同一時刻的記錄，因此重複查詢的結果順序一致 |

範例：

```text
/aceeco history
/aceeco history Alex
/aceeco history Alex <currency>
/aceeco history Alex <currency> 2
```

### 重新載入經濟設定：`/aceeco reload`

修改經濟設定後，想套用就從主控台執行這個指令。它不接受參數，玩家不能執行。

範例：`/aceeco reload`

### 回溯交易：`/aceeco rollback`

管理員需要依 ID 復原一筆已記錄的交易時使用 `rollback`。這是具破壞性的管理操作，因此僅限主控台執行，而且需要同時擁有 `aceeconomy.admin` 與 `aceeconomy.admin.rollback`。交易 ID 是該筆交易的 UUID；格式不正確時會在查詢前就被拒絕。回溯遵循與一般交易相同的負債規則：`economy.allow-negative-balance` 為 `false` 時，會導致餘額變負的回溯會被拒絕且不變更任何資料；為 `true` 時，回溯可以在設定的負債額度內產生負餘額。尾段可加上可選的 `reason`，會跟回溯一起記錄（規則見上方稽核一節）。

各種結果都會有明確回覆：

| 結果 | 回覆 |
|---|---|
| 成功 | 指出被回溯的交易，並列出該筆回溯的稽核紀錄 ID。 |
| 已回溯過 | 說明該交易已回溯、未做任何變更；重送同一個 ID 是安全的空操作，不會重複餘額效果或稽核紀錄。 |
| 找不到交易 | 錯誤提示：沒有這個 ID 的交易。 |
| ID 格式錯誤 | 錯誤提示：參數不是有效 UUID，不會進行任何查詢。 |
| 轉帳對應腿缺失 | 錯誤提示：轉帳的另一腿找不到，無法安全回溯。 |
| 負債拒絕 | 錯誤提示：該回溯會導致餘額變負，而 `economy.allow-negative-balance` 為 `false`（或超出負債額度）；未變更任何資料，該交易仍可重試。 |
| 執行失敗 | 錯誤提示：回溯未生效，該交易仍可重試。 |
| 標記寫入失敗 | 錯誤提示：回溯效果可能已發生但回溯標記缺失；請先檢查儲存並人工核對，再考慮重試。 |

範例：`/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b`。附原因：`/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b duplicate-payout`。

`rollback` 指令已經接入實際上線的指令介面，也有自動化合約測試覆蓋，但**尚未在實際運作的伺服器上驗證過**：Folia/Bukkit 橋接的實機執行、真實 MySQL 儲存，以及用真實資料進行的故障注入演練，都還是未完成的發布門檻。在這些驗證完成之前，上表內容應視為設計規格，而不是實測結果。

### 建立邏輯備份：`/aceeco backup`

想在伺服器運作中留一份可還原的紀錄就用這個指令。它會建立一份 v2 邏輯 JSON 快照。`label` 可省略，若提供則只能使用安全的檔名字元。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco backup [label]` |
| 執行者 | 玩家或主控台 |
| 權限 | `aceeconomy.admin.backup`（另需主權限 `aceeconomy.admin`） |
| 儲存位置 | 插件控制的 `<plugin data folder>/backups` |
| 輸出 | 原子寫入且不覆寫既有檔案，並回報備份 ID |

快照是 v2 邏輯 JSON 模型，包含帳戶、餘額、交易、已回溯標記與已消耗的一次性序號，但不包含資料庫密碼或 webhook 網址。沒有獨立的 `/backup` 主指令。

### 還原邏輯備份：`/aceeco restore`

還原會替換正式經濟資料，因此只能從主控台執行，且需要主權限與子指令權限。確認字串區分大小寫，只接受小寫 `confirm`。尾段可加上可選的 `reason`；這次執行會另外留一筆獨立操作紀錄，規則見上方稽核一節。執行前先請所有玩家離線，失敗就看[常見指令錯誤](#常見指令錯誤)。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco restore <backup-id> confirm [reason]` |
| 執行者 | 僅限主控台；有任何玩家在線時會拒絕 |
| 權限 | `aceeconomy.admin.restore`（另需主權限 `aceeconomy.admin`） |
| 執行前檢查 | 在動到正式資料前，先檢查 JSON 結構、schema 版本、紀錄（含還原操作紀錄）與設定貨幣的相容性；損壞的快照會被拒絕且正式資料保持不變 |
| 安全備份 | 先備份目前狀態；安全備份失敗時中止還原 |
| 成功後 | 清除排行榜快取，但不會熱刷新 session 或 GUI。讓玩家回來前必須重啟伺服器。 |

範例：`/aceeco restore 20260824T093000-aaaa1111 confirm`。附原因：`/aceeco restore 20260824T093000-aaaa1111 confirm wrong-payout`。

### 匯入餘額：`/aceeco import`

當 EssentialsX 或 CMI 伺服器的餘額需要帶進 v2 時使用。指令只能從主控台執行，且需要 `aceeconomy.admin` 與 `aceeconomy.admin.import`。沒有精確的 `apply confirm` 時一律是預演：不寫入、不備份、不消耗防重複狀態。預演或套用都可在尾段加上可選的 `reason`，套用時會跟該次執行一起記錄（規則見上方稽核一節）。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm] [reason]` |
| 執行者 | 僅限主控台 |
| 權限 | `aceeconomy.admin.import`（另需主權限 `aceeconomy.admin`） |
| 來源格式 | Essentials：`<uuid>.yml` 玩家檔案或其目錄（`money:` 餘額、可選 `last-account-name:`）。支援 EssentialsX 2.x userdata。CMI：管理員整理好的 UTF-8 對帳檔（每行 `uuid,name,balance`，可有表頭，`.csv`/`.txt`）。CMI 的 `cmi.sqlite.db` 二進位檔不支援，會直接拒絕。 |
| 路徑 | 相對於插件控制的 `<plugin data folder>/import` 目錄。絕對路徑、`..`、symlink、不存在的項目、過大檔案、敏感檔名與各來源不支援的副檔名，都在讀檔前拒絕。 |
| 貨幣 | 省略時使用設定的預設貨幣。未知的貨幣 ID 會在備份前中止整個流程。 |
| 套用 | 只有精確的 `apply confirm`（`confirm` 限小寫）會寫入。寫入前先建立 `pre-import` 安全備份；備份失敗則整批不套用。重跑同一來源是安全的：已套用的紀錄會回報為跳過。 |
| 報告 | `applied` / `skipped` / `failed` 計數與失敗摘要。只要有任何一筆失敗，就不會宣稱完全成功。 |

範例：

```text
/aceeco import essentials userdata
/aceeco import essentials userdata coin apply confirm
/aceeco import cmi balances.csv
/aceeco import cmi balances.csv coin apply confirm
/aceeco import cmi balances.csv coin apply confirm migration-topup
```

先把 Essentials 的 `plugins/Essentials/userdata/` 檔案（或整理好的 CMI 對帳檔）複製到 `plugins/AceEconomy/import/`；指令不會讀取該目錄以外的任何位置。詳細用法見本頁 `import` 章節。

## 常見指令錯誤

| 狀況 | 檢查方式 |
|---|---|
| 沒有權限 | 確認執行者擁有主指令或子指令列出的權限。 |
| 執行者類型不對 | 玩家限定指令請在遊戲內執行；`/aceeco reload`、`/aceeco rollback`、`/aceeco restore` 與 `/aceeco import` 請從主控台執行；`restore` 另要求沒有玩家在線。 |
| 參數少了或多了 | 對照正確的子指令和用法。例如 `/baltop` 必須接 `top`，不接受頁碼。 |
| 找不到玩家 | 檢查玩家名稱後再試一次。 |
| 找不到貨幣 | 使用已設定的貨幣 ID；省略貨幣時會使用設定的預設值。 |
| 金額格式錯誤 | 請輸入大於零、符合貨幣小數位數且未超過指令上限的數字。 |
| 經濟操作被拒絕 | 依回傳的錯誤修正帳戶或經濟條件，例如餘額不足或超過債務上限。 |
| 回溯被拒絕 | 依錯誤提示判別：需要有效的交易 UUID、已回溯的交易是空操作、標記失敗需先人工核對再重試。 |
| 還原確認被拒絕 | 必須使用精確的小寫 `confirm`：`/aceeco restore <backup-id> confirm [reason]`。`CONFIRM`、`Confirm` 與其他拼法都會被拒絕。 |
| 還原時有玩家在線 | 先讓所有玩家離線，再從主控台重試。 |
| 還原的安全備份或快照檢查失敗 | 保留正式資料，依錯誤提示檢查原因；不要刪除目前儲存內容來強行還原。 |
| 匯入確認被拒絕 | 沒有精確的 `apply confirm` 就只是預覽。請重跑為 `/aceeco import <essentials\|cmi> <path> [currency] apply confirm [reason]`。 |
| 匯入路徑被拒絕 | 路徑必須相對於 `<plugin data folder>/import`。絕對路徑、`..`、symlink、敏感檔名與不支援的副檔名都會在讀檔前拒絕。 |
| 稽核原因被拒絕 | `reason` 必須是單一 token（不含空白、至多 256 個字元），也可省略；看起來像敏感資料的值會被拒絕且不會回顯原文。 |
| 匯入回報失敗 | 看失敗摘要：未知格式、無效數字與負餘額不會被靜默當成零。修好來源後重跑，只會補上還沒套用的部分。 |
