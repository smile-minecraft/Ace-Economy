# 設定指南

[English](config.md) · [简体中文](config.zh-CN.md) · 繁體中文

這份指南寫給第一次接手伺服器的管理員。你改的是 `plugins/AceEconomy/config.yml`。看完應該知道每個設定管什麼、不改會怎樣、改完要不要重啟、填錯會看到什麼。範例裡的密碼和網址都是假的佔位文字，只填在伺服器本機那份。

## 目錄

- [編輯前](#編輯前)
- [儲存方式](#儲存方式)
- [經濟規則](#經濟規則)
- [貨幣](#貨幣)
- [語系與保留的指令設定](#語系與保留的指令設定)
- [排行榜](#排行榜)
- [銀行介面版面](#銀行介面版面)
- [提領逾時](#提領逾時)
- [Discord 與秘密邊界](#discord-與秘密邊界)
- [套用變更](#套用變更)

## 編輯前

檔案使用 v2 設定格式：

```yaml
version: "2.0"
```

這是 v2 才認得的格式標記。縮排和設定鍵名稱要原樣保留，打錯一個字插件就讀不懂。

| 用途 | 設定鍵 | 預設與格式 | 生效時機 |
| --- | --- | --- | --- |
| 指定設定檔格式。 | `version` | `"2.0"`；加引號的 major/minor 文字。 | 設定載入時。 |

填錯會怎樣：`storage.type` 只認 `json`、`sqlite`、`mysql`。填其他值會直接被拒絕，不會幫你換成別種。缺了有預設值的鍵，會用 schema 預設值補上。

請不要把 `version: "2.0"` 改成持久化 schema 的 `1`；兩者描述的是不同層級。前者說設定檔格式，後者說資料本身的版本。

## 儲存方式

儲存方式決定帳戶、餘額和交易紀錄放在哪裡。開服前先選好。換了後端，舊資料不會自己搬過去。

### `storage.type`

這是整份設定最重要的開關，決定錢記在哪裡。

| 用途 | 設定鍵 | 預設與格式 | 生效時機 |
| --- | --- | --- | --- |
| 選擇持久化後端。 | `storage.type` | `json`；可用值為 `json`、`sqlite`、`mysql`。 | 插件啟動時讀取；修改後請重啟。 |

不改會怎樣：預設 `json`，適合只有一台伺服器、想省事的情況。想要一個本機資料庫檔案再選 SQLite。已經有資料庫服務，或想把資料放在插件資料夾外面，才選 MySQL。MariaDB 沒有自己的設定值，連 MariaDB 也是填 `mysql`。

改完要重啟。只做重新載入不會換後端。

### JSON

JSON 是預設做法。資料寫在插件資料夾下一個檔案裡，不用填連線資訊。

```yaml
storage:
  type: json
```

檔案名稱是 `data-v2.json`。備份或搬家時，把它跟插件資料夾一起留好。

### SQLite

SQLite 也是放在本機，只是一個資料庫檔案。

| 用途 | 設定鍵 | 預設與格式 | 生效時機 |
| --- | --- | --- | --- |
| 指定 SQLite 檔案名稱。 | `storage.sqlite.path` | `data-v2.sqlite`；相對路徑會在插件資料夾下解析。 | 啟動時讀取；修改後請重啟。 |

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

路徑一定要留在插件資料夾裡面。`../economy.sqlite` 這種往外跳的寫法會被拒絕，指向別處的絕對路徑也不行。這是為了避免打錯字就選到主機上不相關的檔案。改完要重啟。

### MySQL 與 MariaDB

SQL 網路後端只有 `mysql` 這個設定值。插件拿 `host`、`port`、`database` 組出 JDBC 連線，再開一個 HikariCP 連線池。

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

資料庫和帳號要先能連上。插件啟動時會自己建 v2 需要的資料表。資料長什麼樣子，請看[資料庫概念與升級](database.zh-TW.md)。這整組改完都要重啟。

## 經濟規則

這裡管新帳號一進來有多少錢，以及預設貨幣能不能欠錢。插件在建立經濟服務時讀一次，改完要重啟。只做重新載入不會重建服務。

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

白話說：`start-balance` 是新玩家第一次出現時手上的錢，舊帳號不會被洗掉。`allow-negative-balance` 關掉就不能欠錢。`default-debt-limit` 是沒有特別權限的玩家最多能欠到多少。

## 貨幣

`currencies` 區塊由伺服器管理者自行定義。隨附檔案寫了 `dollar` 和 `token`，照同樣格式可以再加。插件會讀這個區塊裡所有合法的組合。每個貨幣有固定的 ID、顯示名稱、符號和小數位數。整個區塊必須有一個且只有一個 `default: true`，那就是平常流程和 Vault 整合用的預設貨幣。

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

每個項目都要通過這幾條檢查：

- 貨幣 ID 就是 `currencies` 下面的鍵。去掉前後空白並轉成小寫後，只能用 `a-z`、`0-9` 和 `_`。只差大小寫或空白的 ID（例如 `Dollar` 和 `dollar`）算同一個，會因為重複被拒絕。
- 每個項目四個欄位都要寫對型別。缺欄位或型別錯誤（例如 `scale` 加了引號變成文字、`default` 寫成 `"true"`）都會被拒絕。
- 整個區塊至少要有一個貨幣，而且預設貨幣剛好一個。

填錯會怎樣：開服時插件會停下來，並在錯誤訊息裡指出哪裡錯了，不會有改一半的狀態。改名稱或符號只影響顯示。改 ID、小數位數或預設貨幣會改變以後金額怎麼讀，動正式服之前先備份並排好時間。

重新載入會先看候選的 `currencies` 屬於哪一種，確認安全才會動到線上設定：

| 變更 | 重新載入怎麼做 | 為什麼 |
| --- | --- | --- |
| 沒變 | 重新載入成功，什麼都不換。 | 沒有東西要套用。 |
| 只改 `name` 或 `symbol` | 直接熱套用，新顯示文字立刻全服生效。 | 顯示文字不影響已存金額、小數位數與預設貨幣。 |
| 新增貨幣 | 重新載入拒絕並告知新增的 ID，伺服器沿用舊組合。請重啟才會生效。 | 既有帳戶需要批次初始化並支援回溯，目前沒有儲存後端能做到。 |
| 移除貨幣，或改 `scale`、改 `default` | 重新載入拒絕並告知受影響的 ID，伺服器沿用舊組合。請重啟才會生效。 | 這些變更會讓已存餘額被誤讀或變成孤兒。 |
| 候選區塊格式無效 | 重新載入拒絕並告知解析原因，什麼都不換。 | 讀不懂的候選設定絕不能取代線上登錄表。 |

被拒絕的重新載入不會動到運作中的伺服器：把檔案改回來，或為結構變更排一次重啟，再執行一次 `/aceeco reload`。只有純顯示變更能在不重啟的情況下更新登錄表、指令、Vault 橋接與 placeholder 顯示。

## 語系與保留的指令設定

| 用途 | 設定鍵 | 預設與格式 | 生效時機 |
| --- | --- | --- | --- |
| 選擇訊息語系。 | `settings.locale` | `zh_TW`；可用 `en_US`、`zh_TW`、`zh_CN`。 | 語言載入或重新載入時。 |
| 管理指令的額外標籤。 | `settings.main-command-alias` | `aceeco`；文字，限 `a-z`、`0-9`、`-`、`_`。 | 只在啟動時生效；修改後請重啟。 |

```yaml
settings:
  locale: "en_US"
  main-command-alias: "aceeco"
```

正式主指令是 `/aceeco`。把 `settings.main-command-alias` 設成其他值時，插件會在啟動時把那個標籤掛為同一個管理指令在 AceEconomy 指令登錄表裡的額外別名。留空或只打空白會維持預設入口。

這個設定有兩條紅線：

1. **衝突拒絕。** 設定值不能跟插件已在 `plugin.yml` 宣告的任何指令標籤（主指令：`money`、`pay`、`aceeco`、`withdraw`、`baltop`、`bank`；別名：如 `balance`、`bal`、`balancetop`、`top`、`menu`、`bankmenu`）或其他 AceEconomy 指令名稱撞名。撞名會讓插件在啟動時停下來並報錯，不會蓋掉原來的入口；這個設定永遠搶不走 `/bank` 這類既有指令。
2. **Bukkit 標籤是靜態的。** 伺服器只會轉送 `plugin.yml` 裡寫好的指令標籤，那個檔案跟著版本發布。自訂別名會先檢查，也能在 AceEconomy 自己的指令分派器裡認得，但要在遊戲裡打了真的送到插件，那個標籤必須同時寫在 `plugin.yml` 的主指令或別名裡；v2.1.0 不會在執行時期註冊新的 Bukkit 指令。改這個值一定要重啟，重新載入不會重新註冊指令。

語言檔是 `lang/en_US.yml`、`lang/zh_TW.yml` 和 `lang/zh_CN.yml`。不要把密碼或 webhook 網址放進語言檔。

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

`enabled: false` 的意思是排行榜整個關掉。插件不會在自己的登錄表裡建立 baltop 指令，所以不會跑任何經濟程式碼。但 `plugin.yml` 裡的 `baltop` 標籤還在，伺服器只會回一句用法說明；要讓標籤徹底消失，得等改了 `plugin.yml` 的新版本。這個開關只在啟動時讀一次，改完要重啟。

`cache-time-seconds` 是排行榜多久重算一次，預設 300 秒。`page-size` 是一頁顯示幾筆，預設 10 筆。

## 銀行介面版面

`bank-gui` 區塊控制 `/bank` 介面：標題、大小，以及每個格子按下去做什麼。出貨預設就是以前固定的樣子（存款 slot 4、提領 100 slot 11、提領 500 slot 13、關閉 slot 15、大小 27）。

| 用途 | 設定鍵 | 預設與格式 | 注意 |
| --- | --- | --- | --- |
| 開啟或關閉銀行介面。 | `bank-gui.enabled` | `true`；布林值。 | `false` 時 `/bank` 不做任何事。修改後請重啟。 |
| 標題語言鍵。 | `bank-gui.title-key` | `gui.bank-title`；非空白語言鍵。 | 經安全 Component 管線算繪，絕不按 raw MiniMessage 解析。 |
| 背包大小。 | `bank-gui.size` | `27`；只能是 `9`、`18`、`27`、`36`、`45`、`54`。 | 所有按鈕槽位必須落在 `[0, size)` 內。 |
| 按鈕槽位。 | `bank-gui.actions.<name>.slot` | 整數；每個動作必填。 | 整個區塊內不可重複。 |
| 按鈕行為。 | `bank-gui.actions.<name>.type` | `deposit`、`withdraw`、`close`、`none` 四選一。 | `none` 只佔位，不綁動作。 |
| 提領面額。 | `bank-gui.actions.<name>.amount` | 正整數；`withdraw` 必填。 | 其他類型不得填寫。 |
| 提領幣別。 | `bank-gui.actions.<name>.currency` | 已知幣別 ID；`withdraw` 必填。 | 缺省為執行時期預設幣別。其他類型不得填寫。 |
| 按鈕顯示物品。 | `bank-gui.actions.<name>.material` | 合法 Bukkit 材質名稱；除 `none` 外必填。 | air 會被拒絕。 |
| 按鈕顯示文字。 | `bank-gui.actions.<name>.name-key` / `lore-keys` | 語言鍵；除 `none` 外 name 必填，lore 可選。 | 管理員輸入絕不會被當成 MiniMessage 解析。 |

```yaml
bank-gui:
  enabled: true
  title-key: "gui.bank-title"
  size: 27
  actions:
    deposit:
      slot: 4
      type: deposit
      material: "CHEST"
      name-key: "gui.bank-deposit-name"
      lore-keys: ["gui.bank-deposit-lore"]
    withdraw100:
      slot: 11
      type: withdraw
      amount: 100
      currency: dollar
      material: "PAPER"
      name-key: "gui.bank-withdraw-name"
      lore-keys: ["gui.bank-withdraw-lore"]
    withdraw500:
      slot: 13
      type: withdraw
      amount: 500
      currency: dollar
      material: "PAPER"
      name-key: "gui.bank-withdraw-name"
      lore-keys: ["gui.bank-withdraw-lore"]
    close:
      slot: 15
      type: close
      material: "BARRIER"
      name-key: "gui.bank-close-name"
      lore-keys: []
```

填錯會怎樣：啟動時插件會停下來，並在錯誤裡寫出精確路徑（例如 `bank-gui.actions.withdraw100.amount`），不會有改一半的狀態。沒有 `bank-gui` 的舊設定在 schema `2.0` 下照樣能讀，會沿用舊版格子。

版面改完不用重啟，做一次重新載入就會換。換的時候會先關掉大家手上開著的銀行介面，避免點擊卡在新舊規則中間；之後重開的介面就照新規矩。版面寫錯會讓整筆重新載入被拒絕，舊設定原封不動。只有 `bank-gui.enabled` 這個開關要在啟動時讀，改了要重啟。

## 提領逾時

`/withdraw` 是把支票交到玩家手上。伺服器會先排一個工作，等玩家所在的區域執行緒有空再執行。如果玩家在工作輪到他之前就離線，這個排好的工作就不會做了。

`withdraw.dispatch-timeout-seconds` 就是在問：這種等不到的工作，最多等幾秒。超過就不再等，直接收掉，避免玩家錢被扣了卻沒拿到支票。正常提領不受影響，工作一做就結束。

| 用途 | 設定鍵 | 預設與格式 | 注意 |
| --- | --- | --- | --- |
| 玩家離線、工作做不成時最多等幾秒。 | `withdraw.dispatch-timeout-seconds` | `10`；正整數秒。 | 啟動時讀取一次；修改後請重啟。 |

兩種等不到的情況，插件都會站在保住玩家錢的那一邊。如果是檢查背包空間那一步一直沒輪到，提領會在扣錢之前直接拒絕。如果是錢已經扣了、送支票那一步一直沒輪到，扣掉的錢會自動退回去，指令也會回一個明確的失敗，不會讓玩家卡在沒回應的畫面，更不會扣了錢卻沒拿到支票。`0` 或負數會在讀設定時改成 `1` 秒，填錯不會讓所有提領立刻失敗；填正數就照你填的秒數等。

```yaml
withdraw:
  dispatch-timeout-seconds: 10
```

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

webhook 網址跟資料庫密碼一樣，都算鑰匙。不要貼到 issue 或共用範例裡。如果已經外流，先去服務供應商那邊換掉，再回來改伺服器本機的設定。

## 套用變更

1. 修改儲存路徑或連線設定前先停止伺服器。
2. 複製相關資料檔或建立資料庫備份。
3. 編輯 `config.yml`，不要改變 YAML 結構。
4. 啟動伺服器，查看啟動日誌是否有設定或連線錯誤。

管理員重新載入操作會重新載入設定與語言快照，但不會在後端之間搬移資料，也不會重新開啟儲存後端。在服務建立時讀取的設定——儲存方式、`settings.main-command-alias`、`leaderboard.enabled`——會維持啟動時的值直到下次重啟，重新載入只會在回報中提醒重啟，不會偷偷套用；重新載入也不會重新註冊指令。貨幣的顯示變更（`name`、`symbol`）會熱套用，包含 Vault 橋接與 placeholder 顯示；結構變更會被拒絕，請重啟後才會生效。
