# AceEconomy integration API

This reference is for plugin developers. It describes the public integration surfaces that let a
plugin read or modify AceEconomy balances without depending on AceEconomy's internal classes:
the standard Vault `Economy` service and the PlaceholderAPI `aceeco` namespace.

這份參考給插件開發者使用。它說明插件如何透過標準 Vault `Economy` 服務與 PlaceholderAPI
`aceeco` namespace 讀取或修改 AceEconomy 餘額，而不依賴 AceEconomy 內部類別。

For server installation and operator troubleshooting, see [`integrations.md`](integrations.md).
Language-file syntax is documented in [`localization.md`](localization.md).

伺服器安裝與管理員排錯請看 [`integrations.md`](integrations.md)；語系檔語法請看
[`localization.md`](localization.md)。

## Availability and compatibility

AceEconomy requires AceLib `v1.0.0` at runtime. Vault and PlaceholderAPI are optional integrations;
your plugin should handle either public service being unavailable. Do not make an AceEconomy
implementation class a hard dependency when the standard integration is enough.

AceEconomy 執行時需要 AceLib `v1.0.0`。Vault 與 PlaceholderAPI 是可選整合；你的插件應能處理
任一公開服務不存在的情況。如果標準整合已足夠，不要把 AceEconomy 的實作類別設為硬依賴。

## Vault provider

AceEconomy registers a Vault `Economy` provider named `AceEconomy`. A plugin can discover it through
Vault's normal service lookup:

```java
Economy economy = getServer().getServicesManager().load(Economy.class);
if (economy == null || !economy.isEnabled()) {
    // Vault or an economy provider is not available.
    return;
}

double balance = economy.getBalance(player);
EconomyResponse response = economy.withdrawPlayer(player, 25.0);
if (!response.transactionSuccess()) {
    // Treat the operation as failed; do not assume the balance changed.
}
```

The provider maps Vault calls to the configured default currency. Vault does not carry a currency ID,
so a Vault consumer cannot select `token` or another named currency per call. Use the PlaceholderAPI
forms below when a display or integration needs a named currency.

### Vault result rules

| Operation | Public result |
| --- | --- |
| Successful deposit or withdrawal | `EconomyResponse` reports success and the new balance. |
| Failed deposit or withdrawal | `EconomyResponse` reports failure, with amount `0` and the current or zero balance. The operation is not retried. |
| Balance or `has` for a missing account | `0.0` or `false`; no exception is required for this safe result. |
| Name-only account methods | `false` or failure. Use `OfflinePlayer` methods because accounts are UUID-based. |
| Vault bank methods | Not supported. |

Always inspect `transactionSuccess()` before treating a deposit or withdrawal as complete. Do not use
the Vault provider for a non-default currency.

### Vault 提供者

AceEconomy 會註冊名為 `AceEconomy` 的 Vault `Economy` 提供者。插件可以使用 Vault 的標準服務
查詢方式：

```java
Economy economy = getServer().getServicesManager().load(Economy.class);
if (economy == null || !economy.isEnabled()) {
    // Vault 或經濟提供者不可用。
    return;
}

double balance = economy.getBalance(player);
EconomyResponse response = economy.withdrawPlayer(player, 25.0);
if (!response.transactionSuccess()) {
    // 視為失敗，不要假設餘額已變更。
}
```

這個提供者會把 Vault 呼叫對映到設定中的預設貨幣。Vault 本身沒有貨幣 ID，因此 Vault 使用者
不能在每次呼叫時選擇 `token` 或其他具名貨幣。需要顯示或整合具名貨幣時，請使用下方的
PlaceholderAPI 形式。

#### Vault 結果規則

| 操作 | 公開結果 |
| --- | --- |
| 存款或提款成功 | `EconomyResponse` 回報成功與新的餘額。 |
| 存款或提款失敗 | `EconomyResponse` 回報失敗，amount 為 `0`，餘額為目前值或零；不會重試。 |
| 查詢不存在帳戶的餘額或 `has` | 回傳 `0.0` 或 `false`；這個安全結果不需要拋例外。 |
| 只有名稱的帳戶方法 | 回傳 `false` 或失敗。帳戶以 UUID 為主鍵，請使用 `OfflinePlayer` 方法。 |
| Vault bank 方法 | 不支援。 |

請先檢查 `transactionSuccess()`，再把存款或提款視為完成。不要使用 Vault 提供者操作非預設貨幣。

## PlaceholderAPI namespace

The PAPI namespace is `aceeco`. Placeholder parameters are case-insensitive in the resolver, but use
the documented lowercase spelling in configuration and plugin text.

PAPI namespace 是 `aceeco`。解析器對參數不區分大小寫，但設定與插件文字請使用下表的標準小寫
寫法。

### Complete placeholder cheat sheet

| Copy this placeholder | Resolves to | Example result |
| --- | --- | --- |
| `%aceeco_balance%` | Raw balance in the default currency. | `100.00` |
| `%aceeco_balance_formatted%` | Default-currency balance with its symbol. | `$100.00` |
| `%aceeco_balance_<currency>%` | Raw balance in the named currency. | `%aceeco_balance_token%` → `7` |
| `%aceeco_balance_<currency>_formatted%` | Named-currency balance with its symbol. | `%aceeco_balance_token_formatted%` → `ⓒ7` |

Replace `<currency>` with the internal currency ID, not the display name. For the default `config.yml`,
these are ready to copy:

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_token%
%aceeco_balance_token_formatted%
```

`<currency>` must match `[a-z0-9_]+`. Unknown IDs, malformed IDs, unknown placeholder names, missing
players, and unavailable accounts resolve to `null`; PlaceholderAPI then keeps the original literal
placeholder instead of displaying a false balance.

### 完整 placeholder cheat sheet

| 可直接複製的形式 | 解析內容 | 結果範例 |
| --- | --- | --- |
| `%aceeco_balance%` | 預設貨幣的原始餘額。 | `100.00` |
| `%aceeco_balance_formatted%` | 帶貨幣符號的預設貨幣餘額。 | `$100.00` |
| `%aceeco_balance_<currency>%` | 指定貨幣的原始餘額。 | `%aceeco_balance_token%` → `7` |
| `%aceeco_balance_<currency>_formatted%` | 帶符號的指定貨幣餘額。 | `%aceeco_balance_token_formatted%` → `ⓒ7` |

請把 `<currency>` 換成內部貨幣 ID，不是顯示名稱。以預設 `config.yml` 來說，以下都可以直接複製：

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_token%
%aceeco_balance_token_formatted%
```

`<currency>` 必須符合 `[a-z0-9_]+`。未知 ID、格式錯誤的 ID、未知 placeholder 名稱、缺少玩家或
無法使用的帳戶都會解析為 `null`；PlaceholderAPI 會保留原始文字，不顯示假的餘額。

## Currency parameters

Currency IDs come from the keys under `currencies`:

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

| Parameter | Integration meaning |
| --- | --- |
| `currencies.<id>` | The internal ID used in named placeholders. Use lowercase letters, digits, and `_`. |
| `name` | Display name of the currency. It is not used as the placeholder ID. |
| `symbol` | Prefix used by formatted balances. |
| `scale` | Fractional precision defined for the currency. |
| `default` | Selects the one currency exposed through Vault and the default placeholder forms. |

Only one currency should be marked `default: true`. If you add a named currency, use its internal ID
in both raw and formatted placeholder forms.

### 貨幣參數

貨幣 ID 來自 `currencies` 下的 key：

```yaml
currencies:
  dollar:
    name: "金幣"
    symbol: "$"
    scale: 2
    default: true
  token:
    name: "活動代幣"
    symbol: "ⓒ"
    scale: 0
    default: false
```

| 參數 | 整合用途 |
| --- | --- |
| `currencies.<id>` | 具名 placeholder 使用的內部 ID。請用小寫字母、數字與 `_`。 |
| `name` | 貨幣顯示名稱，不是 placeholder ID。 |
| `symbol` | 格式化餘額使用的前綴。 |
| `scale` | 貨幣的小數精度。 |
| `default` | 指定 Vault 與預設 placeholder 形式使用的唯一貨幣。 |

請只將一種貨幣設為 `default: true`。新增具名貨幣後，在原始與格式化 placeholder 形式中都使用
它的內部 ID。

## Fail-safe integration behaviour

An integration consumer should treat a missing service or an unsuccessful result as a normal branch:

- Check that Vault returns an `Economy` service before calling it.
- Check every `EconomyResponse` before updating your own state or sending a success message.
- Treat an unchanged literal PAPI placeholder as unavailable data, not as a numeric value.
- Do not retry a failed Vault transaction automatically unless your own product contract explicitly
  defines a safe retry strategy.

這些整合遇到服務不存在或結果失敗時，使用方應把它當成正常分支處理：

- 呼叫 Vault 前先確認查得到 `Economy` 服務。
- 每次使用 `EconomyResponse` 都先檢查結果，再更新自己的狀態或發送成功訊息。
- PAPI 仍顯示原始 placeholder 時，將它視為資料不可用，不要當成數字。
- 不要自動重試失敗的 Vault 交易，除非你的產品契約明確定義了安全的重試策略。

## Public integration checklist

For a plugin integration, depend on the public contract rather than implementation details:

1. Declare the relevant external plugin as optional when your plugin can run without it.
2. Look up Vault `Economy` at runtime and handle `null`.
3. Use `OfflinePlayer`/UUID-aware calls and inspect `EconomyResponse`.
4. Put the four PAPI forms in user-facing configuration or messages exactly as documented.
5. Leave secrets and webhook URLs in local configuration only.

插件公開整合檢查清單：

1. 如果你的插件沒有外部整合也能執行，請將相關外部插件設為可選依賴。
2. 在執行時查詢 Vault `Economy`，並處理 `null`。
3. 使用 `OfflinePlayer`／UUID 相關方法，並檢查 `EconomyResponse`。
4. 將四種 PAPI 形式原樣放進面向使用者的設定或訊息。
5. Secret 與 Webhook URL 只放在本機設定。

## Related guides

- [`integrations.md`](integrations.md) — server installation, configuration, success checks, and troubleshooting.
- [`localization.md`](localization.md) — v2 language keys and `{placeholder}` message variables.

## 相關指南

- [`integrations.md`](integrations.md)：伺服器安裝、設定、成功確認與排錯。
- [`localization.md`](localization.md)：v2 語系 key 與 `{placeholder}` 訊息變數。
