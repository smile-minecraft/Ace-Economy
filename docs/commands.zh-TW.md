# 指令與權限

[English](commands.md) · [简体中文](commands.zh-CN.md) · 繁體中文

你可能只是想查餘額、轉一筆錢，或打開銀行介面。這頁把玩家指令和管理員指令分開，方便你直接找到要用的指令。以下是目前 v2 語法：每個主指令後面都要接一個指定的子指令。

## 目錄

- [快速查表](#快速查表)
- [玩家用法](#玩家用法)
- [管理員權限](#管理員權限)
- [常見指令錯誤](#常見指令錯誤)

## 快速查表

| 主指令 | 子指令 | 用法 | 執行者 | 權限 | 別名 |
|---|---|---|---|---|---|
| `/money` | `balance` | `[player] [currency]` | 玩家或主控台；主控台必須提供 `player` | `aceeconomy.command.money` | `/balance` |
| `/pay` | `send` | `<player> <amount> [currency]` | 僅限玩家 | `aceeconomy.command.pay` | 無 |
| `/withdraw` | `cash` | `<amount> [currency]` | 僅限玩家 | `aceeconomy.command.withdraw` | 無 |
| `/baltop` | `top` | `[currency]` | 玩家或主控台 | `aceeconomy.command.baltop` | 無 |
| `/bank` | `open` | 不接受參數 | 僅限玩家 | `aceeconomy.command.bank` | 無 |
| `/aceeco` | `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore`、`import` | 見管理員參考 | `reload`、`rollback`、`restore`、`import` 僅限主控台；`backup` 與其他子指令可由玩家或主控台執行 | `aceeconomy.admin` 加上子指令權限 | 無 |

`<必填>` 代表必須提供的值；`[可選]` 代表可省略的值。省略 `currency` 時，使用設定的預設貨幣。貨幣 ID 不區分大小寫。

金額必須是有效數字、大於零、符合該貨幣的小數位數，而且不得超過 `1,000,000,000,000,000`。

目前 v2 指令規格列出的主指令別名只有 `/money` 的 `/balance`。它仍然要接 `balance` 子指令：`/balance balance [player] [currency]`。沒有獨立的 `/backup` 或 `/restore` 主指令，請使用 `/aceeco` 的管理子指令。

## 玩家用法

### 查詢餘額：`/money balance`

想看自己的餘額時直接省略玩家名稱。查詢其他帳戶時加上玩家名稱；主控台沒有自己的玩家帳戶，所以必須使用帶有 `player` 的完整形式。

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

### 轉帳：`/pay send`

把錢轉給其他玩家。這個指令必須在遊戲內由玩家執行。

| 項目 | 說明 |
|---|---|
| 用法 | `/pay send <player> <amount> [currency]` |
| 執行者 | 僅限玩家 |
| 權限 | `aceeconomy.command.pay` |
| 別名 | v2 指令規格未提供別名 |

範例：`/pay send Alex 25 <currency>`

### 提領銀行支票：`/withdraw cash`

把指定金額提領成銀行支票。這個指令必須由玩家執行。

| 項目 | 說明 |
|---|---|
| 用法 | `/withdraw cash <amount> [currency]` |
| 執行者 | 僅限玩家 |
| 權限 | `aceeconomy.command.withdraw` |
| 別名 | v2 指令規格未提供別名 |

範例：`/withdraw cash 100 <currency>`

### 查看排行榜：`/baltop top`

查看餘額最高的玩家。玩家和主控台都能執行；不想使用預設貨幣時，再指定貨幣即可。

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

開啟 AceEconomy 銀行介面。這個指令不接受參數，必須由玩家執行。

| 項目 | 說明 |
|---|---|
| 用法 | `/bank open` |
| 執行者 | 僅限玩家 |
| 權限 | `aceeconomy.command.bank` |
| 別名 | v2 指令規格未提供別名 |

銀行介面的按鈕與格子對應如下：

- 存款（DEPOSIT）：第 4 格（上方中間）。
- 提款（WITHDRAW）：第 11 格與第 13 格（分別是 `100` 與 `500` 提款按鈕）。
- 關閉（CLOSE）：第 15 格。

存入一張有效的 v2 銀行支票時，必須先完成持久防重播機制與入帳，之後才會移除支票或減少堆疊數量。無效、被重播或入帳失敗的支票，會留在玩家物品欄裡。手持支票按右鍵可直接兌回，走與銀行面板存款按鈕相同的原子入帳路徑。如果入帳已經完成卻拿不掉支票，這筆錢仍然算數，伺服器稽核紀錄會記下支票編號、玩家與實際入帳金額，全程可追查。多張支票是用複本扣掉一張再一次寫回，寫入失敗時整疊會留在手上：收好並聯絡管理員。單張支票的清除是一次寫入，失敗後格子狀態無法從外部確認，因此只保證稽核紀錄可追查、不保證票還在：把票根留好，不要重試（錢已經算過，重送會被擋下），直接聯絡管理員。管理員會拿支票編號去對稽核紀錄，先收回或作廢重複的支票，再用例如 `/aceeco give` 把錢補上。

## 管理員權限

管理員主指令是 `/aceeco`，v2 指令規格未提供別名。主權限為 `aceeconomy.admin`，每個操作也各有自己的權限節點。變更餘額的子指令沿用「玩家名稱、金額、可選貨幣」的格式。`history` 只讀取已記錄的交易，不會變更餘額。`reload`、`rollback`、`restore` 與 `import` 只能由主控台執行；`rollback` 要帶交易 ID，`restore` 則要帶備份 ID 與精確的 `confirm`，`import` 要帶來源與路徑（寫入時需精確的 `apply confirm`）。

| 子指令 | 用法 | 執行者 | 子指令權限 | 別名 |
|---|---|---|---|---|
| `give` | `/aceeco give <player> <amount> [currency]` | 玩家或主控台 | `aceeconomy.admin.give` | 無 |
| `take` | `/aceeco take <player> <amount> [currency]` | 玩家或主控台 | `aceeconomy.admin.take` | 無 |
| `set` | `/aceeco set <player> <amount> [currency]` | 玩家或主控台 | `aceeconomy.admin.set` | 無 |
| `history` | `/aceeco history [player] [currency] [page]` | 玩家或主控台 | `aceeconomy.admin.history` | 無 |
| `reload` | `/aceeco reload` | 僅限主控台 | `aceeconomy.admin.reload` | 無 |
| `rollback` | `/aceeco rollback <transaction-id>` | 僅限主控台 | `aceeconomy.admin.rollback` | 無 |
| `backup` | `/aceeco backup [label]` | 玩家或主控台 | `aceeconomy.admin.backup` | 無 |
| `restore` | `/aceeco restore <backup-id> confirm` | 僅限主控台 | `aceeconomy.admin.restore` | 無 |
| `import` | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm]` | 僅限主控台 | `aceeconomy.admin.import` | 無 |

插件宣告的預設值是：玩家指令權限為 `true`，管理員權限為 `op`。另外，插件也宣告 `aceeconomy.bypass.debt`，預設值為 `op`，用於跳過債務上限的權限。

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

### 重載經濟設定：`/aceeco reload`

修改經濟設定後，從主控台執行這個指令。它不接受參數，玩家不能執行。

範例：`/aceeco reload`

### 回滾交易：`/aceeco rollback`

管理員需要依 ID 復原一筆已記錄的交易時使用 `rollback`。這是具破壞性的管理操作，因此僅限主控台執行，而且需要同時擁有 `aceeconomy.admin` 與 `aceeconomy.admin.rollback`。交易 ID 是該筆交易的 UUID；格式不正確時會在查詢前就被拒絕。

各種結果都會有明確回覆：

| 結果 | 回覆 |
|---|---|
| 成功 | 指出被回滾的交易，並列出該筆回滾的稽核紀錄 ID。 |
| 已回滾過 | 說明該交易已回滾、未做任何變更；重送同一個 ID 是安全的空操作，不會重複餘額效果或稽核紀錄。 |
| 找不到交易 | 錯誤提示：沒有這個 ID 的交易。 |
| ID 格式錯誤 | 錯誤提示：參數不是有效 UUID，不會進行任何查詢。 |
| 轉帳對應腿缺失 | 錯誤提示：轉帳的另一腿找不到，無法安全回滾。 |
| 執行失敗 | 錯誤提示：回滾未生效，該交易仍可重試。 |
| 標記寫入失敗 | 錯誤提示：回滾效果可能已發生但回滾標記缺失；請先檢查儲存並人工核對，再考慮重試。 |

範例：`/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b`

`rollback` 指令已經接入實際上線的指令介面，也有自動化契約測試覆蓋，但**尚未在實際運作的伺服器上驗證過**：Folia/Bukkit 橋接的實機執行、真實 MySQL 儲存，以及用真實資料進行的故障注入演練，都還是未完成的發布門檻。在這些驗證完成之前，上表內容應視為設計規格，而不是實測結果。

### 建立邏輯備份：`/aceeco backup`

這個指令會在伺服器運作中建立 v2 邏輯 JSON 快照。`label` 可省略，若提供則只能使用安全的檔名字元。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco backup [label]` |
| 執行者 | 玩家或主控台 |
| 權限 | `aceeconomy.admin.backup`（另需主權限 `aceeconomy.admin`） |
| 儲存位置 | 插件控制的 `<plugin data folder>/backups` |
| 輸出 | 原子寫入且不覆寫既有檔案，並回報備份 ID |

快照是 v2 邏輯 JSON 模型，包含帳戶、餘額、交易、已回滾標記與已消耗的一次性序號，但不包含資料庫密碼或 webhook 網址。沒有獨立的 `/backup` 主指令。

### 還原邏輯備份：`/aceeco restore`

還原會替換正式經濟資料，因此只能從主控台執行，且需要主權限與子指令權限。確認字串區分大小寫，只接受小寫 `confirm`。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco restore <backup-id> confirm` |
| 執行者 | 僅限主控台；有任何玩家在線時會拒絕 |
| 權限 | `aceeconomy.admin.restore`（另需主權限 `aceeconomy.admin`） |
| 執行前檢查 | 在動到正式資料前，先檢查 JSON 結構、schema 版本、紀錄與設定貨幣的相容性 |
| 安全備份 | 先備份目前狀態；安全備份失敗時中止還原 |
| 成功後 | 清除排行榜快取，但不會熱刷新 session 或 GUI。讓玩家回來前必須重啟伺服器。 |

範例：`/aceeco restore 20260824T093000-aaaa1111 confirm`

### 匯入餘額：`/aceeco import`

當 EssentialsX 或 CMI 伺服器的餘額需要帶進 v2 時使用。指令只能從主控台執行，且需要 `aceeconomy.admin` 與 `aceeconomy.admin.import`。沒有精確的 `apply confirm` 時一律是預演：不寫入、不備份、不消耗防重複狀態。

| 項目 | 說明 |
|---|---|
| 用法 | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm]` |
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
```

先把 Essentials 的 `plugins/Essentials/userdata/` 檔案（或整理好的 CMI 對帳檔）複製到 `plugins/AceEconomy/import/`；指令不會讀取該目錄以外的任何位置。遷移流程見[從 AceEconomy v1 升級](upgrade-from-v1.zh-TW.md)。

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
| 回滾被拒絕 | 依錯誤提示判別：需要有效的交易 UUID、已回滾的交易是空操作、標記失敗需先人工核對再重試。 |
| 還原確認被拒絕 | 必須使用精確的小寫 `confirm`：`/aceeco restore <backup-id> confirm`。`CONFIRM`、`Confirm` 與其他拼法都會被拒絕。 |
| 還原時有玩家在線 | 先讓所有玩家離線，再從主控台重試。 |
| 還原的安全備份或快照檢查失敗 | 保留正式資料，依錯誤提示檢查原因；不要刪除目前儲存內容來強行還原。 |
| 匯入確認被拒絕 | 沒有精確的 `apply confirm` 就只是預覽。請重跑為 `/aceeco import <essentials\|cmi> <path> [currency] apply confirm`。 |
| 匯入路徑被拒絕 | 路徑必須相對於 `<plugin data folder>/import`。絕對路徑、`..`、symlink、敏感檔名與不支援的副檔名都會在讀檔前拒絕。 |
| 匯入回報失敗 | 看失敗摘要：未知格式、無效數字與負餘額不會被靜默當成零。修好來源後重跑，只會補上還沒套用的部分。 |
