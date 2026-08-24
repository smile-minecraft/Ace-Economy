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

### Managed backup / 受管理備份

Use the canonical command to create a logical snapshot while the server is running:

```text
/aceeco backup [label]
```

The command writes a v2 JSON snapshot only under the plugin-controlled
`<plugin data folder>/backups` directory. The optional label is restricted to safe filename
characters. It creates `<backup-id>.json` with a verified directory handle and `CREATE_NEW`, forces
the complete snapshot, and then creates `<backup-id>.ready` with `CREATE_NEW`. The ready marker
contains a SHA-256 digest and is the application-level logical commit point; restore requires the
marker and a matching, fully validated snapshot. Existing target or marker files are never
replaced. The snapshot contains the logical accounts, balances, transactions, reverted markers,
and consumed nonces; it does not contain database passwords or webhook URLs. Keep the `.json` and
matching `.ready` files together when moving a snapshot; a bare JSON file is not committed.

使用以下 canonical 指令，在伺服器運作中建立邏輯 snapshot：

```text
/aceeco backup [label]
```

指令只會在插件控制的 `<plugin data folder>/backups` 下寫出 v2 JSON snapshot。選用的 label 只能使用安全的
檔名字元；服務會以已驗證的 directory handle 和 `CREATE_NEW` 建立 `<backup-id>.json`，完整寫入並 force，
再以 `CREATE_NEW` 建立 `<backup-id>.ready`。ready marker 包含 SHA-256 digest，是 application-level logical
commit point；還原時必須有 marker，且 snapshot 完整驗證並與 digest 相符。既有 target 或 marker 不會被覆寫。
snapshot 包含邏輯上的帳戶、餘額、交易、reverted marker 與已消耗 nonce，不包含資料庫密碼或 webhook URL。
搬移 snapshot 時必須保留配對的 `.json` 與 `.ready`；只有 JSON 不是已提交的備份。

There are no separate `/backup` or `/restore` root commands. These operations exist as `/aceeco`
admin subcommands. The command details and permissions are listed in [Commands and permissions](commands.md).

沒有獨立的 `/backup` 或 `/restore` 根指令；這兩個操作只以 `/aceeco` 管理子指令存在。指令與權限見
[指令與權限](commands.md)。

### Restore sequence / 還原流程

Restore is destructive and must use the exact command below from the console:

```text
/aceeco restore <backup-id> confirm
```

The command requires `aceconomy.admin` and `aceconomy.admin.restore`, rejects any online player, and
accepts only lowercase `confirm`. Before live data is touched, the service performs a read-only
preflight for JSON shape, schema version, account and transaction records, duplicate transaction
IDs, amounts and timestamps, and compatibility with the configured currencies. It then creates a
safety backup of the current state. If the safety backup fails, the restore stops and live state is
left untouched.

還原是破壞性操作，必須從主控台執行以下精確指令：

```text
/aceeco restore <backup-id> confirm
```

指令需要 `aceconomy.admin` 與 `aceconomy.admin.restore`，有任何玩家在線時會拒絕，而且只接受小寫
`confirm`。動到正式資料前，服務會先以唯讀方式檢查 JSON 結構、schema 版本、帳戶與交易紀錄、重複交易 ID、
金額與時間，以及目前設定的貨幣是否相容；接著建立目前狀態的 safety backup。安全備份失敗時會中止還原，
正式資料保持不變。

After preflight and the safety backup pass, JSON restore reads the already committed marker/target
pair through a secure directory handle; the marker protocol is not an OS atomic rename or hard-link
claim. SQLite/MySQL restore runs as one JDBC transaction. A backend failure is reported without
claiming unchanged live state. On success, the leaderboard cache is cleared, but sessions and GUIs
are not hot-refreshed; restart the server before players return.

Preflight 與 safety backup 都成功後，JSON 還原會透過 secure directory handle 讀取已提交的 marker／target
組合；這套 marker protocol 不宣稱 OS atomic rename 或 hard-link 保證。SQLite/MySQL 還原使用單一 JDBC
transaction；backend 失敗時不會宣稱正式資料已保持不變。成功後會清除排行榜快取，但不會熱刷新 session 或
GUI；讓玩家回來前必須重啟伺服器。

### Logical backend boundary / 邏輯備份的 backend 邊界

The same v2 JSON model is used for logical backup and restore across JSON, SQLite, and MySQL. This
supports logical moves such as JSON→JSON, SQLite→JSON→SQLite, and the corresponding MySQL logical
path. It is not a MySQL server-native dump: it does not replace `mysqldump`, `mariadb-dump`, or a
database administrator's physical/disaster-recovery process.

JSON、SQLite 與 MySQL 的邏輯備份／還原共用同一套 v2 JSON model，因此可做 JSON→JSON、SQLite→JSON→SQLite，
以及對應的 MySQL 邏輯 round-trip。這不是 MySQL server-native dump，不能取代 `mysqldump`、`mariadb-dump` 或
資料庫管理員的實體／災難復原流程。

The automated round-trip coverage confirms JSON→JSON and SQLite→JSON→SQLite, including accounts,
balances, transactions, reverted markers, and consumed nonces, plus backend rollback on restore
failure. No live MySQL connection or live Folia backup/restore proof has been completed; those remain
operational validation items rather than claims made by this document.

目前自動化 round-trip 覆蓋 JSON→JSON 與 SQLite→JSON→SQLite，並確認帳戶、餘額、交易、reverted marker、已消耗
nonce，以及還原失敗時 backend rollback。尚未完成 live MySQL 連線或 live Folia 備份／還原驗證；這些仍是待做的
操作驗證，不是本文已宣稱的結果。

## When startup fails / 啟動失敗時

- Check that `storage.type` is one of the three accepted values. / 先確認 `storage.type` 是三個可用值之一。
- For SQLite, check that `storage.sqlite.path` is a YAML map containing `path`, and that the resolved path stays inside the plugin data folder. / SQLite 請確認 `storage.sqlite.path` 是含有 `path` 的 YAML map，且解析後仍在插件資料夾內。
- For MySQL or MariaDB, check host, port, database, username, password, and network access. / MySQL 或 MariaDB 請確認主機、連接埠、資料庫、帳號、密碼與網路存取。
- A schema version mismatch is not repaired by changing the backend name. Take a backup, then follow the v2 upgrade guidance in [Database concepts and upgrades](database.md). / schema 版本不相容不會因修改 backend 名稱而修好。請先備份，再依[資料庫概念與升級](database.md)的 v2 升級說明處理。
