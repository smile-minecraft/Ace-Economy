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

The v2 files use `lang/<locale>.yml`. The older `messages_<locale>.yml` naming belongs to the previous layout; do not use it for v2 messages.

## How a v2 message is written

Language files use three separate syntaxes:

- **Key namespace:** dotted YAML paths such as `general.invalid-amount` and `economy.payment-sent`. Keep namespaces and key names unchanged; translate values, not keys.
- **Typed placeholders:** write variables as `{placeholder}`, such as `{amount}`, `{player}`, `{balance}`, and `{status}`. Keep braces and placeholder names intact.
- **MiniMessage:** use tags such as `<red>`, `<yellow>`, `<aqua>`, `<green>`, and `</red>` for presentation. Tags are rendered after variable substitution; do not replace them with legacy colour-code syntax.

```yaml
economy:
  balance-check: "Your balance: <yellow>{balance}</yellow>"
  payment-sent: "<green>Sent <yellow>{amount}</yellow> to <aqua>{player}</aqua>!</green>"
```

### Built-in key map

| Namespace | Use | Example keys |
| --- | --- | --- |
| `message` | Prefix shared by messages | `message.prefix` |
| `general` | General errors and status | `general.no-permission`, `general.status` |
| `economy` | Balances and payments | `economy.balance-check`, `economy.payment-received` |
| `admin` | Administrator feedback | `admin.give` |

## Change the active language

1. Open `plugins/AceEconomy/config.yml`.
2. Set `settings.locale` to the matching locale filename, such as `en_US`, `zh_TW`, or `zh_CN`.
3. Save the file.
4. Run `/aceeco reload` from the server console, or restart the server.

The reload reads the config and language snapshot again. A valid change becomes visible in the next message that uses the changed key.

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

`/aceeco reload` from the server console reloads both the v2 config and selected language resource. If edited YAML is invalid, reload reports a failure and keeps the last valid in-memory snapshot. A bad translation should not silently replace the language currently in use.

Fix the reported YAML problem, save the file, and run `/aceeco reload` again. If it still fails, restore the last valid copy and check key indentation, quotes, and MiniMessage tags.

## Related guides

- [Configuration guide](config.md) — full `config.yml` reference.
- [Integrations](integrations.md) — Vault, PlaceholderAPI, Discord, and AceLib setup.
- [Integration API](integration-api.md) — placeholder and currency details for plugin developers.
