# AceEconomy integration API

English · [简体中文](integration-api.zh-CN.md) · [繁體中文](integration-api.zh-TW.md)

This reference is for plugin developers. It describes the public integration surfaces for reading or modifying AceEconomy balances without depending on internal classes: the standard Vault `Economy` service and the PlaceholderAPI `aceeco` namespace. Server installation is covered in [Integrations](integrations.md); language-file syntax is in [Localization](localization.md).

## Contents

- [Availability and compatibility](#availability-and-compatibility)
- [Vault provider](#vault-provider)
- [PlaceholderAPI namespace](#placeholderapi-namespace)
- [Currency parameters](#currency-parameters)
- [Fail-safe integration behaviour](#fail-safe-integration-behaviour)
- [Public integration checklist](#public-integration-checklist)
- [Related guides](#related-guides)

## Availability and compatibility

AceEconomy requires AceLib `v1.0.0` at runtime. Vault and PlaceholderAPI are optional integrations; your plugin should handle either public service being unavailable. Do not make an AceEconomy implementation class a hard dependency when the standard integration is enough.

## Vault provider

AceEconomy registers a Vault `Economy` provider named `AceEconomy`. Discover it through Vault's normal service lookup:

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

The provider maps Vault calls to the configured default currency. Vault has no currency ID, so a Vault consumer cannot select `token` or another named currency per call. Use the PlaceholderAPI forms below when a display or integration needs a named currency.

### Vault result rules

| Operation | Public result |
| --- | --- |
| Successful deposit or withdrawal | `EconomyResponse` reports success and the new balance. |
| Failed deposit or withdrawal | `EconomyResponse` reports failure, with amount `0` and the current or zero balance. The operation is not retried. |
| Balance or `has` for a missing account | `0.0` or `false`; no exception is required for this safe result. |
| Name-only account methods | `false` or failure. Use `OfflinePlayer` methods because accounts are UUID-based. |
| Vault bank methods | Not supported. |

Always inspect `transactionSuccess()` before treating a deposit or withdrawal as complete. Do not use the Vault provider for a non-default currency.

## PlaceholderAPI namespace

The PAPI namespace is `aceeco`. Placeholder parameters are case-insensitive in the resolver, but use the documented lowercase spelling in configuration and plugin text.

### Complete placeholder cheat sheet

| Copy this placeholder | Resolves to | Example result |
| --- | --- | --- |
| `%aceeco_balance%` | Raw balance in the default currency. | `100.00` |
| `%aceeco_balance_formatted%` | Default-currency balance with its symbol. | `$100.00` |
| `%aceeco_balance_<currency>%` | Raw balance in the named currency. | `%aceeco_balance_token%` → `7` |
| `%aceeco_balance_<currency>_formatted%` | Named-currency balance with its symbol. | `%aceeco_balance_token_formatted%` → `ⓒ7` |

Replace `<currency>` with the internal currency ID, not the display name. For the default `config.yml`, these are ready to copy:

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_token%
%aceeco_balance_token_formatted%
```

`<currency>` must match `[a-z0-9_]+`. Unknown IDs, malformed IDs, unknown placeholder names, missing players, and unavailable accounts resolve to `null`; PlaceholderAPI then keeps the original literal placeholder instead of displaying a false balance.

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
| `currencies.<id>` | Internal ID used in named placeholders. Use lowercase letters, digits, and `_`. |
| `name` | Display name; it is not the placeholder ID. |
| `symbol` | Prefix used by formatted balances. |
| `scale` | Fractional precision defined for the currency. |
| `default` | Selects the one currency exposed through Vault and default placeholder forms. |

Only one currency should be marked `default: true`. If you add a named currency, use its internal ID in both raw and formatted placeholder forms.

## Fail-safe integration behaviour

An integration consumer should treat a missing service or an unsuccessful result as a normal branch:

- Check that Vault returns an `Economy` service before calling it.
- Check every `EconomyResponse` before updating your own state or sending a success message.
- Treat an unchanged literal PAPI placeholder as unavailable data, not as a numeric value.
- Do not retry a failed Vault transaction automatically unless your own product contract explicitly defines a safe retry strategy.

## Public integration checklist

For a plugin integration, depend on the public contract rather than implementation details:

1. Declare the relevant external plugin as optional when your plugin can run without it.
2. Look up Vault `Economy` at runtime and handle `null`.
3. Use `OfflinePlayer`/UUID-aware calls and inspect `EconomyResponse`.
4. Put the four PAPI forms in user-facing configuration or messages exactly as documented.
5. Leave secrets and webhook URLs in local configuration only.

## Related guides

- [Integrations](integrations.md) — server installation, configuration, success checks, and troubleshooting.
- [Localization](localization.md) — v2 language keys and `{placeholder}` message variables.
