# AceEconomy localization

English · [简体中文](localization.zh-CN.md) · [繁體中文](localization.zh-TW.md)

Use this guide to change the server language, edit a translation, preserve the v2 key namespace, and reload the result. AceEconomy v2 uses `lang/<locale>.yml`; the built-in examples are `en_US`, `zh_TW`, and `zh_CN`.

## Contents

- [File locations and locales](#file-locations-and-locales)
- [How a v2 message is written](#how-a-v2-message-is-written)
- [Built-in key map](#built-in-key-map)
- [Change the active language](#change-the-active-language)
- [Edit a translation safely](#edit-a-translation-safely)
- [Reload and recovery](#reload-and-recovery)
- [Related guides](#related-guides)

## File locations and locales

After the first start, edit the files in the plugin data folder:

```text
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
```

Select the locale in `plugins/AceEconomy/config.yml`:

```yaml
settings:
  locale: zh_TW
```

The v2 files use `lang/<locale>.yml`. The older `messages_<locale>.yml` naming belongs to the previous layout; these files are deprecated (retained for reference only) and are NOT read by the v2 message pipeline. Do not edit them for v2 messages; edit `lang/<locale>.yml` instead.

On the first start, the adapter provisions the three canonical `lang/<locale>.yml` resources via `JavaPlugin.saveResource("lang/" + fileName, false)`. A provisioning failure for any canonical resource (thrown `IOException`/permission/`RuntimeException`) is fail-fast: the adapter emits a sanitized `WARNING` (`Failed to ensure lang resource {0}: {1}` with `[redacted sensitive value]` for secrets, never echoing raw messages) and aborts the initial `load()` with a non-sensitive `IllegalStateException`; it does not continue to `ConfigManager`/`LangManager` loading and does not fall back to a default language. An already-present file (`saveResource(..., false)` no-throw) is not a failure.

## How a v2 message is written

Language files use three separate syntaxes:

- **Key namespace:** dotted YAML paths such as `general.invalid-amount` and `economy.payment-sent`. Keep namespaces and key names unchanged; translate values, not keys.
- **Typed placeholders:** write variables as `{placeholder}`, such as `{amount}`, `{player}`, `{balance}`, `{currency_name}`, `{issuer}`, and `{status}`. Keep braces and placeholder names intact. Dynamic values MUST use `{name}`; do not use `<currency_name>`, `<amount>`, or `<issuer>` angle forms (legacy `<...>` dynamic forms are rejected by resource contract tests).
- **MiniMessage:** use tags such as `<red>`, `<yellow>`, `<aqua>`, `<green>`, and `</red>` for presentation. Tags are rendered after variable substitution; do not replace them with legacy colour-code syntax.
- **Command literals:** when a help or usage line shows an example argument, write the angle brackets as escaped literals `\<player>` and `\<amount>` so MiniMessage renders them as literal `<player>` brackets. Example (single-quoted YAML preserves the backslash):

```yaml
admin:
  help-pay: '<white>/pay \<player> \<amount></white> <gray>- Transfer money</gray>'
  help-withdraw: '<white>/withdraw \<amount></white> <gray>- Withdraw balance as check</gray>'
```

Dynamic values such as `economy.balance-check-currency` and `economy.withdraw-redeem` use `{currency_name}` and `{issuer}`:

```yaml
economy:
  balance-check: "Your balance: <yellow>{balance}</yellow>"
  balance-check-currency: "Your {currency_name} balance: <yellow>{balance}</yellow>"
  payment-sent: "<green>Sent <yellow>{amount}</yellow> to <aqua>{player}</aqua>!</green>"
  withdraw-redeem: "<green>Redeemed banknote: <yellow>{amount}</yellow> <gray>(Issuer: {issuer})</gray></green>"
```

### Built-in key map

| Namespace | Use | Example keys |
| --- | --- | --- |
| `message` | Prefix shared by messages | `message.prefix` |
| `general` | General errors and status | `general.no-permission`, `general.status` |
| `economy` | Balances and payments | `economy.balance-check`, `economy.payment-received` |
| `admin` | Administrator feedback | `admin.give` |
| `command` | Command usage and errors | `command.usage-pay`, `command.invalid-uuid` |
| `error` | System-level error diagnostics | `error.missing-key`, `error.injection-detected` |
| `gui` | Bank GUI labels and prompts | `gui.bank-title`, `gui.input-request` |
| `banknote` | Banknote item text | `banknote.name`, `banknote.redeem-success` |

## Change the active language

1. Open `plugins/AceEconomy/config.yml`.
2. Set the full path `settings.locale` (not a bare `locale` key) to the matching canonical filename, such as `en_US`, `zh_TW`, or `zh_CN`. Only these three values are supported; other values are rejected and the previous valid language is kept.
3. Save the file.
4. Run `/aceeco reload` from the server console, or restart the server.

The reload reads the config and language snapshot again under a single adapter lock. A valid change becomes visible in the next message that uses the changed key. The active locale is resolved from `config.yml` via `ConfigLangAdapter` and `LangManager`; the constructor default (`zh_TW`) is only a fallback when `settings.locale` is absent before the first load — an invalid value (not `en_US`/`zh_TW`/`zh_CN`) fails the initial `load()` and never falls back, while a `reload()` with an invalid locale keeps the previous valid snapshot.

## Edit a translation safely

Start from the v2 file that matches the locale you want to edit. Change only YAML values:

```yaml
general:
  invalid-amount: "<red>Invalid amount: <white>{amount}</white></red>"
  player-not-found: "<red>Player not found: <white>{player}</white></red>"
```

When translating:

- keep indentation and YAML quoting valid;
- keep every `{placeholder}` required by the original message;
- keep opening and closing MiniMessage tags paired;
- keep key names such as `invalid-amount` in English, even when the value is translated.

Keep the built-in resource shape when maintaining a locale. The built-in resource set remains the reference for required namespaces and placeholder names.

## Reload and recovery

`/aceeco reload` from the server console reloads the v2 config and selected language resource atomically via candidate managers. The adapter builds a candidate `ConfigManager` and validates `config.yml` under strict type rules for every declared v2 field before any swap: integer fields (`storage.mysql.port` 1–65535, `storage.mysql.pool-size` 1–1000, `leaderboard.cache-time-seconds` 1–86400, `leaderboard.page-size` 1–100, `storage.mysql.max-lifetime` ≥1) must be finite integral numbers — fractional values such as `3306.5`, non-finite `NaN`/`Infinity`, and numeric strings such as `"3306"` are rejected without truncation; boolean fields (`economy.allow-negative-balance`, `discord.enabled`, `leaderboard.enabled`) must be YAML Boolean (`"true"`/`"false"` strings are rejected); string fields (`settings.locale` as `en_US`/`zh_TW`/`zh_CN` enum, `settings.main-command-alias`, `storage.sqlite.path`, `storage.mysql.host`/`database`/`username`/`password`, `discord.webhook-url`) must be String type (empty `password`/`webhook-url` remains legal, traversal of `storage.sqlite.path` outside the data folder is rejected, and storage/MySQL cross-field rules are enforced).Diagnostics never echo raw config, user, or exception values — validation failures use fixed `invalid <path>: must be …` or `must be one of …` messages, and load/IO failures use only the exception class; an unsupported `settings.locale` emits a fixed `WARNING` without echoing the raw code. The adapter then builds a candidate `LangManager` after a preflight that the selected canonical file exists, is a regular file, and is non-empty (missing, empty, non-regular/directory, or unreadable `lang/<locale>.yml` is treated as a failure and never falls back silently to the default locale). Only when both candidates succeed are `ConfigManager`/`LangManager`/`MessageService` swapped under a single lock; any failure (corrupt YAML, type/range violation such as `storage.mysql.port: not-a-number` or `3306.5`, unsupported `settings.locale` such as `ja_JP`, or missing/empty/corrupt/non-regular selected `lang/<locale>.yml`) keeps the previous complete snapshot for `getConfig("settings.locale")`, active language, and all render output — no half-applied state, and file preservation/restore failures are reported as part of the diagnostic.

A failure returns `ReloadResult{config=failed/lang=failed, configError/langError}` with a non-sensitive diagnostic (fixed `invalid <path>: …` or exception class only; passwords, webhook URLs, and arbitrary user values are never echoed) and emits a `WARNING` via the plugin logger; `diagnostics()` always contains the failing side's reason. Successful reloads return `config=ok, lang=ok`. Bad edits never overwrite the in-memory language currently in use.

Missing keys produce a non-blank fallback `Missing translation: <key>` (without leaking user-provided values) and a diagnostic `WARNING`. User-provided values containing MiniMessage tags (e.g. `<red>`, `<bold>`, `<click:...>`, `<hover:...>`, `<insertion>`, `<font>`) are escaped before parsing, so they appear as literal text with no colour, decoration, click/hover, insertion, or font injection in both component and plain-text projections.

Fix the reported YAML problem (check valid `{placeholder}` names, paired MiniMessage tags, escaped `\<literal>` for command examples, and correct `settings.locale`), save the file, and run `/aceeco reload` again. If it still fails, restore the last valid copy and check key indentation, quotes, and MiniMessage tags.

## Related guides

- [Configuration guide](config.md) — full `config.yml` reference.
- [Integrations](integrations.md) — Vault, PlaceholderAPI, Discord, and AceLib setup.
- [Integration API](integration-api.md) — placeholder and currency details for plugin developers.
