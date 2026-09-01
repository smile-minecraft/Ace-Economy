# AceEconomy 整合功能

[English](integrations.md) · [简体中文](integrations.zh-CN.md) · 繁體中文

當伺服器管理員需要讓 AceEconomy 和其他插件或 Discord 協作時，請使用本指南。內容涵蓋必需的 AceLib，以及選用的 Vault、PlaceholderAPI 與 Discord 整合。插件作者請看[整合 API](integration-api.zh-TW.md)；語言檔請看[在地化](localization.zh-TW.md)。

## 目錄

- [開始前](#開始前)
- [AceLib](#acelib)
- [Vault](#vault)
- [PlaceholderAPI](#placeholderapi)
- [Discord 通知](#discord-通知)
- [相關指南](#相關指南)

## 開始前

AceEconomy 執行時需要 AceLib `v1.2.0`。請先裝好 AceLib，再啟動 AceEconomy。`plugin.yml` 把 AceLib 列為硬依賴；如果沒有一個就緒的 AceLib 服務，AceEconomy 根本不會啟動。執行環境還需要 Java 25，以及符合本版本基準的 Paper/Folia 伺服器。

Vault 與 PlaceholderAPI 是選用的軟依賴。插件沒裝或沒啟用時，AceEconomy 會跳過對應的整合。Discord 不需要另外裝伺服器插件，它用的是 `config.yml` 裡設定的 webhook。

## AceLib

請安裝與本版本相符的 AceLib，再啟動帶 AceEconomy 的伺服器。AceLib 文件必須先就緒，AceEconomy 才能註冊指令、訊息與整合。

正常時，AceEconomy 會完成啟動，相關指令也能用。如果 AceLib 缺失或還沒就緒，插件會直接停用，而不是帶著殘缺的服務勉強跑。

無法啟動時：

1. 確認 AceLib 的 JAR 已經安裝並啟用。
2. 確認本版本使用的是 AceLib `v1.2.0`。
3. 看 AceEconomy 啟動時最早出現的那條錯誤。先把 AceLib 修好，再重啟 AceEconomy。

## Vault

Vault 讓那些使用標準 Vault `Economy` 服務的插件，可以把 AceEconomy 當作經濟提供者。

### 安裝與設定

1. 安裝並啟用 Vault。
2. 安裝並啟用 AceLib，再啟用 AceEconomy。
3. 在 `config.yml` 裡，把要交給 Vault 使用的貨幣設為 `default: true`：

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

Vault 只有一個經濟餘額，所以永遠使用設定裡的預設貨幣。具名貨幣仍然可以透過 AceEconomy 自己的多貨幣功能與 PlaceholderAPI 來使用；Vault 不會在每次呼叫時挑選貨幣。

### 成功現象

查詢 Vault `Economy` 服務的插件，可以載入到一個名為 `AceEconomy` 的提供者。存款或提款成功時會回傳新的餘額；查詢一個已有帳戶的餘額，會回傳該帳戶的餘額。

### 排錯

- **看不到提供者：** 先啟用 Vault，再檢查 AceEconomy。Vault 沒裝或沒啟用時，AceEconomy 會保持 Vault 提供者停用。
- **交易回報失敗：** 檢查玩家帳戶與金額。失敗的操作不會被報成成功，Vault 的轉接器也不會重試。
- **不存在的帳戶顯示零或 false：** 這是餘額或 `has` 查詢的安全結果。請先透過 AceEconomy 的正常流程建立帳戶。
- **銀行功能不可用：** AceEconomy 的 Vault 提供者不提供 Vault 的銀行功能。

## PlaceholderAPI

PlaceholderAPI 在 `aceeco` 這個命名空間下提供 AceEconomy 的數值。請在啟動 AceEconomy 之前，先安裝並啟用 PlaceholderAPI。

### 安裝與確認

1. 安裝並啟用 PlaceholderAPI。
2. 重新啟動伺服器或重新載入，讓 AceEconomy 註冊它的擴充。
3. 把[整合 API](integration-api.zh-TW.md)裡的某個佔位符，放進支援 PAPI 的插件。

```text
Balance: %aceeco_balance_formatted%
Tokens: %aceeco_balance_token_formatted%
```

只要佔位符正確、帳戶可用，使用中的插件就會收到餘額值。未知或不可用的數值，會保留原始的佔位符文字，不會被一個假數字頂替。

具名貨幣使用的是 `currencies.<id>` 裡的內部貨幣 ID，只能用小寫 `a-z`、`0-9` 與 `_`。要顯示帶格式的值，必須使用精確的 `_formatted` 尾綴。

### 排錯

- **畫面直接顯示出佔位符：** 確認 PlaceholderAPI 已經安裝並啟用，再檢查拼字與貨幣 ID。
- **具名貨幣解析不出來：** 用內部貨幣 ID，不要用顯示名稱。
- **預設值能用，但格式化的值用不了：** 使用精確的 `_formatted` 尾綴；四種形式都列在 API 指南裡。

## Discord 通知

Discord 會在交易提交之後，以盡力而為的方式發一條通知。它適合用來做審計風格的頻道，但它本身不是交易結果：通知失敗不會回滾或否決那筆經濟操作。

### 設定

在 `plugins/AceEconomy/config.yml` 裡設定這兩個 `discord` 鍵：

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/<WEBHOOK_ID>/<WEBHOOK_TOKEN>"
```

請只在本機替換掉佔位符。不要把真實的 webhook 網址寫進共用文件、commit、issue 或求助訊息。

保存之後，從伺服器主控台執行 `/aceeco reload`，或者重啟伺服器。之後完成的交易，應該在 Discord 收到一條包含交易類型、發送者、接收者與金額的嵌入訊息。

### 投遞行為

- 投遞是非同步的，執行交易的指令不會乾等網路。
- 请求被拒绝、超时、映射失败或传输失败时，通知器会忽略这个错误；已经提交的经济结果保持不变。
- 负载内容的字段有长度限制，设置里的密钥会被遮蔽；webhook 网址本身不会放进负载内容里。

### 排錯

- **沒有訊息：** 檢查 `discord.enabled`、webhook 網址，以及伺服器到 Discord 的網路連通性，然後做一筆新交易；通知是針對已提交的事件發送的。
- **交易成功但 Discord 沒訊息：** 這就是盡力而為投遞失敗的正常情況。去修 webhook 或網路路徑，不要把 Discord 當成餘額的真正來源。
- **負載內容裡出現了密鑰：** 從交易文字裡移除那個值，並輪換密鑰。webhook 憑證只能放在本機設定裡。

## 相關指南

- [整合 API](integration-api.zh-TW.md) — 給插件開發者的 Vault 與 PlaceholderAPI 公開契約。
- [在地化](localization.zh-TW.md) — v2 語言檔、鍵、佔位符與重新載入流程。
- [設定指南](config.zh-TW.md) — 儲存、貨幣與伺服器設定參考。
