# Configuration guide / 設定指南

AceEconomy reads `config.yml` as a versioned YAML file. This page explains what each setting is for, when it takes effect, and which values are safe to use. The examples use placeholders for secrets; replace them only in the server's private copy.

AceEconomy 會把 `config.yml` 當作有版本的 YAML 設定檔讀取。本頁說明每個設定存在的原因、何時生效，以及可以使用的值。範例中的秘密都使用 placeholder，請只在伺服器自己的私有副本填入實際內容。

## Before editing / 編輯前

The file uses the v2 configuration format:

```yaml
version: "2.0"
```

Keep the YAML nesting and key names intact. A missing value receives the schema default where a default is defined. A storage type that is not `json`, `sqlite`, or `mysql` is rejected instead of silently selecting another backend.

檔案使用 v2 設定格式：

```yaml
version: "2.0"
```

請保留 YAML 的縮排與設定鍵名稱。若有定義預設值，缺少該值時會使用 schema 預設值。`storage.type` 只能使用 `json`、`sqlite` 或 `mysql`；其他值會被拒絕，不會靜默改用另一種儲存方式。

| Purpose / 用途 | Key / 設定鍵 | Default and format / 預設與格式 | Takes effect / 生效時機 |
| --- | --- | --- | --- |
| Identify the configuration format. / 指定設定檔格式。 | `version` | `"2.0"`; quoted major/minor text. / 預設 `"2.0"`；加引號的 major/minor 文字。 | When the configuration is loaded. / 設定載入時。 |

Do not replace `version: "2.0"` with the persistence schema value `1`; they describe different layers. / 請不要把 `version: "2.0"` 改成持久化 schema 的 `1`；兩者描述的是不同層級。

## Storage choice / 儲存方式

Storage determines where accounts, balances, and transaction records live. Choose it before putting the server into normal operation, because changing the backend does not itself convert existing data.

儲存方式決定帳戶、餘額與交易紀錄存放在哪裡。正式運作前就應先選定，因為切換 backend 不會自動轉換既有資料。

### `storage.type`

| Purpose / 用途 | Key / 設定鍵 | Default and format / 預設與格式 | Takes effect / 生效時機 |
| --- | --- | --- | --- |
| Select the persistence backend. / 選擇持久化 backend。 | `storage.type` | `json`; one of `json`, `sqlite`, `mysql`. / 預設 `json`，可用值為 `json`、`sqlite`、`mysql`。 | At plugin startup. Restart after changing it. / 插件啟動時讀取；修改後請重啟。 |

Use JSON for a simple single-server installation. Use SQLite when you want one local database file. Use MySQL for a server that already operates a database service or needs its data outside the plugin folder. MariaDB has no separate config value; configure a MariaDB service through the `mysql` backend.

單一伺服器、希望維護最少時可用 JSON。想使用一個本機資料庫檔案時可用 SQLite。已有資料庫服務，或希望資料放在插件資料夾以外時可用 MySQL。MariaDB 沒有獨立的設定值；MariaDB 服務請透過 `mysql` backend 設定。

### JSON

JSON is the default and stores the v2 model in one file under the plugin data folder. It needs no connection settings.

JSON 是預設方式，會在插件資料夾下以單一檔案儲存 v2 資料，不需要連線設定。

```yaml
storage:
  type: json
```

The file is `data-v2.json`. Keep it with the plugin's data directory when backing up or moving the server.

檔案名稱是 `data-v2.json`。備份或搬遷伺服器時，請把它和插件資料夾一起保留。

### SQLite

| Purpose / 用途 | Key / 設定鍵 | Default and format / 預設與格式 | Takes effect / 生效時機 |
| --- | --- | --- | --- |
| Choose the SQLite file name. / 指定 SQLite 檔案名稱。 | `storage.sqlite.path` | `data-v2.sqlite`; a relative path is resolved under the plugin data folder. / 預設 `data-v2.sqlite`；相對路徑會在插件資料夾下解析。 | At startup. Restart after changing it. / 啟動時讀取；修改後請重啟。 |

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

The path must remain inside the plugin data folder. Paths such as `../economy.sqlite` and absolute paths outside that folder are rejected. This keeps a configuration typo from selecting an unrelated file on the host.

路徑必須留在插件資料夾內。`../economy.sqlite` 這類越界路徑，以及指向其他根目錄的絕對路徑都會被拒絕，避免設定錯誤時誤選主機上的其他檔案。

### MySQL and MariaDB

`mysql` is the only SQL network backend value. It builds a JDBC connection from `host`, `port`, and `database`, then creates a HikariCP connection pool.

SQL 網路 backend 只有 `mysql` 這個設定值。系統會用 `host`、`port` 與 `database` 組成 JDBC 連線，再建立 HikariCP 連線池。

| Purpose / 用途 | Key / 設定鍵 | Default and format / 預設與格式 | Takes effect / 生效時機 |
| --- | --- | --- | --- |
| Database server name. / 資料庫主機名稱。 | `storage.mysql.host` | `localhost`; text. / 預設 `localhost`；文字。 | Startup. / 啟動時。 |
| Database server port. / 資料庫連接埠。 | `storage.mysql.port` | `3306`; integer. / 預設 `3306`；整數。 | Startup. / 啟動時。 |
| Database name. / 資料庫名稱。 | `storage.mysql.database` | `aceeconomy`; text. / 預設 `aceeconomy`；文字。 | Startup. / 啟動時。 |
| Database user. / 資料庫使用者。 | `storage.mysql.username` | `root`; text. Use a dedicated account for a live server. / 預設 `root`；文字。正式伺服器請使用專用帳號。 | Startup. / 啟動時。 |
| Database password. / 資料庫密碼。 | `storage.mysql.password` | Empty string in the shipped example; set it privately. / 隨附範例為空字串；請在私有設定中填入。 | Startup. / 啟動時。 |
| Maximum pool size. / 連線池上限。 | `storage.mysql.pool-size` | `10`; positive integer. / 預設 `10`；正整數。 | Startup. / 啟動時。 |
| Maximum connection lifetime. / 連線最長生命週期。 | `storage.mysql.max-lifetime` | `1800000`; positive milliseconds (30 minutes). / 預設 `1800000`；正整數，單位為毫秒（30 分鐘）。 | Startup. / 啟動時。 |

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

The database and user must already be available to the server. The plugin creates the v2 tables when it initializes the SQL backend; it does not use the old v1 table names as a v2 setup script. See [Database concepts and upgrades](database.md) for the data model.

資料庫與使用者必須先能讓伺服器連線。插件初始化 SQL backend 時會建立 v2 資料表；v2 不使用舊版 v1 的資料表名稱作為建庫腳本。資料模型請參考[資料庫概念與升級](database.md)。

## Economy rules / 經濟規則

These values shape new accounts and the default currency's debt policy. They are read when the economy services are built, so restart after changing them rather than assuming a reload will rebuild live services.

這些設定會影響新帳戶與預設貨幣的負債規則。它們會在經濟服務建立時讀取，因此修改後請重啟，不要假設 reload 會重新建立正在運作的服務。

| Purpose / 用途 | Key / 設定鍵 | Default and format / 預設與格式 | Notes / 注意 |
| --- | --- | --- | --- |
| Allow the balance of the default currency to go below zero. / 允許預設貨幣餘額低於零。 | `economy.allow-negative-balance` | `true`; boolean. / 預設 `true`；布林值。 | When `false`, the debt policy is disabled. / 設為 `false` 時停用負債規則。 |
| Set the default debt limit. / 設定預設負債上限。 | `economy.default-debt-limit` | `0.0`; decimal amount. / 預設 `0.0`；小數金額。 | Used when the player has no permission-specific debt setting. / 玩家沒有權限專屬負債設定時使用。 |
| Give a new account its initial amount in the default currency. / 設定新帳戶的預設金額。 | `start-balance` | `1000.0`; decimal amount. / 預設 `1000.0`；小數金額。 | Existing accounts are not reset by changing this value. / 修改後不會重設既有帳戶。 |

```yaml
economy:
  allow-negative-balance: true
  default-debt-limit: 0.0
start-balance: 1000.0
```

## Currencies / 貨幣

The current configuration defines `dollar` and `token`. Each entry gives the application a stable ID, a display name, a symbol, and a decimal scale. Exactly one entry must have `default: true`; that entry supplies the default currency used by the general economy flow and Vault integration.

目前設定檔定義 `dollar` 與 `token`。每個項目提供穩定的 ID、顯示名稱、符號與小數位數。必須且只能有一個項目使用 `default: true`；該項目是一般經濟流程與 Vault 整合使用的預設貨幣。

| Purpose / 用途 | Key / 設定鍵 | Default and format / 預設與格式 | Notes / 注意 |
| --- | --- | --- | --- |
| Human-readable name. / 給玩家看的名稱。 | `currencies.<id>.name` | `金幣` for `dollar`, `活動代幣` for `token`; text. / `dollar` 預設 `金幣`，`token` 預設 `活動代幣`；文字。 | The ID is the key below `currencies`; keep it stable once data exists. / ID 是 `currencies` 下的鍵，已有資料後請保持穩定。 |
| Display symbol. / 顯示符號。 | `currencies.<id>.symbol` | `$` for `dollar`, `ⓒ` for `token`; text. / `dollar` 預設 `$`，`token` 預設 `ⓒ`；文字。 | Used beside amounts in user-facing output. / 顯示金額時會放在數值旁。 |
| Number of fractional digits. / 小數位數。 | `currencies.<id>.scale` | `2` for `dollar`, `0` for `token`; non-negative integer. / `dollar` 預設 `2`，`token` 預設 `0`；非負整數。 | Amounts with more fractional digits are not implicitly rounded. / 超過位數的金額不會被自動四捨五入。 |
| Select the default currency. / 指定預設貨幣。 | `currencies.<id>.default` | `true` for `dollar`, `false` for `token`; boolean. / `dollar` 預設 `true`，`token` 預設 `false`；布林值。 | Keep exactly one default. / 必須維持一個預設貨幣。 |

```yaml
currencies:
  dollar:
    name: "Gold Coin"
    symbol: "$"
    scale: 2
    default: true
  token:
    name: "Event Token"
    symbol: "ⓒ"
    scale: 0
    default: false
```

Changing a name or symbol affects presentation. Changing an ID, scale, or default currency affects how later operations interpret amounts, so make a backup and plan the change before applying it to a live economy.

修改名稱或符號會影響顯示。修改 ID、小數位數或預設貨幣會影響後續操作如何解讀金額；套用到正式經濟系統前，請先備份並安排變更。

## Locale and retained command setting / 語系與保留的指令設定

| Purpose / 用途 | Key / 設定鍵 | Default and format / 預設與格式 | Takes effect / 生效時機 |
| --- | --- | --- | --- |
| Select message language. / 選擇訊息語系。 | `settings.locale` | `zh_TW`; one of `en_US`, `zh_TW`, `zh_CN`. / 預設 `zh_TW`；可用 `en_US`、`zh_TW`、`zh_CN`。 | On language load or reload. / 語言載入或 reload 時。 |
| Preserve the command setting used by the configuration format. / 保留設定格式中的指令設定。 | `settings.main-command-alias` | `aceeco`; text. / 預設 `aceeco`；文字。 | The formal root command is `/aceeco`. Changing this value does not rename or re-register that command. / 正式 root command 是 `/aceeco`；修改此值不會重新命名或註冊該指令。 |

```yaml
settings:
  locale: "en_US"
  main-command-alias: "aceeco"
```

Use `/aceeco` as the root command in server instructions and command examples. Keep `main-command-alias: "aceeco"` when the key is present; changing the value does not change the documented command usage. The language files are named `lang/en_US.yml`, `lang/zh_TW.yml`, and `lang/zh_CN.yml`. Do not put passwords or webhook URLs in language files.

伺服器操作說明與指令範例請使用 `/aceeco` 作為 root command。若檔案中保留這個設定鍵，請維持 `main-command-alias: "aceeco"`；修改該值不會改變文件中的正式指令用法。語言檔名稱為 `lang/en_US.yml`、`lang/zh_TW.yml` 與 `lang/zh_CN.yml`。請不要把密碼或 webhook URL 放進語言檔。

## Leaderboard / 排行榜

| Purpose / 用途 | Key / 設定鍵 | Default and format / 預設與格式 | Notes / 注意 |
| --- | --- | --- | --- |
| Make leaderboard features available. / 啟用排行榜功能。 | `leaderboard.enabled` | `true`; boolean. / 預設 `true`；布林值。 | Use with the selected storage backend and the server's leaderboard commands. / 請和所選儲存 backend 及伺服器上的排行榜指令一起規劃。 |
| Control how long a cached result is reused. / 控制排行榜快取重用時間。 | `leaderboard.cache-time-seconds` | `300`; integer seconds. / 預設 `300`；整數秒。 | A shorter value refreshes more often; a longer value reduces refresh work. / 值較小會更常更新，值較大會減少更新次數。 |
| Set entries per page. / 設定每頁筆數。 | `leaderboard.page-size` | `10`; integer. / 預設 `10`；整數。 | This controls the page size, not the number of stored accounts. / 這只控制每頁顯示數量，不會限制帳戶數。 |

```yaml
leaderboard:
  enabled: true
  cache-time-seconds: 300
  page-size: 10
```

## Discord and secret boundaries / Discord 與秘密邊界

| Purpose / 用途 | Key / 設定鍵 | Default and format / 預設與格式 | Notes / 注意 |
| --- | --- | --- | --- |
| Turn webhook notifications on or off. / 開啟或關閉 webhook 通知。 | `discord.enabled` | `false`; boolean. / 預設 `false`；布林值。 | Keep it `false` until a private endpoint is configured. / 尚未設定私有 endpoint 前請維持 `false`。 |
| Identify the Discord webhook endpoint. / 指定 Discord webhook endpoint。 | `discord.webhook-url` | Empty string by default; URL text. / 預設空字串；URL 文字。 | Treat the complete URL as a credential. / 完整 URL 應視為憑證。 |

```yaml
discord:
  enabled: false
  webhook-url: "https://discord.com/api/webhooks/<set-locally>"
```

Never publish a real webhook URL, database password, or connection details with credentials. Do not paste them into issue reports or shared examples. If a secret is exposed, replace it at the provider and update the private configuration.

請勿公開真實 webhook URL、資料庫密碼，或含有憑證的連線資訊。也不要把它們貼到 issue 或共用範例中。若秘密已外洩，請先在服務供應商端更換，再更新伺服器的私有設定。

## Applying changes / 套用變更

1. Stop the server before changing storage paths or connection settings. / 修改儲存路徑或連線設定前先停止伺服器。
2. Make a copy of the relevant data file or database backup. / 複製相關資料檔或建立資料庫備份。
3. Edit `config.yml` without changing the YAML structure. / 編輯 `config.yml`，不要改變 YAML 結構。
4. Start the server and check the startup log for configuration or connection errors. / 啟動伺服器，查看啟動日誌是否有設定或連線錯誤。

The administrative reload action reloads the configuration and language snapshots. It does not move data between backends or reopen the storage backend; use a restart for storage changes and for settings that are captured while services are created.

管理員 reload 操作會重新載入設定與語言快照，但不會在 backend 之間搬移資料，也不會重新開啟儲存 backend。修改儲存設定，以及在服務建立時讀取的設定，請使用重啟。
