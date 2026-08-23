# AceEconomy integrations

This guide is for server administrators who want other plugins to use AceEconomy. It covers the
required AceLib dependency and the optional Vault, PlaceholderAPI, and Discord integrations.
For the public surfaces available to plugin authors, see [`integration-api.md`](integration-api.md).
For message translations, see [`localization.md`](localization.md).

這份指南給需要讓其他插件使用 AceEconomy 的伺服器管理員。內容涵蓋必要的 AceLib，以及可選的
Vault、PlaceholderAPI 與 Discord 整合。插件開發者可參考
[`integration-api.md`](integration-api.md)；訊息翻譯請看 [`localization.md`](localization.md)。

## Before you start

AceEconomy requires:

- AceLib `v1.0.0` at runtime. Install it before AceEconomy. `plugin.yml` declares AceLib as a hard
  dependency, so AceEconomy will not start without a ready AceLib service.
- Java 25 and a Paper/Folia server matching the release baseline.

Vault and PlaceholderAPI are optional. AceEconomy declares them as soft dependencies and skips the
corresponding integration when the plugin is not installed or enabled. Discord does not require a
separate server plugin; it uses the webhook configured in `config.yml`.

AceEconomy 需要：

- 執行時可用的 AceLib `v1.0.0`。請先安裝 AceLib，再啟動 AceEconomy。`plugin.yml` 將 AceLib
  列為必要依賴，因此沒有可用的 AceLib 服務時，AceEconomy 不會啟動。
- Java 25，以及符合本版本基準的 Paper/Folia 伺服器。

Vault 與 PlaceholderAPI 都是可選整合。`plugin.yml` 將它們列為軟依賴；插件未安裝或未啟用時，
AceEconomy 會略過對應整合。Discord 不需要另外安裝伺服器插件，而是使用 `config.yml` 中設定的
Webhook。

## AceLib

### Install and check

Install the matching AceLib release, then start the server with AceEconomy. AceLib must be ready
before AceEconomy can register its commands, messages, and integrations.

When it is working, AceEconomy enables normally and its commands are available. If AceLib is missing
or not ready, the plugin is disabled instead of starting with partial services.

**If it does not start:**

1. Check that the AceLib JAR is installed and enabled.
2. Check that the AceLib version is `v1.0.0` for this release.
3. Read the first AceEconomy startup error. Fix AceLib first, then restart AceEconomy.

### 安裝與確認

請安裝相符的 AceLib 版本，再啟動含有 AceEconomy 的伺服器。AceLib 必須先就緒，AceEconomy
才能註冊指令、訊息與其他整合。

正常時，AceEconomy 會完成啟動，相關指令也能使用。如果 AceLib 缺少或尚未就緒，插件會停用，
不會以只有部分服務的狀態繼續執行。

**無法啟動時：**

1. 確認 AceLib JAR 已安裝並啟用。
2. 確認本版本使用的是 AceLib `v1.0.0`。
3. 先閱讀 AceEconomy 啟動時最早出現的錯誤。修正 AceLib 後，再重新啟動 AceEconomy。

## Vault

Vault lets plugins that use the standard Vault `Economy` service use AceEconomy as their economy
provider.

### Install and configure

1. Install and enable Vault.
2. Install and enable AceLib, then AceEconomy.
3. Choose the currency with `default: true` in `config.yml`:

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

Vault has one economy balance, so it always uses the configured default currency. Named currencies
remain available through AceEconomy's own multi-currency and PlaceholderAPI surfaces; Vault does not
select a currency per call.

### What success looks like

Plugins that query the Vault `Economy` service can load a provider named `AceEconomy`. A deposit or
withdrawal reports success with the new balance. Balance checks for an existing account return the
account balance.

### Troubleshooting

- **No provider is visible:** make sure Vault is enabled before checking AceEconomy. If Vault is not
  installed or enabled, AceEconomy leaves the Vault provider disabled.
- **A transaction reports failure:** check the player account and amount. Failed operations are not
  reported as successful, and the operation is not retried by the Vault adapter.
- **A missing account shows zero or false:** this is the safe result for a balance or `has` query.
  Create the account through the normal AceEconomy flow before asking another plugin to use it.
- **Bank features are unavailable:** the AceEconomy Vault provider does not advertise Vault bank
  support.

### Vault 整合

Vault 讓使用標準 Vault `Economy` 服務的插件，把 AceEconomy 當作經濟提供者。

#### 安裝與設定

1. 安裝並啟用 Vault。
2. 安裝並啟用 AceLib，再啟用 AceEconomy。
3. 在 `config.yml` 將要給 Vault 使用的貨幣設為 `default: true`，例如上方的 `dollar`。

Vault 只有單一經濟餘額，因此永遠使用設定中的預設貨幣。具名貨幣仍可透過 AceEconomy 自己的
多貨幣功能與 PlaceholderAPI 使用；Vault 不會在每次呼叫時選擇貨幣。

#### 成功現象

查詢 Vault `Economy` 服務的插件可以載入名為 `AceEconomy` 的提供者。存款或提款成功時會回傳
新的餘額；已存在帳戶的餘額查詢會回傳該帳戶餘額。

#### 排錯

- **看不到提供者：** 先確認 Vault 已啟用。如果 Vault 未安裝或未啟用，AceEconomy 會保持
  Vault 提供者停用。
- **交易回報失敗：** 檢查玩家帳戶與金額。失敗操作不會被回報為成功，Vault 適配器也不會重試。
- **不存在的帳戶顯示零或 `false`：** 這是餘額或 `has` 查詢的安全結果。請先用 AceEconomy
  的正常流程建立帳戶，再讓其他插件查詢。
- **無法使用銀行功能：** AceEconomy 的 Vault 提供者不宣告 Vault bank support。

## PlaceholderAPI

PlaceholderAPI exposes AceEconomy values under the `aceeco` namespace. Install and enable
PlaceholderAPI before starting AceEconomy.

### Install and check

1. Install and enable PlaceholderAPI.
2. Restart or reload the server so AceEconomy can register its expansion.
3. Put one of the placeholders from the table below into a PAPI-compatible plugin.

For example:

```text
Balance: %aceeco_balance_formatted%
Tokens: %aceeco_balance_token_formatted%
```

If the placeholder is valid and the account is available, the consuming plugin receives the balance
value. Unknown or unusable values remain as the original placeholder text rather than being replaced
with a misleading number.

See [`integration-api.md`](integration-api.md) for the complete placeholder contract and currency ID
rules.

### Troubleshooting

- **The placeholder is shown literally:** confirm PlaceholderAPI is installed and enabled, then
  confirm that the spelling and currency ID are correct.
- **A named currency does not resolve:** use the internal currency ID from `currencies.<id>` and keep
  it lowercase with only `a-z`, `0-9`, and `_`.
- **The default value works but the formatted value does not:** use the exact `_formatted` suffix;
  the four supported forms are listed in the API guide.

### PlaceholderAPI 整合

PlaceholderAPI 會在 `aceeco` 命名空間提供 AceEconomy 數值。請在啟動 AceEconomy 前安裝並啟用
PlaceholderAPI。

#### 安裝與確認

1. 安裝並啟用 PlaceholderAPI。
2. 重新啟動或重新載入伺服器，讓 AceEconomy 註冊 expansion。
3. 將下表其中一個占位符放入支援 PAPI 的插件。

例如：

```text
Balance: %aceeco_balance_formatted%
Tokens: %aceeco_balance_token_formatted%
```

占位符正確且帳戶可用時，使用中的插件會收到餘額值。未知或無法使用的值會保留原始占位符，
不會被錯誤數字取代。

完整占位符契約與貨幣 ID 規則請看 [`integration-api.md`](integration-api.md)。

#### 排錯

- **畫面直接顯示占位符：** 確認 PlaceholderAPI 已安裝並啟用，再檢查拼字與貨幣 ID。
- **具名貨幣無法解析：** 使用 `currencies.<id>` 中的內部貨幣 ID，並只使用小寫 `a-z`、`0-9`
  與 `_`。
- **預設餘額可用，但格式化值無法用：** 確認使用正確的 `_formatted` 尾綴；四種支援形式列在
  API 指南中。

## Discord notifications

Discord sends a best-effort notification after a transaction has been committed. It is useful for
an audit-style channel, but it is not the transaction result: a Discord delivery problem does not
roll back or veto the economy operation.

### Configure it

Set the two `discord` keys in `plugins/AceEconomy/config.yml`:

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/<WEBHOOK_ID>/<WEBHOOK_TOKEN>"
```

Replace the placeholders locally. Do not paste a real webhook URL into shared documentation,
commits, issue reports, or support messages.

After saving the file, run `/aceeco reload` from the server console or restart the server. Complete transactions should then
produce a Discord embed containing the transaction type, sender, receiver, and amount.

### Delivery behaviour

- Delivery is asynchronous and does not wait on the network in the command that completed the
  transaction.
- A rejected request, timeout, mapping error, or transport failure is ignored by the notifier. The
  committed economy result remains unchanged.
- Payload fields are length-bounded and configured secrets are redacted. The webhook URL itself is
  not placed in the payload body.

### Troubleshooting

- **Nothing arrives:** check `discord.enabled`, the webhook URL, and the server's network access to
  Discord. Then make one new transaction; notifications are sent for committed events.
- **The transaction succeeds but Discord does not:** this is expected for a best-effort delivery
  failure. Fix the webhook or network path without treating Discord as the source of truth for the
  balance.
- **A secret appears in a payload field:** remove it from the transaction text and rotate the secret.
  Webhook credentials belong only in the local configuration.

### Discord 通知

Discord 會在交易提交後，以盡力而為的方式發送通知。它適合用於類似審計頻道的用途，但不是
交易結果本身：Discord 投遞失敗不會回滾或否決經濟操作。

#### 設定

在 `plugins/AceEconomy/config.yml` 設定 `discord` 下的兩個鍵：

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/<WEBHOOK_ID>/<WEBHOOK_TOKEN>"
```

只在本機將 placeholder 換成真正值。不要把真實 Webhook URL 放進共用文件、commit、issue 或求助
訊息。

儲存檔案後，從伺服器主控台執行 `/aceeco reload`，或重新啟動伺服器。之後完成的交易應在 Discord 收到包含交易
類型、發送者、接收者與金額的 embed。

#### 投遞行為

- 投遞是非同步的，完成交易的指令不會等待網路回應。
- 請求被拒絕、逾時、對映失敗或傳輸失敗時，通知器會忽略該錯誤；已提交的經濟結果不變。
- Payload 欄位有長度限制，設定的 secrets 會被遮蔽；Webhook URL 不會放進 payload 內容。

#### 排錯

- **Discord 沒收到訊息：** 檢查 `discord.enabled`、Webhook URL，以及伺服器是否能連到 Discord。
  再執行一筆新交易；通知是針對已提交事件發送。
- **交易成功但 Discord 沒訊息：** 這符合盡力而為通知的行為。修正 Webhook 或網路路徑，不要
  把 Discord 當成餘額的真實來源。
- **Payload 欄位出現 secret：** 從交易文字移除該值並輪替 secret。Webhook 憑證只能放在本機設定。

## Related guides

- [`integration-api.md`](integration-api.md) — public Vault and PlaceholderAPI contract for plugin
  developers.
- [`localization.md`](localization.md) — v2 language files, keys, placeholders, and reload workflow.
- [`config.md`](config.md) — storage, currency, and server configuration reference.

## 相關指南

- [`integration-api.md`](integration-api.md)：給插件開發者的 Vault 與 PlaceholderAPI 公開契約。
- [`localization.md`](localization.md)：v2 語系檔、key、placeholder 與重新載入方式。
- [`config.md`](config.md)：儲存、貨幣與伺服器設定參考。
