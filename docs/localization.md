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

Every render uses that global locale. The adapter renders through one plugin-scoped message scope whose resolver is fixed to the active locale, so players with different client locales still see the same language — per-player locales are deliberately not followed. A render that names an explicit locale takes its `message.prefix` from that locale (falling back to the default locale when the locale has no prefix); a missing key still returns `Missing translation: <key>` with a `WARNING`.

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

Running `/aceeco reload` from the console atomically reloads configuration and language resources. The process enforces a three-stage validation pipeline; any failure keeps the existing active snapshot completely untouched:

### 1. Configuration Validation (`config.yml`)
The adapter validates the candidate configuration using `ConfigManager.load()` under strict typing:
- **Integer fields:** Must be finite integers. Includes `storage.mysql.port` (1–65535), `storage.mysql.pool-size` (1–1000), `leaderboard.cache-time-seconds` (1–86400), `leaderboard.page-size` (1–100), and `storage.mysql.max-lifetime` (≥1). Decimals (e.g. `3306.5`), non-finite values (`NaN`/`Infinity`), or numeric strings (`"3306"`) are rejected immediately without truncation.
- **Boolean fields:** Must be native YAML Booleans (`economy.allow-negative-balance`, `discord.enabled`, `leaderboard.enabled`). String values like `"true"`/`"false"` are rejected.
- **String fields:** Includes `settings.locale` (strictly `en_US`, `zh_TW`, or `zh_CN`), paths, and credentials. Paths that escape the plugin data directory via `storage.sqlite.path` are rejected.
- **Redaction policy:** Diagnostics never output raw passwords, webhook URLs, or user strings. Errors only report fixed schema paths (such as `invalid <path>: must be …`) or exception class names.

### 2. Language Preflight and Load
The adapter checks the selected `lang/<locale>.yml`:
- The file must exist, be a regular file, and be non-empty. Missing, unreadable, directory, or zero-byte files fail the operation immediately without silent fallback.
- Once verified, `LangManager.load(locale)` parses the templates and MiniMessage tags.

### 3. Atomic Swap and Failure Isolation
Only when both configuration and language pass validation are `ConfigManager`, `LangManager`, `MessageService`, and active render scopes swapped under a single lock. Retired scopes close immediately to prevent torn rendering states.

If validation fails, a `ReloadResult` containing sanitized failure diagnostics is returned and logged as a `WARNING`. The existing in-memory configuration and active language remain completely untouched.

Missing translation keys fall back to `Missing translation: <key>` and log a `WARNING`. User-supplied variable values containing MiniMessage tags (e.g. `<red>`, `<click>`) are escaped before resolution, preventing color or click injection attacks.

## Related guides

- [Configuration guide](config.md) — full `config.yml` reference.
- [Integrations](integrations.md) — Vault, PlaceholderAPI, Discord, and AceLib setup.
- [Integration API](integration-api.md) — placeholder and currency details for plugin developers.
