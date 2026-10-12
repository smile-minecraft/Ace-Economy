# AceEconomy 在地化

[English](localization.md) · [简体中文](localization.zh-CN.md) · 繁體中文

當你需要變更伺服器語言、修改翻譯、維持 v2 的鍵命名空間，或者重新載入結果時，請使用本指南。AceEconomy v2 使用 `lang/<locale>.yml`；內建範例是 `en_US`、`zh_TW` 與 `zh_CN`。

## 目錄

- [檔案位置與語系](#檔案位置與語系)
- [v2 訊息的寫法](#v2-訊息的寫法)
- [內建鍵對照](#內建鍵對照)
- [變更目前語言](#變更目前語言)
- [安全地修改翻譯](#安全地修改翻譯)
- [重新載入與復原](#重新載入與復原)
- [相關指南](#相關指南)

## 檔案位置與語系

首次啟動之後，請編輯插件資料夾裡的檔案：

```text
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
```

在 `plugins/AceEconomy/config.yml` 裡選擇語系：

```yaml
settings:
  locale: zh_TW
```

v2 的檔案使用 `lang/<locale>.yml`。舊版的 `messages_<locale>.yml` 屬於上一代的檔案配置，已標示為停用（僅供參考），v2 訊息管線不會讀取這些檔案；編輯 v2 訊息時請只修改 `lang/<locale>.yml`。

首次啟動時，設定轉接器會透過 `JavaPlugin.saveResource("lang/" + fileName, false)` 置備三份標準 `lang/<locale>.yml` 資源。任一標準資源的置備失敗（拋出 `IOException`/權限/`RuntimeException`）會直接失敗：設定轉接器會發出脫敏的 `WARNING`（`Failed to ensure lang resource {0}: {1}`，敏感值以 `[redacted sensitive value]` 替代，絕不回顯原始訊息），並以非敏感的 `IllegalStateException` 中止初次 `load()`；不會繼續到 `ConfigManager`/`LangManager` 載入，也不會回落到預設語系。已存在的檔案（`saveResource(..., false)` 不拋例外）不視為失敗。

## v2 訊息的寫法

語言檔使用三種語法：

- **鍵的命名空間：** 像 `general.invalid-amount` 與 `economy.payment-sent` 這類帶點的 YAML 路徑。保留命名空間與鍵名，只翻譯值。
- **變數佔位符：** 變數寫成 `{placeholder}`，例如 `{amount}`、`{player}`、`{balance}`、`{currency_name}`、`{issuer}` 與 `{status}`。保留大括號與佔位符名稱。動態值必須使用 `{name}`，不要使用 `<currency_name>`、`<amount>` 或 `<issuer>` 這類尖括號形式（舊的 `<...>` 動態形式會被資源合約測試拒絕）。
- **MiniMessage：** 用 `<red>`、`<yellow>`、`<aqua>`、`<green>`、與 `</red>` 這類標籤來處理顯示。標籤會在變數代換之後解析，不要改成舊式的顏色代碼。
- **指令字面量：** 當 help 或 usage 行需要展示範例參數時，把尖括號寫成脫逸字面量 `\<player>` 與 `\<amount>`，MiniMessage 會渲染為字面 `<player>` 括號。範例（單引號 YAML 會保留反斜線）：

```yaml
admin:
  help-pay: '<white>/pay \<player> \<amount></white> <gray>- 轉帳給其他玩家</gray>'
  help-withdraw: '<white>/withdraw \<amount></white> <gray>- 將餘額提款為支票</gray>'
```

動態值如 `economy.balance-check-currency` 與 `economy.withdraw-redeem` 使用 `{currency_name}` 與 `{issuer}`：

```yaml
economy:
  balance-check: "Your balance: <yellow>{balance}</yellow>"
  balance-check-currency: "Your {currency_name} balance: <yellow>{balance}</yellow>"
  payment-sent: "<green>Sent <yellow>{amount}</yellow> to <aqua>{player}</aqua>!</green>"
  withdraw-redeem: "<green>Redeemed banknote: <yellow>{amount}</yellow> <gray>(Issuer: {issuer})</gray></green>"
```

### 內建鍵對照

| 命名空間 | 用途 | 鍵範例 |
| --- | --- | --- |
| `message` | 訊息共用的前綴 | `message.prefix` |
| `general` | 一般錯誤與狀態 | `general.no-permission`、`general.status` |
| `economy` | 餘額與付款 | `economy.balance-check`、`economy.payment-received` |
| `admin` | 管理員操作回饋 | `admin.give` |
| `command` | 指令用法與錯誤 | `command.usage-pay`、`command.invalid-uuid` |
| `error` | 系統層級錯誤診斷 | `error.missing-key`、`error.injection-detected` |
| `gui` | 銀行 GUI 標籤與提示 | `gui.bank-title`、`gui.input-request` |
| `banknote` | 支票物品文字 | `banknote.name`、`banknote.redeem-success` |

## 變更目前語言

1. 開啟 `plugins/AceEconomy/config.yml`。
2. 把完整路徑 `settings.locale`（不是裸的 `locale`）設成對應的標準檔名，例如 `en_US`、`zh_TW` 或 `zh_CN`。僅這三個值受支援；其他值會被拒絕並保留上一份有效語系。
3. 儲存檔案。
4. 從伺服器主控台執行 `/aceeco reload`，或者重新啟動伺服器。

重新載入會在同一個設定轉接器的鎖內再次讀取設定與語系快照。有效修改會在下一則用到該鍵的訊息中以新語言顯示。目前語系由 `config.yml` 經 `ConfigLangAdapter` 與 `LangManager` 解析；建構時的預設值（`zh_TW`）僅在首次載入前 `settings.locale` 缺失時作為回退——若值無效（非 `en_US`/`zh_TW`/`zh_CN`），首次 `load()` 會直接失敗，不會退回預設語系；`reload()` 則保留上一份有效快照。

所有渲染都使用這個全域語系。設定轉接器經由單一插件作用域的訊息範圍渲染，其解析器固定為目前語系——即使玩家的客戶端語系不同，看到的仍是同一種語言；刻意不跟隨個別玩家的語系。若渲染時指定了明確語系，`message.prefix` 取自該語系（該語系沒有前綴時退回預設語系）；缺鍵仍回傳 `Missing translation: <key>` 並記錄 `WARNING`。

## 安全地修改翻譯

請從要編輯的語系所對應的 v2 檔案開始，只修改 YAML 的值：

```yaml
general:
  invalid-amount: "<red>Invalid amount: <white>{amount}</white></red>"
  player-not-found: "<red>Player not found: <white>{player}</white></red>"
```

翻譯時請注意：

- 保留縮排，並確保 YAML 的引號有效；
- 保留原訊息需要的每一個 `{placeholder}`；
- MiniMessage 的開始與結束標籤必須成對；
- `invalid-amount` 這類鍵名保持英文，即使值已經翻譯。

維護某種語系時，請保留內建資源的結構。內建資源集是所需命名空間與佔位符名稱的參考來源。

## 重新載入與復原

在主控台執行 `/aceeco reload` 時，插件會原子化載入設定與語系資源。整個過程分為三階段驗證，任一階段失敗皆保留原快照，絕不套用殘缺設定：

### 1. 設定檔驗證（`config.yml`）
轉接器透過 `ConfigManager.load()` 執行嚴格型別校驗：
- **整數欄位：** 必須為有限整數。包含 `storage.mysql.port`（1–65535）、`storage.mysql.pool-size`（1–1000）、`leaderboard.cache-time-seconds`（1–86400）、`leaderboard.page-size`（1–100）及 `storage.mysql.max-lifetime`（≥1）。浮點數（如 `3306.5`）、`NaN`/`Infinity` 或數字字串（`"3306"`）均直接拒絕。
- **布林欄位：** 必須為原生 YAML Boolean（`economy.allow-negative-balance`、`discord.enabled`、`leaderboard.enabled`），字串 `"true"`/`"false"` 會被拒絕。
- **字串欄位：** 包含 `settings.locale`（僅限 `en_US`、`zh_TW`、`zh_CN`）、路徑與帳密。`storage.sqlite.path` 若超出插件資料夾會被阻擋。
- **脫敏防護：** 診斷日誌絕不回顯密碼、Webhook 或使用者原始輸入，僅輸出固定格式訊息（如 `invalid <path>: must be …`）或例外類別。

### 2. 語系檔預檢與載入
轉接器會檢查選定的 `lang/<locale>.yml`：
- 目標檔案必須存在、為一般檔案且非空檔。若缺失、為目錄或不可讀取，立即視為失敗，絕不靜默回退。
- 通過檔案預檢後，由 `LangManager.load(locale)` 解析內容與 MiniMessage 標籤。

### 3. 原子交換與失敗防護
僅當設定與語系兩者皆通過驗證，才會在單一鎖保護下原子交換 `ConfigManager`、`LangManager`、`MessageService` 與渲染範圍。退役的舊範圍隨即關閉，避免產生半套用或撕裂狀態。

若載入失敗，系統回傳包含非敏感原因的 `ReloadResult` 並輸出 `WARNING`。記憶體中維持原設定與語系輸出不變。修正檔案問題後重新執行 `/aceeco reload` 即可。

缺鍵時回退顯示 `Missing translation: <key>`，並記錄 `WARNING` 日誌。所有傳入變數中的 MiniMessage 標籤（如 `<red>`、`<click>`）均會在渲染前脫逸，防範顏色與點擊事件注入。

## 相關指南

- [設定指南](config.zh-TW.md) — 完整的 `config.yml` 參考。
- [整合功能](integrations.zh-TW.md) — Vault、PlaceholderAPI、Discord 與 AceLib 的設定。
- [整合 API](integration-api.zh-TW.md) — 給插件開發者的佔位符與貨幣細節。
