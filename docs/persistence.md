# Persistence, backup, and restore / 持久化、備份與還原

AceEconomy stores the same logical v2 data through either a JSON file or a SQL backend. JSON and SQLite are local to the plugin data folder; MySQL is a network database configured through `storage.mysql.*`. The storage choice changes the physical location, not the account and transaction concepts described in [Database concepts and upgrades](database.md).

AceEconomy 會透過 JSON 檔案或 SQL backend 儲存同一套 v2 邏輯資料。JSON 與 SQLite 位於插件資料夾內；MySQL 則透過 `storage.mysql.*` 設定網路資料庫。儲存方式會改變實體位置，但不會改變[資料庫概念與升級](database.md)所說的帳戶與交易概念。

## Choose a backend / 選擇 backend

| Backend | Where data lives / 資料位置 | Good fit / 適合情境 |
| --- | --- | --- |
| JSON | `<plugin-data>/data-v2.json` | A single server that wants one portable file. / 想使用單一可攜檔案的單一伺服器。 |
| SQLite | `<plugin-data>/<storage.sqlite.path>` | A single server that wants a local SQL database file. / 想使用本機 SQL 檔案資料庫的單一伺服器。 |
| MySQL | The configured database at `host:port/database`. / `host:port/database` 指定的資料庫。 | An installation that already operates a database service. / 已有資料庫服務的安裝。 |
| MariaDB | Configure it through `storage.type: mysql`; there is no `mariadb` value. / 透過 `storage.type: mysql` 設定，沒有 `mariadb` 值。 | A MariaDB service exposed through the MySQL-compatible connection settings. / 使用 MySQL 相容連線設定的 MariaDB 服務。 |

The backend is selected at startup. Changing `storage.type` points the plugin at another store; it does not copy old rows or merge two stores.

Backend 會在啟動時選定。修改 `storage.type` 只會讓插件指向另一個儲存位置，不會複製舊資料，也不會合併兩個儲存位置。

## JSON file / JSON 檔案

With `storage.type: json`, the plugin creates `data-v2.json` in its data folder when the file does not exist. Each mutation rewrites the model through a temporary file and a rename, so a write is committed as a complete file rather than as a partially written document.

使用 `storage.type: json` 時，若資料夾內沒有檔案，插件會建立 `data-v2.json`。每次資料變更都會先寫入暫存檔，再以重新命名方式更新正式檔案，因此寫入結果是完整檔案，不是可能被中斷的半份文件。

The JSON model contains a schema version, accounts with their balances, and transactions. Amounts are represented as decimal strings so their stored value is not changed by floating-point formatting.

JSON 模型包含 schema 版本、帳戶與各貨幣餘額，以及交易紀錄。金額以十進位字串表示，避免浮點數格式化改變儲存值。

### Safe file operations / 安全的檔案操作

- Stop the server before copying, replacing, or moving `data-v2.json`. / 複製、替換或搬移 `data-v2.json` 前先停止伺服器。
- Keep the file in the plugin data folder unless the plugin is configured to use another supported backend. / 除非改用其他支援的 backend，否則請把檔案留在插件資料夾內。
- Keep more than one dated backup and record which configuration used each backup. / 請保留多份帶日期的備份，並記錄每份備份對應的設定。
- Do not hand-edit balances or transaction records. / 不要手動編輯餘額或交易紀錄。

## SQLite file / SQLite 檔案

SQLite uses the path in `storage.sqlite.path`, resolved under the plugin data folder. The default is `data-v2.sqlite`. The parser rejects a path that resolves outside that folder, including `..` traversal and an absolute path to another root.

SQLite 使用 `storage.sqlite.path` 指定的檔案，並且會把它解析在插件資料夾下。預設值是 `data-v2.sqlite`。如果路徑解析後離開插件資料夾，包含 `..` 越界或指向其他根目錄的絕對路徑，都會被拒絕。

The SQL backend creates the v2 tables when it initializes. Schema creation and a batch of transaction rows use a database transaction. A restart reopens the same file and initializes the existing schema without duplicating the version row.

SQL backend 初始化時會建立 v2 資料表。建立 schema 與一批交易紀錄都會使用資料庫交易；重啟後會重新開啟同一個檔案，並重新初始化既有 schema，不會重複插入版本列。

For a file backup, stop the server first, copy the SQLite file, and keep the copy outside the live plugin directory. Restoring means stopping the server, placing the chosen file at the configured path, and starting the server again.

要備份檔案，請先停止伺服器，再複製 SQLite 檔案，並把備份放在正式插件資料夾以外。還原時先停止伺服器，把選定檔案放回設定的路徑，再重新啟動伺服器。

## MySQL and MariaDB / MySQL 與 MariaDB

The `mysql` backend builds `jdbc:mysql://<host>:<port>/<database>` from the YAML values and passes the credentials and pool values to HikariCP. It opens one SQL backend over the resulting connection. The plugin package includes the JDBC drivers it uses; a separate driver file is not part of the server setup described here.

`mysql` backend 會使用 YAML 值組成 `jdbc:mysql://<host>:<port>/<database>`，再把帳號、密碼與連線池設定交給 HikariCP，並在該連線上建立 SQL backend。插件套件包含所需的 JDBC driver；本文件的伺服器設定不需要另外放置 driver 檔案。

```yaml
storage:
  type: mysql
  mysql:
    host: "db.example.invalid"
    port: 3306
    database: "aceeconomy"
    username: "<database-user>"
    password: "<set-locally>"
    pool-size: 10
    max-lifetime: 1800000
```

`pool-size` must be positive and controls the maximum number of pooled connections. `max-lifetime` must be positive and is measured in milliseconds. These values are not global `storage` keys; they belong under `storage.mysql`.

`pool-size` 必須是正數，控制連線池可使用的最大連線數。`max-lifetime` 也必須是正數，單位是毫秒。這兩個值不是全域的 `storage` 設定，而是放在 `storage.mysql` 底下。

For MariaDB, keep `type: mysql` and provide the MariaDB host, port, database, account, and password. There is no separate `mariadb` branch in the configuration.

使用 MariaDB 時，請維持 `type: mysql`，填入 MariaDB 的主機、連接埠、資料庫、帳號與密碼。設定檔沒有另外的 `mariadb` 分支。

## What is safe during normal operation / 日常操作的安全邊界

### Writes and transactions / 寫入與交易

- A single transaction record is either appended or rejected; a duplicate transaction ID is not silently overwritten. / 單筆交易紀錄要嘛完整加入，要嘛被拒絕；重複交易 ID 不會被靜默覆寫。
- A batch of records commits as one database transaction. If one record fails, the batch is rolled back. / 一批紀錄會以一個資料庫交易提交；其中一筆失敗時，整批會回滾。
- Marking a transaction as reverted is safe to repeat for an existing record. / 將既有交易標記為 reverted 可以重複執行。
- A process restart reopens the same store; it does not select a different backend or migrate data. / 程序重啟會重新開啟同一個儲存位置，不會改選 backend 或搬移資料。

### Backup routine / 備份流程

1. Stop the server before copying a JSON or SQLite file. / 複製 JSON 或 SQLite 檔案前先停止伺服器。
2. For MySQL or MariaDB, use the database service's normal logical or physical backup process instead of copying a live database file. / 使用 MySQL 或 MariaDB 時，請使用資料庫服務本身的邏輯或實體備份流程，不要複製運作中的資料庫檔案。
3. Store the backup separately from the live plugin data and protect credentials in the backup location. / 將備份和正式插件資料分開存放，並保護備份位置中的憑證。
4. Record the backend type, database/file name, and backup time. / 記錄 backend 類型、資料庫或檔案名稱，以及備份時間。

### Restore routine / 還原流程

For file backends, stop the server, replace the configured file with the selected backup, and start the server. For MySQL or MariaDB, restore the selected database backup through the database service, keep the same connection settings, and then start the server.

檔案型 backend 的還原方式是停止伺服器、用選定備份替換設定的檔案，再啟動伺服器。MySQL 或 MariaDB 則透過資料庫服務還原選定的資料庫備份，保留相同的連線設定後再啟動伺服器。

The persistence layer also accepts a v2 JSON snapshot for backup and restore. It parses the snapshot and checks its schema version before replacing live rows. Invalid JSON or an incompatible snapshot leaves the current store untouched. A snapshot can move the logical v2 model between JSON and SQL backends; it is not a converter for the old v1 model.

持久化層也支援以 v2 JSON snapshot 進行備份與還原。系統會先解析 snapshot 並檢查 schema 版本，再替換正式資料；JSON 無效或版本不相容時，現有儲存內容不會被碰觸。snapshot 可以在 JSON 與 SQL backend 之間搬移 v2 邏輯模型，但不是舊版 v1 模型的轉換器。

## When startup fails / 啟動失敗時

- Check that `storage.type` is one of the three accepted values. / 先確認 `storage.type` 是三個可用值之一。
- For SQLite, check that `storage.sqlite.path` is a YAML map containing `path`, and that the resolved path stays inside the plugin data folder. / SQLite 請確認 `storage.sqlite.path` 是含有 `path` 的 YAML map，且解析後仍在插件資料夾內。
- For MySQL or MariaDB, check host, port, database, username, password, and network access. / MySQL 或 MariaDB 請確認主機、連接埠、資料庫、帳號、密碼與網路存取。
- A schema version mismatch is not repaired by changing the backend name. Take a backup, then follow the v2 upgrade guidance in [Database concepts and upgrades](database.md). / schema 版本不相容不會因修改 backend 名稱而修好。請先備份，再依[資料庫概念與升級](database.md)的 v2 升級說明處理。
