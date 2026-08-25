# 設定指南

[English](config.md) · [简体中文](config.zh-CN.md) · 繁體中文

AceEconomy 會把 `config.yml` 當作有版本的 YAML 設定檔讀取。本頁說明每個設定存在的原因、何時生效，以及可以使用的值。範例中的秘密都使用預留位置，請只在伺服器自己的私有副本填入實際內容。

## 目錄

- [編輯前](#編輯前)
- [儲存方式](#儲存方式)
- [經濟規則](#經濟規則)
- [貨幣](#貨幣)
- [語系與保留的指令設定](#語系與保留的指令設定)
- [排行榜](#排行榜)
- [Discord 與秘密邊界](#discord-與秘密邊界)
- [套用變更](#套用變更)

## 編輯前

檔案使用 v2 設定格式：

```yaml
version: "2.0"
```

請保留 YAML 的縮排與設定鍵名稱。若有定義預設值，缺少該值時會使用 schema 預設值。`storage.type` 只能使用 `json`、`sqlite` 或 `mysql`；其他值會被拒絕，不會靜默改用另一種儲存方式。

| 用途 | 設定鍵 | 預設與格式 | 生效時機 |
| --- | --- | --- | --- |
| 指定設定檔格式。 | `version` | `"2.0"`；加引號的 major/minor 文字。 | 設定載入時。 |

請不要把 `version: "2.0"` 改成持久化 schema 的 `1`；兩者描述的是不同層級。

## 儲存方式

儲存方式決定帳戶、餘額與交易紀錄存放在哪裡。正式運作前就應先選定，因為切換後端不會自動轉換既有資料。

### `storage.type`

| 用途 | 設定鍵 | 預設與格式 | 生效時機 |
| --- | --- | --- | --- |
| 選擇持久化後端。 | `storage.type` | `json`；可用值為 `json`、`sqlite`、`mysql`。 | 插件啟動時讀取；修改後請重啟。 |

單一伺服器、希望維護最少時可用 JSON。想使用一個本機資料庫檔案時可用 SQLite。已有資料庫服務，或希望資料放在插件資料夾以外時可用 MySQL。MariaDB 沒有獨立的設定值；MariaDB 服務請透過 `mysql` 後端設定。

### JSON

JSON 是預設方式，會在插件資料夾下以單一檔案儲存 v2 資料，不需要連線設定。

```yaml
storage:
  type: json
```

檔案名稱是 `data-v2.json`。備份或搬遷伺服器時，請把它和插件資料夾一起保留。

### SQLite

| 用途 | 設定鍵 | 預設與格式 | 生效時機 |
| --- | --- | --- | --- |
| 指定 SQLite 檔案名稱。 | `storage.sqlite.path` | `data-v2.sqlite`；相對路徑會在插件資料夾下解析。 | 啟動時讀取；修改後請重啟。 |

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

路徑必須留在插件資料夾內。`../economy.sqlite` 這類越界路徑，以及指向其他根目錄的絕對路徑都會被拒絕，避免設定錯誤時誤選主機上的其他檔案。

### MySQL 與 MariaDB

SQL 網路後端只有 `mysql` 這個設定值。系統會用 `host`、`port` 與 `database` 組成 JDBC 連線，再建立 HikariCP 連線池。

| 用途 | 設定鍵 | 預設與格式 | 生效時機 |
| --- | --- | --- | --- |
| 資料庫主機名稱。 | `storage.mysql.host` | `localhost`；文字。 | 啟動時。 |
| 資料庫連接埠。 | `storage.mysql.port` | `3306`；整數。 | 啟動時。 |
| 資料庫名稱。 | `storage.mysql.database` | `aceeconomy`；文字。 | 啟動時。 |
| 資料庫使用者。 | `storage.mysql.username` | `root`；文字。正式伺服器請使用專用帳號。 | 啟動時。 |
| 資料庫密碼。 | `storage.mysql.password` | 隨附範例為空字串；請在私有設定中填入。 | 啟動時。 |
| 連線池上限。 | `storage.mysql.pool-size` | `10`；正整數。 | 啟動時。 |
| 連線最長生命週期。 | `storage.mysql.max-lifetime` | `1800000`；正整數，單位為毫秒（30 分鐘）。 | 啟動時。 |

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

資料庫與使用者必須先能讓伺服器連線。插件初始化 SQL 後端時會建立 v2 資料表；v2 不使用舊版 v1 的資料表名稱作為建庫腳本。資料模型請參考[資料庫概念與升級](database.zh-TW.md)。

## 經濟規則

這些設定會影響新帳戶與預設貨幣的負債規則。它們會在經濟服務建立時讀取，因此修改後請重啟，不要假設 reload 會重新建立正在運作的服務。

| 用途 | 設定鍵 | 預設與格式 | 注意 |
| --- | --- | --- | --- |
| 允許預設貨幣餘額低於零。 | `economy.allow-negative-balance` | `true`；布林值。 | 設為 `false` 時停用負債規則。 |
| 設定預設負債上限。 | `economy.default-debt-limit` | `0.0`；小數金額。 | 玩家沒有權限專屬負債設定時使用。 |
| 設定新帳戶的預設金額。 | `start-balance` | `1000.0`；小數金額。 | 修改後不會重設既有帳戶。 |

```yaml
economy:
  allow-negative-balance: true
  default-debt-limit: 0.0
start-balance: 1000.0
```

## 貨幣

`currencies` 區塊由伺服器管理者自行定義。隨附檔案定義了 `dollar` 與 `token`，並可用同樣方式新增其他貨幣；插件會載入區塊中任何合法的組合。每個項目提供穩定的 ID、顯示名稱、符號與小數位數。必須且只能有一個項目使用 `default: true`；該項目是一般經濟流程與 Vault 整合使用的預設貨幣。

| 用途 | 設定鍵 | 預設與格式 | 注意 |
| --- | --- | --- | --- |
| 給玩家看的名稱。 | `currencies.<id>.name` | 文字；每個貨幣必填。 | ID 是 `currencies` 下的鍵，已有資料後請保持穩定。 |
| 顯示符號。 | `currencies.<id>.symbol` | 文字；每個貨幣必填。 | 顯示金額時會放在數值旁。 |
| 小數位數。 | `currencies.<id>.scale` | 非負整數；每個貨幣必填。 | 超過位數的金額不會被自動四捨五入。 |
| 指定預設貨幣。 | `currencies.<id>.default` | 布林值；整個區塊只能有一個 `true`。 | 必須維持一個預設貨幣。 |

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
  gem:
    name: "Gem"
    symbol: "*"
    scale: 1
    default: false
```

每個項目都適用以下驗證規則：

- 貨幣 ID 是 `currencies` 下的鍵。去除前後空白並轉為小寫後，只能使用 `a-z`、`0-9` 與 `_`。只有大小寫或空白差異的 ID（例如 `Dollar` 與 `dollar`）視為同一貨幣，會以重複為由拒絕。
- 每個項目都必須以正確型別定義四個欄位；缺欄位或型別錯誤（例如 `scale` 寫成加引號的文字、`default` 寫成 `"true"`）都會被拒絕。
- 區塊至少要有一個貨幣，且恰好一個預設貨幣。

違反這些規則的設定會讓插件在啟動時停止，並留下指出問題的錯誤訊息；不會有半套用的狀態。修改名稱或符號會影響顯示。修改 ID、小數位數或預設貨幣會影響後續操作如何解讀金額；套用到正式經濟系統前，請先備份並安排變更。

貨幣組合只在啟動時讀取一次。管理員 reload 操作會更新設定快照，但不會重建運行中的登錄表、指令、Vault 橋接或 placeholder 擴充；新增、移除或修改貨幣請重啟後才會生效。

## 語系與保留的指令設定

| 用途 | 設定鍵 | 預設與格式 | 生效時機 |
| --- | --- | --- | --- |
| 選擇訊息語系。 | `settings.locale` | `zh_TW`；可用 `en_US`、`zh_TW`、`zh_CN`。 | 語言載入或 reload 時。 |
| 管理指令的額外標籤。 | `settings.main-command-alias` | `aceeco`；文字，限 `a-z`、`0-9`、`-`、`_`。 | 只在啟動時生效；修改後請重啟。 |

```yaml
settings:
  locale: "en_US"
  main-command-alias: "aceeco"
```

正式主指令是 `/aceeco`。把 `settings.main-command-alias` 設成其他值時，插件會在啟動時把該標籤掛為同一個管理指令在 AceEconomy 指令登錄表內的額外別名。空值或空白會維持預設入口。

此設定有兩個邊界：

1. **衝突拒絕。** 設定值不得與插件已在 `plugin.yml` 宣告的任何指令標籤（主指令：`money`、`pay`、`aceeco`、`withdraw`、`baltop`、`bank`；別名：如 `balance`、`bal`、`balancetop`、`top`、`menu`、`bankmenu`）或其他 AceEconomy 指令名稱衝突。衝突會讓插件在啟動時以明確錯誤停止，而不是覆蓋既有入口；這個設定永遠無法搶走 `/bank` 等既有指令。
2. **Bukkit 標籤是靜態的。** 伺服器只會轉送 `plugin.yml` 中宣告的指令標籤，而該檔案隨版本固定發布。自訂別名會經過驗證並可在 AceEconomy 的指令分派器內解析，但要在遊戲內實際輸入該標籤抵達插件，前提是該標籤也已宣告在 `plugin.yml` 的主指令／別名中；v2.1.0 不會在執行期註冊新的 Bukkit 指令。修改此值一律需要重啟，reload 不會重新註冊指令。

語言檔名稱為 `lang/en_US.yml`、`lang/zh_TW.yml` 與 `lang/zh_CN.yml`。請不要把密碼或 webhook 網址放進語言檔。

## 排行榜

| 用途 | 設定鍵 | 預設與格式 | 注意 |
| --- | --- | --- | --- |
| 啟用排行榜功能。 | `leaderboard.enabled` | `true`；布林值。 | `false` 會在啟動時移除可執行的 `/baltop` 處理器；指令標籤本身仍靜態存在於 `plugin.yml`。修改後請重啟。 |
| 控制排行榜快取重用時間。 | `leaderboard.cache-time-seconds` | `300`；整數秒。 | 值較小會更常更新，值較大會減少更新次數。 |
| 設定每頁筆數。 | `leaderboard.page-size` | `10`；整數。 | 這只控制每頁顯示數量，不會限制帳戶數。 |

```yaml
leaderboard:
  enabled: true
  cache-time-seconds: 300
  page-size: 10
```

設定 `enabled: false` 時，插件不會在其登錄表建立或掛上 baltop 指令規格，因此不會執行任何經濟程式碼。由於 `plugin.yml` 仍宣告靜態的 `baltop` 標籤，伺服器只會回應單純的用法說明；要完全移除該標籤需要變更 `plugin.yml` 的新版本。此開關只在啟動時讀取；修改後請重啟。

## Discord 與秘密邊界

| 用途 | 設定鍵 | 預設與格式 | 注意 |
| --- | --- | --- | --- |
| 開啟或關閉 webhook 通知。 | `discord.enabled` | `false`；布林值。 | 尚未設定私有 endpoint 前請維持 `false`。 |
| 指定 Discord webhook 端點。 | `discord.webhook-url` | 預設空字串；URL 文字。 | 完整 URL 應視為憑證。 |

```yaml
discord:
  enabled: false
  webhook-url: "https://discord.com/api/webhooks/<set-locally>"
```

請勿公開真實 webhook 網址、資料庫密碼，或含有憑證的連線資訊。也不要把它們貼到 issue 或共用範例中。若秘密已外洩，請先在服務供應商端更換，再更新伺服器的私有設定。

## 套用變更

1. 修改儲存路徑或連線設定前先停止伺服器。
2. 複製相關資料檔或建立資料庫備份。
3. 編輯 `config.yml`，不要改變 YAML 結構。
4. 啟動伺服器，查看啟動日誌是否有設定或連線錯誤。

管理員 reload 操作會重新載入設定與語言快照，但不會在後端之間搬移資料，也不會重新開啟儲存後端。在服務建立時讀取的設定——儲存方式、貨幣、`settings.main-command-alias`、`leaderboard.enabled`——會維持啟動時的值直到下次重啟；reload 不會重建貨幣登錄表、不會重新註冊指令，也不會重接 Vault 與 placeholder 整合。
