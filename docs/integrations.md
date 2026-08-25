# AceEconomy integrations

English · [简体中文](integrations.zh-CN.md) · [繁體中文](integrations.zh-TW.md)

Use this guide when a server administrator needs AceEconomy to work with another plugin or with Discord. It covers the required AceLib dependency and the optional Vault, PlaceholderAPI, and Discord integrations. Plugin authors should use [Integration API](integration-api.md); language-file work is covered in [Localization](localization.md).

## Contents

- [Before you start](#before-you-start)
- [AceLib](#acelib)
- [Vault](#vault)
- [PlaceholderAPI](#placeholderapi)
- [Discord notifications](#discord-notifications)
- [Related guides](#related-guides)

## Before you start

AceEconomy requires AceLib `v1.0.0` at runtime. Install it before AceEconomy; `plugin.yml` declares AceLib as a hard dependency, so AceEconomy will not start without a ready AceLib service. It also requires Java 25 and a Paper/Folia server matching the release baseline.

Vault and PlaceholderAPI are optional soft dependencies. AceEconomy skips the corresponding integration when either plugin is absent or disabled. Discord does not require a separate server plugin; it uses the webhook configured in `config.yml`.

## AceLib

Install the matching AceLib release, then start the server with AceEconomy. AceLib must be ready before AceEconomy can register commands, messages, and integrations.

When it works, AceEconomy enables normally and its commands are available. If AceLib is missing or not ready, the plugin is disabled instead of starting with partial services.

If it does not start:

1. Check that the AceLib JAR is installed and enabled.
2. Check that the AceLib version is `v1.0.0` for this release.
3. Read the first AceEconomy startup error. Fix AceLib first, then restart AceEconomy.

## Vault

Vault lets plugins using the standard Vault `Economy` service use AceEconomy as their economy provider.

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

Vault has one economy balance, so it always uses the configured default currency. Named currencies remain available through AceEconomy's own multi-currency and PlaceholderAPI surfaces; Vault does not select a currency per call.

### What success looks like

Plugins querying the Vault `Economy` service can load a provider named `AceEconomy`. A deposit or withdrawal reports success with the new balance. Balance checks for an existing account return that account's balance.

### Troubleshooting

- **No provider is visible:** enable Vault before checking AceEconomy. If Vault is not installed or enabled, AceEconomy leaves the Vault provider disabled.
- **A transaction reports failure:** check the player account and amount. Failed operations are not reported as successful, and the Vault adapter does not retry them.
- **A missing account shows zero or false:** this is the safe result for a balance or `has` query. Create the account through the normal AceEconomy flow first.
- **Bank features are unavailable:** the AceEconomy Vault provider does not advertise Vault bank support.

## PlaceholderAPI

PlaceholderAPI exposes AceEconomy values under the `aceeco` namespace. Install and enable PlaceholderAPI before starting AceEconomy.

### Install and check

1. Install and enable PlaceholderAPI.
2. Restart or reload the server so AceEconomy can register its expansion.
3. Put one of the placeholders from [Integration API](integration-api.md) into a PAPI-compatible plugin.

```text
Balance: %aceeco_balance_formatted%
Tokens: %aceeco_balance_token_formatted%
```

If the placeholder is valid and the account is available, the consuming plugin receives the balance value. Unknown or unusable values remain as the original placeholder text instead of being replaced with a misleading number.

Named currencies use the internal currency ID from `currencies.<id>`, in lowercase with only `a-z`, `0-9`, and `_`. Use the exact `_formatted` suffix for formatted output.

### Troubleshooting

- **The placeholder is shown literally:** confirm PlaceholderAPI is installed and enabled, then check spelling and currency ID.
- **A named currency does not resolve:** use the internal currency ID, not the display name.
- **The default value works but the formatted value does not:** use the exact `_formatted` suffix; the four supported forms are listed in the API guide.

## Discord notifications

Discord sends a best-effort notification after a transaction has been committed. It is useful for an audit-style channel, but it is not the transaction result: delivery failure does not roll back or veto the economy operation.

### Configure it

Set the two `discord` keys in `plugins/AceEconomy/config.yml`:

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/<WEBHOOK_ID>/<WEBHOOK_TOKEN>"
```

Replace placeholders locally. Do not paste a real webhook URL into shared documentation, commits, issue reports, or support messages.

After saving, run `/aceeco reload` from the server console or restart the server. Completed transactions should then produce a Discord embed containing transaction type, sender, receiver, and amount.

### Delivery behaviour

- Delivery is asynchronous and does not make the command wait on the network.
- A rejected request, timeout, mapping error, or transport failure is ignored by the notifier. The committed economy result remains unchanged.
- Payload fields are length-bounded and configured secrets are redacted. The webhook URL itself is not placed in the payload body.

### Troubleshooting

- **Nothing arrives:** check `discord.enabled`, the webhook URL, and server network access to Discord. Then make one new transaction; notifications are sent for committed events.
- **The transaction succeeds but Discord does not:** this is an expected best-effort delivery failure. Fix the webhook or network path without treating Discord as the source of truth for the balance.
- **A secret appears in a payload field:** remove it from the transaction text and rotate the secret. Webhook credentials belong only in local configuration.

## Related guides

- [Integration API](integration-api.md) — public Vault and PlaceholderAPI contract for plugin developers.
- [Localization](localization.md) — v2 language files, keys, placeholders, and reload workflow.
- [Configuration guide](config.md) — storage, currency, and server configuration reference.
