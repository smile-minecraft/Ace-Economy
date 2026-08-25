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

v2 的檔案使用 `lang/<locale>.yml`。舊版的 `messages_<locale>.yml` 屬於上一代的檔案配置；編輯 v2 訊息時不要用舊檔名。

## v2 訊息的寫法

語言檔使用三種語法：

- **鍵的命名空間：** 像 `general.invalid-amount` 與 `economy.payment-sent` 這類帶點的 YAML 路徑。保留命名空間與鍵名，只翻譯值。
- **變數佔位符：** 變數寫成 `{placeholder}`，例如 `{amount}`、`{player}`、`{balance}` 與 `{status}`。保留大括號與佔位符名稱。
- **MiniMessage：** 用 `<red>`、`<yellow>`、`<aqua>`、`<green>`、與 `</red>` 這類標籤來處理顯示。標籤會在變數代換之後解析，不要改成舊式的顏色代碼。

```yaml
economy:
  balance-check: "Your balance: <yellow>{balance}</yellow>"
  payment-sent: "<green>Sent <yellow>{amount}</yellow> to <aqua>{player}</aqua>!</green>"
```

### 內建鍵對照

| 命名空間 | 用途 | 鍵範例 |
| --- | --- | --- |
| `message` | 訊息共用的前綴 | `message.prefix` |
| `general` | 一般錯誤與狀態 | `general.no-permission`、`general.status` |
| `economy` | 餘額與付款 | `economy.balance-check`、`economy.payment-received` |
| `admin` | 管理員操作回饋 | `admin.give` |

## 變更目前語言

1. 開啟 `plugins/AceEconomy/config.yml`。
2. 把 `settings.locale` 設成對應檔名裡的語系，例如 `en_US`、`zh_TW` 或 `zh_CN`。
3. 儲存檔案。
4. 從伺服器主控台執行 `/aceeco reload`，或者重新啟動伺服器。

重新載入會再次讀取設定與語系快照。修改生效之後，下一則用到該鍵的訊息就會以新語言顯示。

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

從伺服器主控台執行 `/aceeco reload`，會重新載入 v2 設定與選中的語言資源。如果修改後的 YAML 無效，重新載入會報告失敗，並保留記憶體中最後一份有效的快照。錯誤的翻譯不應該悄悄取代目前正在使用的語言。

請修正報告裡指出的 YAML 問題、儲存檔案，再執行一次 `/aceeco reload`。如果還是失敗，就還原到最後一份有效的副本，並檢查鍵的縮排、引號與 MiniMessage 標籤。

## 相關指南

- [設定指南](config.zh-TW.md) — 完整的 `config.yml` 參考。
- [整合功能](integrations.zh-TW.md) — Vault、PlaceholderAPI、Discord 與 AceLib 的設定。
- [整合 API](integration-api.zh-TW.md) — 給插件開發者的佔位符與貨幣細節。
