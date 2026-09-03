# AceEconomy 整合 API

[English](integration-api.md) · [简体中文](integration-api.zh-CN.md) · 繁體中文

這份參考是給插件開發者看的。它說明如何不依賴 AceEconomy 的內部類別，而是透過標準的 Vault `Economy` 服務與 PlaceholderAPI 的 `aceeco` 命名空間，來讀取或修改餘額。伺服器安裝請看[整合功能](integrations.zh-TW.md)；語言檔語法請看[在地化](localization.zh-TW.md)。

## 目錄

- [可用性與相容性](#可用性與相容性)
- [Vault 提供者](#vault-提供者)
- [PlaceholderAPI 命名空間](#placeholderapi-命名空間)
- [貨幣參數](#貨幣參數)
- [安全的整合行為](#安全的整合行為)
- [公開整合清單](#公開整合清單)
- [相關指南](#相關指南)

## 可用性與相容性

AceEconomy 執行時需要 AceLib `v1.2.0`。Vault 與 PlaceholderAPI 是選用整合；你的插件應該能處理其中任一公開服務不可用的情況。如果標準整合已經夠用，不要把 AceEconomy 的實作類別設成硬依賴。

## Vault 提供者

AceEconomy 會註冊一個名為 `AceEconomy` 的 Vault `Economy` 提供者。插件可以用 Vault 標準的服務查詢方式來取得它：

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

這個提供者會把 Vault 的呼叫對應到設定裡的預設貨幣。Vault 沒有貨幣 ID，所以 Vault 的使用者不能在每次呼叫時挑選 `token` 或其他具名貨幣。需要顯示或整合具名貨幣時，請使用下方的 PlaceholderAPI 形式。

### Vault 結果規則

| 操作 | 公開結果 |
| --- | --- |
| 存款或提款成功 | `EconomyResponse` 回報成功，並給出新餘額。 |
| 存款或提款失敗 | `EconomyResponse` 回報失敗，金額為 `0`，餘額為目前值或零；不會重試。 |
| 查詢不存在帳戶的餘額或 `has` | 回傳 `0.0` 或 `false`；這個安全結果不要求拋例外。 |
| 只有名稱的帳戶方法 | 回傳 `false` 或失敗。帳戶使用 UUID，請使用 `OfflinePlayer` 方法。 |
| Vault 的銀行方法 | 不支援。 |

在把存款或提款視為完成之前，必須先檢查 `transactionSuccess()`。不要用 Vault 提供者去操作非預設貨幣。

## PlaceholderAPI 命名空間

PAPI 的命名空間是 `aceeco`。解析器對參數不區分大小寫，但設定與插件文字裡，請使用文件規定的小寫寫法。

### 佔位符完整速查表

| 可複製的佔位符 | 解析為 | 結果範例 |
| --- | --- | --- |
| `%aceeco_balance%` | 預設貨幣的原始餘額。 | `100.00` |
| `%aceeco_balance_formatted%` | 帶符號的預設貨幣餘額。 | `$100.00` |
| `%aceeco_balance_<currency>%` | 具名貨幣的原始餘額。 | `%aceeco_balance_token%` → `7` |
| `%aceeco_balance_<currency>_formatted%` | 帶符號的具名貨幣餘額。 | `%aceeco_balance_token_formatted%` → `ⓒ7` |
| `%aceeco_rank%` / `%aceeco_rank_<currency>%` | 請求玩家在預設／具名貨幣排行榜中的名次（從 1 開始）。沒有有效快照，或玩家不在榜上時回 `null`。 | `%aceeco_rank%` → `3` |
| `%aceeco_top_name_<n>%` / `%aceeco_top_name_<n>_<currency>%` | 排行榜第 n 名的玩家名稱（從 1 開始，`1 <= n <= 100`）。 | `%aceeco_top_name_1%` → `Steve` |
| `%aceeco_top_balance_<n>%` / `%aceeco_top_balance_<n>_<currency>%` | 排行榜第 n 名的原始餘額。 | `%aceeco_top_balance_1%` → `950.00` |
| `%aceeco_currency_name_<id>%` | 已設定貨幣的顯示名稱。 | `%aceeco_currency_name_dollar%` → `Gold Coin` |
| `%aceeco_currency_symbol_<id>%` | 已設定貨幣的符號。 | `%aceeco_currency_symbol_dollar%` → `$` |

請把 `<currency>` 換成內部貨幣 ID，而不是顯示名稱。以預設的 `config.yml` 來說，下面這些形式可以直接複製：

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_token%
%aceeco_balance_token_formatted%
%aceeco_rank%
%aceeco_rank_token%
%aceeco_top_name_1%
%aceeco_top_name_1_token%
%aceeco_top_balance_1%
%aceeco_top_balance_1_token%
%aceeco_currency_name_dollar%
%aceeco_currency_symbol_dollar%
```

`<currency>` 必須符合 `[a-z0-9_]+`。未知的 ID、格式錯誤的 ID、未知的佔位符名稱、缺少玩家，以及不可用的帳戶，都會解析為 `null`；PlaceholderAPI 會保留原始文字，而不是顯示一個假的餘額。

排名與前 N 名佔位符讀取的是與 `/baltop` 同一份排行榜快取快照，因此排序永遠一致，也永遠不會觸發資料庫查詢。快照缺失或過期時（例如剛啟動、第一次重新整理之前），它們會解析為 `null`，佔位符保持原文直到下一次重新整理。不在榜上的玩家，`rank` 也回 `null`，絕不會猜測一個名次。`<n>` 必須是 `1` 到 `100` 的純正整數；`0`、負數、非數字或超大數值都會回 `null`，超出榜單長度的位置同樣回 `null`。

## 貨幣參數

貨幣 ID 來自 `currencies` 下的鍵：

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

| 參數 | 整合用途 |
| --- | --- |
| `currencies.<id>` | 具名佔位符使用的內部 ID。使用小寫字母、數字與 `_`。 |
| `name` | 顯示名稱，不是佔位符 ID。 |
| `symbol` | 格式化餘額使用的前綴。 |
| `scale` | 貨幣定義的小數精度。 |
| `default` | 選擇透過 Vault 與預設佔位符形式公開的唯一貨幣。 |

只應該把一種貨幣標記為 `default: true`。新增具名貨幣時，原始與格式化佔位符都要使用它的內部 ID。

## 安全的整合行為

整合使用方應該把服務缺失或結果失敗，當作一個正常的分支來處理：

- 呼叫 Vault 之前，先確認取到了 `Economy` 服務。
- 每次更新自己的狀態或發出成功訊息之前，先檢查 `EconomyResponse`。
- PAPI 仍然顯示原始佔位符時，把它視為資料不可用，不要當成數字。
- 不要自動重試失敗的 Vault 交易，除非你自己的產品契約明確定義了安全的重試策略。

## 公開整合清單

插件整合應該依賴公開契約，而不是實作細節：

1. 插件沒有外部整合也能執行時，把相關的外部插件宣告為選用依賴。
2. 執行時查詢 Vault 的 `Economy` 並處理 `null`。
3. 使用 `OfflinePlayer`／UUID 相關的呼叫，並檢查 `EconomyResponse`。
4. 把 PAPI 形式，依文件原樣放進面向使用者的設定或訊息裡。
5. 密鑰與 webhook 網址只放在本機設定裡。

## 相關指南

- [整合功能](integrations.zh-TW.md) — 伺服器安裝、設定、成功確認與排錯。
- [在地化](localization.zh-TW.md) — v2 語言鍵與 `{placeholder}` 訊息變數。
