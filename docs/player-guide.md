# Player guide

English · [简体中文](player-guide.zh-CN.md) · [繁體中文](player-guide.zh-TW.md)

This guide covers player commands and operations for the AceEconomy v2 economy system. Commands follow an explicit `/<command> <action>` syntax (for example, `/money balance`). In command templates, `<...>` denotes required parameters, and `[...]` denotes optional parameters; enter actual values without the brackets. Omitting `[currency]` automatically selects the server's default currency.

## Contents

- [Before you start](#before-you-start)
- [I want to check a balance](#i-want-to-check-a-balance)
- [I want to pay another player](#i-want-to-pay-another-player)
- [I want to carry money as an item](#i-want-to-carry-money-as-an-item)
- [I want to see the richest players](#i-want-to-see-the-richest-players)
- [I want to use the bank dashboard](#i-want-to-use-the-bank-dashboard)
- [I want to use more than one currency](#i-want-to-use-more-than-one-currency)
- [I want to understand a balance change from an admin](#i-want-to-understand-a-balance-change-from-an-admin)
- [Common input problems](#common-input-problems)
- [More help](#more-help)

## Before you start

1. **Command Syntax:** v2 commands require explicit action subcommands (e.g., `/money balance`, `/pay send`); legacy root abbreviations are deprecated.
2. **Currency IDs:** Currency IDs are alphanumeric identifiers (such as `dollar` or `token`) defined in the server configuration and may differ from in-game display names or symbols. Consult your server administrator if unsure.

## I want to check a balance

### My own balance

Check your default currency balance:

```text
/money balance
```

To query a specific currency, append the currency ID after your player name:

```text
/money balance <player> <currency>
```

Requires `aceeconomy.command.money`.

### Another player's balance

Query another player's balance, optionally specifying a currency ID:

```text
/money balance <player> [currency]
```

Example:

```text
/money balance Alex token
```

Requires `aceeconomy.command.money`.

## I want to pay another player

Transfer funds to another online player:

```text
/pay send <player> <amount> [currency]
```

Example (transfer 250 default currency to Alex):

```text
/pay send Alex 250
```

Example (transfer 10 tokens to Alex):

```text
/pay send Alex 10 token
```

`/pay` is restricted to players and requires `aceeconomy.command.pay`. Amounts must be greater than 0 and conform to the configured decimal places for that currency. Both sender and recipient receive real-time notifications upon completion.

## I want to carry money as an item

### Withdraw a banknote

Withdraw funds into a physical banknote item. Ensure you have at least one empty inventory slot before executing:

```text
/withdraw cash <amount> [currency]
```

Example:

```text
/withdraw cash 100
```

The specified amount is debited from your account balance and converted into a physical banknote. Player-only; requires `aceeconomy.command.withdraw`.

### Redeem a banknote

Redeem a banknote through either of the following methods (both enforce strict anti-replay validation and atomic crediting):

1. **Via Bank GUI:** Run `/bank open`, hold the banknote, and click the deposit button.
2. **Right-Click in Hand:** Hold the banknote and right-click.

If an exception occurs during redemption:
- **Deposit rejected:** If the item is invalid, already redeemed, or uses an unknown currency, the note remains in your inventory. Do not attempt duplicate submissions; contact an administrator.
- **Clear delay / retention:** If funds are credited but the item cannot be removed, the transaction ID and credited amount remain logged in the audit ledger. Retain the item and notify an administrator.

## I want to see the richest players

Display the server balance leaderboard:

```text
/baltop top [currency]
```

Omit `[currency]` to query the default currency. Specific currency example:

```text
/baltop top token
```

Requires `aceeconomy.command.baltop`.

## I want to use the bank dashboard

Open the player bank interface:

```text
/bank open
```

Player-only; requires `aceeconomy.command.bank`. If an administrator reloads the bank configuration while the GUI is open, the interface dynamically rebuilds and updates in place.

- **Java Edition Clients:** Renders a chest GUI displaying account details, asset breakdowns, transaction logs, and deposit/withdrawal actions.
- **Bedrock Edition Clients (Geyser + Floodgate):** Renders a native modal form showing current balances with deposit, withdrawal, and close options. Withdrawals require explicit amount/currency inputs and secondary confirmation.
Both platforms execute identical backend transaction pipelines and validation rules.

## I want to use more than one currency

Every command that shows `[currency]` accepts a configured currency ID. The default configuration includes `dollar` and `token`; your server may define more.

Use the default currency when the server's normal economy is enough. Add a currency ID when the activity uses a separate balance:

```text
/money balance <player> token
/pay send Alex 10 token
/withdraw cash 5 token
/baltop top token
```

Currency IDs are normalized for lookup, so differences in capitalization or surrounding spaces do not create a second currency. The amount still has to match that currency's configured scale. A currency with scale `0` accepts whole numbers; a currency with scale `2` accepts up to two decimal places.

## I want to understand a balance change from an admin

Server administrators can give money, take money, or set a balance. When one of these actions affects you, AceEconomy can show a player-facing notification with the amount and currency. You can confirm the resulting value with:

```text
/money balance
```

If you expected a reward or correction but do not recognize the new balance, ask the server administrator for the transaction context. Admin command syntax and permissions are listed in [Commands and permissions](commands.md).

## Common input problems

### The command says the amount is invalid

Check that the amount is a real number, greater than zero, and does not contain more decimal places than the selected currency allows. Extremely large amounts are rejected as well. Try a small value first, such as `10` or `10.50` when the currency uses scale `2`.

### The currency is not found

Use the currency ID configured by the server, not its display name or symbol. Currency suggestions appear while you type when command completion is available. If the ID is still rejected, ask an administrator which currencies are enabled.

### The player cannot be found

Check the spelling and use the player's name exactly as the server recognizes it. For `/pay send`, also make sure you are running the command as a player and are not trying to pay yourself.

### I do not have permission

Ask the server administrator to check the permission for the command:

| Command | Permission |
| --- | --- |
| `/money` | `aceeconomy.command.money` |
| `/pay` | `aceeconomy.command.pay` |
| `/withdraw` | `aceeconomy.command.withdraw` |
| `/baltop` | `aceeconomy.command.baltop` |
| `/bank` | `aceeconomy.command.bank` |

### My banknote did not appear

Check your inventory first. A full inventory prevents a banknote withdrawal. If the command reports an economy error, check the amount and your available balance, then ask an administrator if the problem continues.

## More help

For every command's full syntax, sender rules, aliases, and admin permissions, see [Commands and permissions](commands.md). Server setup belongs in the [admin installation runbook](admin-install-runbook.md), while configuration details are in the [configuration guide](config.md). If a player-facing problem remains, see [Troubleshooting](troubleshooting.md) or ask your server administrator.
