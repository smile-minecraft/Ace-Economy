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

首次啟動時，轉接器會透過 `JavaPlugin.saveResource("lang/" + fileName, false)` 置備三份 canonical `lang/<locale>.yml` 資源。任一 canonical 資源的置備失敗（拋出 `IOException`/權限/`RuntimeException`）為 fail-fast：轉接器會發出脫敏的 `WARNING`（`Failed to ensure lang resource {0}: {1}`，敏感值以 `[redacted sensitive value]` 替代，絕不回顯原始訊息），並以非敏感的 `IllegalStateException` 中止初次 `load()`；不會繼續到 `ConfigManager`/`LangManager` 載入，也不會回落到預設語系。已存在的檔案（`saveResource(..., false)` 不拋例外）不視為失敗。

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
2. 把完整路徑 `settings.locale`（不是裸的 `locale`）設成對應的 canonical 檔名，例如 `en_US`、`zh_TW` 或 `zh_CN`。僅這三個值受支援；其他值會被拒絕並保留上一份有效語系。
3. 儲存檔案。
4. 從伺服器主控台執行 `/aceeco reload`，或者重新啟動伺服器。

重新載入會在同一個 adapter 鎖內再次讀取設定與語系快照。有效修改會在下一則用到該鍵的訊息中以新語言顯示。目前語系由 `config.yml` 經 `ConfigLangAdapter` 與 `LangManager` 解析；建構時的預設值（`zh_TW`）僅在首次載入前 `settings.locale` 缺失時作為回退——若值無效（非 `en_US`/`zh_TW`/`zh_CN`），首次 `load()` 會直接失敗、不會 fallback，`reload()` 則保留上一份有效 snapshot。

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

從伺服器主控台執行 `/aceeco reload`，會透過候選管理器原子地重新載入 v2 設定與選中的語言資源。轉接器會先以 `new ConfigManager(...).load()` 驗證 `config.yml`，對每個已宣告的 v2 欄位採嚴格型別規則且在交換前完成：整數欄位（`storage.mysql.port` 1–65535、`storage.mysql.pool-size` 1–1000、`leaderboard.cache-time-seconds` 1–86400、`leaderboard.page-size` 1–100、`storage.mysql.max-lifetime` ≥1）必須為有限的整數——小數如 `3306.5`、非有限 `NaN`/`Infinity` 與數字字串如 `"3306"` 會被拒絕且不截斷；布林欄位（`economy.allow-negative-balance`、`discord.enabled`、`leaderboard.enabled`）必須為 YAML Boolean（字串 `"true"`/`"false"` 會被拒絕）；字串欄位（`settings.locale` 僅 `en_US`/`zh_TW`/`zh_CN`、`settings.main-command-alias`、`storage.sqlite.path`、`storage.mysql.host`/`database`/`username`/`password`、`discord.webhook-url`）必須為 String 型別（空 `password`/`webhook-url` 仍合法，`storage.sqlite.path` 越界至 data folder 外會被拒絕，並檢查 storage/MySQL 跨欄位規則）。診斷永遠不回顯原始設定、使用者或例外訊息——驗證失敗僅用固定 `invalid <path>: must be …` 或 `must be one of …`，載入/IO 失敗僅用例外類別；不支援的 `settings.locale` 會發出固定 `WARNING`，不回顯原始 code。接著對選中的 `lang/<locale>.yml` 先做預檢（必須存在、為 regular 檔案且非空；缺失、空檔、非 regular/目錄或無法讀取皆視為失敗，不會靜默回落到預設語系），再以 `new LangManager(...).load(locale)` 驗證內容；僅在兩個候選皆成功時，才於同一把鎖內一次性交換 `ConfigManager`/`LangManager`/`MessageService`。任何失敗（YAML 損壞、型別/範圍違規如 `storage.mysql.port: not-a-number` 或 `3306.5`、不支援的 `settings.locale` 如 `ja_JP`、或選中的 `lang/<locale>.yml` 缺失/空檔/非 regular/損壞）皆會保留上一份完整快照，`getConfig("settings.locale")`、目前語系與所有渲染輸出皆不變——不會出現半套用狀態，且檔案保留/還原失敗會併入診斷。

失敗會回傳 `ReloadResult{config=failed/lang=failed, configError/langError}`，攜帶非敏感診斷（固定 `invalid <path>: …` 或例外類別；密碼、webhook 網址與任意使用者值永不回顯）並透過插件 logger 發出 `WARNING`；`diagnostics()` 始終包含失敗側原因。成功則回傳 `config=ok, lang=ok`。錯誤的修改永遠不會覆蓋目前記憶體中的語言。

缺鍵會產生非空白回退 `Missing translation: <key>`（不洩漏使用者提供的值）並記錄診斷 `WARNING`。使用者提供的值若包含 MiniMessage 標籤（如 `<red>`、`<bold>`、`<click:...>`、`<hover:...>`、`<insertion>`、`<font>`），會在解析前被脫逸，因此在元件與純文字投影中皆以字面文字出現，不會產生顏色、裝飾、點擊/懸停、insertion 或 font 注入。

請修正報告裡指出的 YAML 問題（檢查有效的 `{placeholder}` 名稱、成對的 MiniMessage 標籤、指令範例的脫逸 `\<literal>` 以及正確的 `settings.locale`）、儲存檔案，再執行一次 `/aceeco reload`。若還是失敗，就還原到最後一份有效的副本，並檢查鍵的縮排、引號與 MiniMessage 標籤。

## 相關指南

- [設定指南](config.zh-TW.md) — 完整的 `config.yml` 參考。
- [整合功能](integrations.zh-TW.md) — Vault、PlaceholderAPI、Discord 與 AceLib 的設定。
- [整合 API](integration-api.zh-TW.md) — 給插件開發者的佔位符與貨幣細節。
