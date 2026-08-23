# Database concepts and upgrades / 資料庫概念與升級

This page describes the current v2 data model so an administrator can recognize the data that must be preserved during an upgrade. It is not a manual SQL creation script. Selecting `storage.type: mysql` or `storage.type: sqlite` lets AceEconomy initialize the schema for the selected dialect; JSON stores the same logical model in a file.

本頁說明目前 v2 資料模型，讓管理員在升級時知道哪些資料需要保留。這不是手動建立 SQL 的腳本。選擇 `storage.type: mysql` 或 `storage.type: sqlite` 後，AceEconomy 會依選定 dialect 初始化 schema；JSON 則在檔案中儲存相同的邏輯模型。

## Two versions with different meanings / 兩個版本欄位的意義

There are two separate version concepts:

| Version | Where it appears | Meaning |
| --- | --- | --- |
| Configuration `2.0` | `config.yml` → `version` | The shape of the v2 configuration file. |
| Persistence `1` | SQL table `ace_v2_schema`, or JSON `schemaVersion` | The current v2 persistence model understood by the backend. |

這裡有兩個不同的版本概念：

| 版本 | 出現位置 | 意義 |
| --- | --- | --- |
| 設定版本 `2.0` | `config.yml` 的 `version` | v2 設定檔的格式。 |
| 持久化版本 `1` | SQL 的 `ace_v2_schema`，或 JSON 的 `schemaVersion` | backend 目前理解的 v2 持久化模型。 |

Do not use the persistence value as the configuration value, and do not treat an old v1 table layout as a v2 schema. The names below are the v2 names.

請不要把持久化版本值拿來填設定版本，也不要把舊版 v1 資料表結構當成 v2 schema。以下列出的名稱才是 v2 名稱。

## The v2 model / v2 模型

The model has one schema marker, one account relation, one balance relation, and one transaction relation. The four SQL tables are:

這套模型包含一個 schema 標記、一個帳戶資料表、一個餘額資料表與一個交易資料表。SQL 中的四張表是：

### `ace_v2_schema` / schema 標記

This table records the persistence model version. It contains `version` and `updated_at`; the initialized v2 store writes version `1`. The row tells the backend which model it is opening. It does not contain player balances.

這張表記錄持久化模型版本。欄位是 `version` 與 `updated_at`；初始化的 v2 儲存位置會寫入版本 `1`。這一列是讓 backend 判斷正在開啟哪個模型，不存放玩家餘額。

### `ace_v2_accounts` / 帳戶

This table maps an account owner ID to the last stored owner name:

這張表把帳戶 owner ID 對應到最後儲存的 owner 名稱：

| Column / 欄位 | Meaning / 意義 |
| --- | --- |
| `owner` | The account owner's UUID. / 帳戶擁有者的 UUID。 |
| `owner_name` | The stored display name for that owner. / 該擁有者儲存的顯示名稱。 |

The UUID is the durable identity. The name is a stored name for display, not the account key.

UUID 才是持久化身份；名稱是供顯示使用的儲存值，不是帳戶鍵。

### `ace_v2_balances` / 餘額

This table stores one amount for each `(owner, currency_id)` pair:

這張表為每一組 `(owner, currency_id)` 儲存一個金額：

| Column / 欄位 | Meaning / 意義 |
| --- | --- |
| `owner` | The account owner UUID. / 帳戶擁有者 UUID。 |
| `currency_id` | The configured currency ID, such as `dollar` or `token`. / 設定中的貨幣 ID，例如 `dollar` 或 `token`。 |
| `amount` | The exact decimal amount, stored as text. / 精確的十進位金額，以文字儲存。 |

The pair `(owner, currency_id)` is the key. That is why changing a currency ID is a data change, not just a display change.

`(owner, currency_id)` 是這張表的鍵。因此修改貨幣 ID 不只是改顯示文字，而是資料變更。

### `ace_v2_transactions` / 交易

This table records the information needed to understand a financial event and its later state:

這張表記錄理解一筆財務事件及其後續狀態所需的資料：

| Column / 欄位 | Meaning / 意義 |
| --- | --- |
| `id` | The transaction UUID. / 交易 UUID。 |
| `account_id` | The account affected by the record. / 這筆紀錄影響的帳戶。 |
| `counterparty` | The other account when the operation has one; it may be empty. / 有對手帳戶時記錄對方；也可以是空值。 |
| `currency_id` | The currency used by the operation. / 這筆操作使用的貨幣。 |
| `amount` | The exact decimal amount as text. / 以文字保存的精確十進位金額。 |
| `type` | The domain transaction type. / 領域中的交易類型。 |
| `balance_before` | The balance before the event, when available. / 事件前餘額，若有提供。 |
| `balance_after` | The balance after the event, when available. / 事件後餘額，若有提供。 |
| `timestamp` | The event time. / 事件時間。 |
| `reason` | An optional reason associated with the event. / 事件可附帶的原因。 |
| `reverted` | Whether this record has been marked reverted. / 這筆紀錄是否已標記為 reverted。 |

Amounts and balance snapshots are stored as decimal text in both SQL dialects. MySQL uses its v2 `VARCHAR` representation and a boolean reverted flag; SQLite uses text and its integer boolean representation. The logical fields are the same.

兩種 SQL dialect 都以十進位文字儲存金額與餘額快照。MySQL 使用 v2 的 `VARCHAR` 表示與 boolean reverted 欄位；SQLite 使用文字與整數形式的布林值。邏輯欄位相同。

## JSON and SQL represent the same data / JSON 與 SQL 表示相同資料

The JSON backend keeps the same concepts in one document:

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

The example is intentionally small. A real snapshot can contain many accounts and transactions. `accounts` carries balances, while `transactions` carries the event history and the `reverted` state.

範例刻意保持精簡。實際 snapshot 可以包含許多帳戶與交易。`accounts` 保存餘額，`transactions` 保存事件歷史與 `reverted` 狀態。

## How a new SQL store is initialized / 新 SQL 儲存位置如何初始化

When an SQL backend starts with no v2 tables, it creates:

SQL backend 在沒有 v2 資料表的全新位置啟動時，會建立：

1. `ace_v2_schema` with the v2 persistence version row. / 含有 v2 持久化版本列的 `ace_v2_schema`。
2. `ace_v2_accounts` for account identity and stored name. / 保存帳戶身份與名稱的 `ace_v2_accounts`。
3. `ace_v2_balances` for one amount per owner and currency. / 保存每位 owner、每種貨幣金額的 `ace_v2_balances`。
4. `ace_v2_transactions` for event records and revert state. / 保存事件紀錄與回復狀態的 `ace_v2_transactions`。

Table creation is idempotent for an existing compatible store. The plugin should initialize the tables; administrators do not need to copy an old v1 DDL script into a new v2 database.

對相容的既有儲存位置，資料表建立可以重複執行而不會重複建立資料。應由插件初始化資料表；管理員不需要把舊版 v1 DDL 腳本複製到新的 v2 資料庫。

## Upgrade path / 升級路徑

The v2 configuration and persistence model are clean boundaries. An old v1 configuration or old v1 tables are not read as v2 data. Plan the upgrade as a new v2 store:

v2 設定與持久化模型有清楚的邊界。舊版 v1 設定或舊版 v1 資料表不會被當成 v2 資料讀取。請把升級規劃成建立新的 v2 儲存位置：

1. Keep a backup of the old installation before changing files or database settings. / 修改檔案或資料庫設定前，先保留舊安裝的備份。
2. Choose JSON, SQLite, or MySQL in the v2 `config.yml`. / 在 v2 `config.yml` 選擇 JSON、SQLite 或 MySQL。
3. Start with the selected location and let the plugin create the v2 model. / 使用選定的位置啟動，讓插件建立 v2 模型。
4. If you have a v2 JSON snapshot, restore that snapshot into the new backend. / 如果已有 v2 JSON snapshot，將它還原到新的 backend。
5. Check account balances and configured currency IDs before normal operation. / 正式運作前，確認帳戶餘額與設定中的貨幣 ID。

There is no automatic conversion described by this model between old v1 rows and v2 accounts, balances, and transactions. A v2 snapshot is portable between the JSON and SQL backends because it carries the shared logical model; that portability does not make an old v1 backup compatible.

這套模型沒有把舊版 v1 列自動轉換成 v2 帳戶、餘額與交易的流程。v2 snapshot 帶有共用的邏輯模型，因此可在 JSON 與 SQL backend 間搬移；這不代表舊版 v1 備份也相容。

## Backups and recovery / 備份與復原

Use the persistence document for the operational sequence in [Persistence, backup, and restore](persistence.md). The important data boundary is the complete v2 model: accounts, balances, transactions, and the schema version.

操作順序請參考[持久化、備份與還原](persistence.md)。需要保留的資料邊界是完整的 v2 模型：帳戶、餘額、交易，以及 schema 版本。

Before replacing a live store, stop the server and retain the current backup. A restore first parses the incoming v2 JSON snapshot and checks its schema version. If parsing or version validation fails, the existing data is left in place. A successful restore replaces the current accounts, balances, and transactions with the snapshot inside the backend's restore operation.

替換正式儲存位置前，請先停止伺服器並保留目前的備份。還原時會先解析輸入的 v2 JSON snapshot，再檢查 schema 版本。解析或版本檢查失敗時，現有資料會保留。成功還原後，backend 會在還原操作中用 snapshot 替換目前的帳戶、餘額與交易。

Do not delete the schema marker or drop the v2 tables as a first troubleshooting step. If the store is incompatible, preserve a backup and treat recreation as a data-destructive operation that starts empty.

請不要把刪除 schema 標記或刪除 v2 資料表當成第一個故障排除步驟。若儲存位置不相容，請先保留備份，並把重建視為會清空資料、從空白狀態開始的破壞性操作。

## What changed from the old document / 舊文件最容易造成的誤解

The old table names `ace_balances`, `ace_users`, and `ace_transaction_logs` are not the v2 creation guide. In v2, account identity and balances are separated into `ace_v2_accounts` and `ace_v2_balances`, while transaction history is represented by `ace_v2_transactions`. The v2 schema also stores exact decimal values as text and includes a schema marker.

舊文件中的 `ace_balances`、`ace_users` 與 `ace_transaction_logs` 不是 v2 建庫指南。v2 將帳戶身份與餘額分開放在 `ace_v2_accounts` 與 `ace_v2_balances`，交易歷史則放在 `ace_v2_transactions`。v2 schema 也以文字保存精確十進位值，並包含 schema 標記。

If an existing database contains only those old table names, do not point a v2 backend at it and assume the rows will be adopted. Keep the old backup, create or select a v2 location, and handle any data conversion as a separate migration decision.

如果既有資料庫只有那些舊資料表名稱，請不要直接讓 v2 backend 指向它並假設會接收舊資料。請保留舊備份，建立或選擇 v2 儲存位置，並把資料轉換當成另外的 migration 決策。
