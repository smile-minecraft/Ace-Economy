# 資料庫概念與升級

[English](database.md) · [简体中文](database.zh-CN.md) · 繁體中文

這一頁說明目前 v2 的資料模型，幫助管理員在升級時認出哪些資料必須保留。它不是手動建表的 SQL 腳本。選擇 `storage.type: mysql` 或 `storage.type: sqlite` 後，AceEconomy 會依照所選的資料庫類型建立 schema；JSON 則是把同一套邏輯模型存在檔案裡。

## 目錄

- [兩個意義不同的版本值](#兩個意義不同的版本值)
- [v2 模型](#v2-模型)
  - [`ace_v2_schema`](#ace_v2_schema)
  - [`ace_v2_accounts`](#ace_v2_accounts)
  - [`ace_v2_balances`](#ace_v2_balances)
  - [`ace_v2_transactions`](#ace_v2_transactions)
- [JSON 與 SQL 表示相同資料](#json-與-sql-表示相同資料)
- [新的 SQL 儲存位置如何初始化](#新的-sql-儲存位置如何初始化)
- [升級路徑](#升級路徑)
- [備份與復原](#備份與復原)
- [與舊文件相比的變化](#與舊文件相比的變化)

## 兩個意義不同的版本值

這裡有兩個獨立的版本概念：

| 版本 | 出現位置 | 意義 |
| --- | --- | --- |
| 設定 `2.0` | `config.yml` → `version` | v2 設定檔的格式。 |
| 持久化 `1` | SQL 資料表 `ace_v2_schema`，或 JSON `schemaVersion` | backend 目前理解的 v2 持久化模型。 |

請不要把持久化值當成設定值，也不要把舊版 v1 資料表結構當成 v2 schema。以下列出的是 v2 名稱。

## v2 模型

這套模型包含一個 schema 標記、一個帳戶關聯、一個餘額關聯與一個交易關聯。SQL 中的四張表是：

### `ace_v2_schema`

這張表記錄持久化模型版本。欄位包含 `version` 與 `updated_at`；初始化的 v2 儲存位置會寫入版本 `1`。這一列用來告訴 backend 正在開啟哪個模型，不包含玩家餘額。

### `ace_v2_accounts`

這張表把帳戶 owner ID 對應到最後儲存的 owner 名稱：

| 欄位 | 意義 |
| --- | --- |
| `owner` | 帳戶擁有者的 UUID。 |
| `owner_name` | 該擁有者儲存的顯示名稱。 |

UUID 才是持久化身份。名稱是供顯示使用的儲存值，不是帳戶鍵。

### `ace_v2_balances`

這張表為每一組 `(owner, currency_id)` 儲存一個金額：

| 欄位 | 意義 |
| --- | --- |
| `owner` | 帳戶擁有者 UUID。 |
| `currency_id` | 設定中的貨幣 ID，例如 `dollar` 或 `token`。 |
| `amount` | 精確的十進位金額，以文字儲存。 |

`(owner, currency_id)` 這一組值就是鍵。因此，修改貨幣 ID 是資料變更，不只是改顯示文字。

### `ace_v2_transactions`

這張表記錄理解一筆財務事件及其後續狀態所需的資訊：

| 欄位 | 意義 |
| --- | --- |
| `id` | 交易 UUID。 |
| `account_id` | 這筆紀錄影響的帳戶。 |
| `counterparty` | 有對手帳戶時記錄對方；也可以是空值。 |
| `currency_id` | 這筆操作使用的貨幣。 |
| `amount` | 精確的十進位金額，以文字儲存。 |
| `type` | 領域中的交易類型。 |
| `balance_before` | 事件前餘額，若有提供。 |
| `balance_after` | 事件後餘額，若有提供。 |
| `timestamp` | 事件時間。 |
| `reason` | 事件可附帶的原因。 |
| `reverted` | 這筆紀錄是否已標記為 reverted。 |

兩種 SQL dialect 都以十進位文字儲存金額與餘額快照。MySQL 使用 v2 的 `VARCHAR` 表示與 boolean reverted 標記；SQLite 使用文字與整數形式的布林值。邏輯欄位相同。

## JSON 與 SQL 表示相同資料

JSON backend 會在一份文件中保存相同概念：

```json
{
  "schemaVersion": 1,
  "accounts": {
    "<owner-uuid>": {
      "owner": "<owner-uuid>",
      "ownerName": "<display-name>",
      "balances": {
        "dollar": "1000.00"
      }
    }
  },
  "transactions": []
}
```

這個範例刻意保持精簡。實際 snapshot 可以包含許多帳戶與交易。`accounts` 保存餘額，`transactions` 保存事件歷史與 `reverted` 狀態。

## 新的 SQL 儲存位置如何初始化

SQL backend 在沒有 v2 資料表的全新位置啟動時，會建立：

1. 含有 v2 持久化版本列的 `ace_v2_schema`。
2. 保存帳戶身份與名稱的 `ace_v2_accounts`。
3. 保存每位 owner、每種貨幣金額的 `ace_v2_balances`。
4. 保存事件紀錄與回復狀態的 `ace_v2_transactions`。

對相容的既有儲存位置，資料表建立可以重複執行而不會重複建資料。應由插件初始化資料表；管理員不需要把舊版 v1 DDL 腳本複製到新的 v2 資料庫。

## 升級路徑

v2 設定與持久化模型有清楚的邊界。舊版 v1 設定或舊版 v1 資料表不會被當成 v2 資料讀取。請把升級規劃成建立新的 v2 儲存位置：

1. 修改檔案或資料庫設定前，先保留舊安裝的備份。
2. 在 v2 `config.yml` 選擇 JSON、SQLite 或 MySQL。
3. 使用選定的位置啟動，讓插件建立 v2 模型。
4. 如果已有 v2 JSON snapshot，將該 snapshot 還原到新的 backend。
5. 正式運作前，確認帳戶餘額與設定中的貨幣 ID。

這套模型沒有描述舊版 v1 列與 v2 帳戶、餘額、交易之間的自動轉換。v2 snapshot 帶有共用的邏輯模型，因此可在 JSON 與 SQL backend 間搬移；這不代表舊版 v1 備份也相容。

## 備份與復原

操作順序請參考[持久化、備份與還原](persistence.zh-TW.md)。需要保留的完整資料邊界是 v2 模型：帳戶、餘額、交易，以及 schema 版本。

替換正式儲存位置前，請先停止伺服器並保留目前的備份。還原時會先解析輸入的 v2 JSON snapshot，再檢查其 schema 版本。解析或版本檢查失敗時，現有資料會保留。成功還原後，backend 會在還原操作中用 snapshot 替換目前的帳戶、餘額與交易。

請不要把刪除 schema 標記或刪除 v2 資料表當成第一個故障排除步驟。若儲存位置不相容，請先保留備份，並把重建視為會清空資料、從空白狀態開始的破壞性操作。

## 與舊文件相比的變化

舊文件中的 `ace_balances`、`ace_users` 與 `ace_transaction_logs` 不是 v2 建庫指南。v2 將帳戶身份與餘額分開放在 `ace_v2_accounts` 與 `ace_v2_balances`，交易歷史則由 `ace_v2_transactions` 表示。v2 schema 也以文字保存精確十進位值，並包含 schema 標記。

如果既有資料庫只有那些舊資料表名稱，請不要直接讓 v2 backend 指向它並假設會接收舊資料。請保留舊備份，建立或選擇 v2 儲存位置，並把資料轉換當成另外的 migration 決策。
