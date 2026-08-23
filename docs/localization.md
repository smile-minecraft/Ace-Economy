# AceEconomy localization

AceEconomy v2 keeps its language resources in `lang/<locale>.yml`. This guide explains how to
change a server language, edit a translation, preserve the key namespace, and reload the result.
The examples use the built-in `en_US`, `zh_TW`, and `zh_CN` locales.

AceEconomy v2 的語系資源使用 `lang/<locale>.yml`。本指南說明如何變更伺服器語言、修改翻譯、
維持 key namespace，以及重新載入結果。範例使用內建的 `en_US`、`zh_TW` 與 `zh_CN` 語系。

## File locations and locales

After the first start, edit the files in the plugin data folder:

```text
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
```

The locale is selected in `plugins/AceEconomy/config.yml`:

```yaml
settings:
  locale: zh_TW
```

The v2 files are named `lang/<locale>.yml`. The older `messages_<locale>.yml` naming belongs to the
previous layout; do not use it when editing v2 messages.

首次啟動後，請修改插件資料夾中的檔案：

```text
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
```

語系在 `plugins/AceEconomy/config.yml` 選擇：

```yaml
settings:
  locale: zh_TW
```

v2 使用 `lang/<locale>.yml` 命名。舊版的 `messages_<locale>.yml` 屬於之前的檔案配置；修改 v2
訊息時不要使用舊檔名。

## How a v2 message is written

Language files use two separate syntaxes:

- **Key namespace:** dotted YAML paths such as `general.invalid-amount` and
  `economy.payment-sent`. Keep the namespace and key names unchanged; translate values, not keys.
- **Typed placeholders:** write variables as `{placeholder}`, for example `{amount}`, `{player}`,
  `{balance}`, and `{status}`. Keep the braces and placeholder name intact.
- **MiniMessage:** use tags such as `<red>`, `<yellow>`, `<aqua>`, `<green>`, and `</red>` for
  presentation. Tags are rendered after variable substitution, so they should not be replaced with
  legacy colour-code syntax.

For example, the same key can carry different wording while keeping the contract:

```yaml
economy:
  balance-check: "Your balance: <yellow>{balance}</yellow>"
  payment-sent: "<green>Sent <yellow>{amount}</yellow> to <aqua>{player}</aqua>!</green>"
```

翻譯檔使用兩種不同語法：

- **Key namespace：** 使用 `general.invalid-amount`、`economy.payment-sent` 這類 dotted YAML
  路徑。請保留 namespace 與 key 名稱，只翻譯值。
- **Typed placeholder：** 變數寫成 `{placeholder}`，例如 `{amount}`、`{player}`、`{balance}` 與
  `{status}`。大括號與 placeholder 名稱都要保留。
- **MiniMessage：** 使用 `<red>`、`<yellow>`、`<aqua>`、`<green>` 與 `</red>` 等標籤處理顯示
  樣式。標籤會在變數代換後解析，不要改成舊式色碼。

同一個 key 可以使用不同文案，只要契約不變：

```yaml
economy:
  balance-check: "您的餘額：<yellow>{balance}</yellow>"
  payment-sent: "<green>已轉帳 <yellow>{amount}</yellow> 給 <aqua>{player}</aqua>！</green>"
```

### Built-in key map

The built-in resources use these namespaces:

| Namespace | Use | Example keys |
| --- | --- | --- |
| `message` | Prefix shared by messages | `message.prefix` |
| `general` | General errors and status | `general.no-permission`, `general.status` |
| `economy` | Balances and payments | `economy.balance-check`, `economy.payment-received` |
| `admin` | Administrator feedback | `admin.give` |

內建語系檔使用以下 namespace：

| Namespace | 用途 | key 範例 |
| --- | --- | --- |
| `message` | 訊息共用前綴 | `message.prefix` |
| `general` | 一般錯誤與狀態 | `general.no-permission`、`general.status` |
| `economy` | 餘額與轉帳訊息 | `economy.balance-check`、`economy.payment-received` |
| `admin` | 管理員操作回饋 | `admin.give` |

## Change the active language

1. Open `plugins/AceEconomy/config.yml`.
2. Set `settings.locale` to the matching locale filename, such as `en_US`, `zh_TW`, or `zh_CN`.
3. Save the file.
4. Run `/aceeco reload` from the server console, or restart the server.

The reload reads the config and language snapshot again. A valid change becomes visible in the next
message that uses the changed key.

變更目前語言：

1. 開啟 `plugins/AceEconomy/config.yml`。
2. 將 `settings.locale` 設為對應檔名中的語系，例如 `en_US`、`zh_TW` 或 `zh_CN`。
3. 儲存檔案。
4. 從伺服器主控台執行 `/aceeco reload`，或重新啟動伺服器。

重新載入會再次讀取設定與語系快照。修改有效後，下一次使用該 key 的訊息就會顯示新內容。

## Edit a translation safely

Start from the v2 file that matches the locale you want to edit. Change only the YAML values:

```yaml
general:
  invalid-amount: "<red>Invalid amount: <white>{amount}</white></red>"
  player-not-found: "<red>Player not found: <white>{player}</white></red>"
```

When translating:

- keep indentation and YAML quoting valid;
- keep every `{placeholder}` needed by the original message;
- keep opening and closing MiniMessage tags paired;
- keep key names such as `invalid-amount` in English, even when the value is translated.

Keep the built-in resource shape when maintaining a locale. The built-in resource set remains the
reference for the required namespace and placeholder names.

安全修改翻譯時，請從要修改的語系對應 v2 檔案開始，只改 YAML 值：

```yaml
general:
  invalid-amount: "<red>無效金額：<white>{amount}</white></red>"
  player-not-found: "<red>找不到玩家：<white>{player}</white></red>"
```

翻譯時請注意：

- 保留縮排，並確保 YAML 引號有效；
- 保留原訊息需要的每一個 `{placeholder}`；
- MiniMessage 開始與結束標籤要成對；
- `invalid-amount` 這類 key 名稱保持英文，即使值已翻譯。

維護語系時請保留內建資源的結構。內建語系檔是 namespace 與 placeholder 名稱的參考。

## Reload and recovery

`/aceeco reload` from the server console reloads both the v2 config and the selected language resource. If the edited YAML is
invalid, the reload reports a failure and keeps the last valid in-memory snapshot. This means a bad
translation should not silently replace the language currently in use.

Fix the reported YAML problem, save the file, and run `/aceeco reload` again. If the file still fails,
restore the last valid copy and check the key indentation, quotes, and MiniMessage tags.

從伺服器主控台執行 `/aceeco reload` 會重新載入 v2 設定與選定的語系資源。如果修改後的 YAML 無效，重新載入會回報
失敗，並保留記憶體中最後一份有效快照。錯誤翻譯不會悄悄取代目前使用中的語言。

請修正回報的 YAML 問題、儲存檔案，再次執行 `/aceeco reload`。如果仍然失敗，請還原最後一份
有效副本，並檢查 key 縮排、引號與 MiniMessage 標籤。

## Related guides

- [`config.md`](config.md) — the full `config.yml` reference.
- [`integrations.md`](integrations.md) — Vault, PlaceholderAPI, Discord, and AceLib setup.
- [`integration-api.md`](integration-api.md) — placeholder and currency details for plugin developers.

## 相關指南

- [`config.md`](config.md)：完整的 `config.yml` 參考。
- [`integrations.md`](integrations.md)：Vault、PlaceholderAPI、Discord 與 AceLib 設定。
- [`integration-api.md`](integration-api.md)：給插件開發者的 placeholder 與貨幣細節。
