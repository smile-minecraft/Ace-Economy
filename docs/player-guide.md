# Player guide

English · [简体中文](player-guide.zh-CN.md) · [繁體中文](player-guide.zh-TW.md)

AceEconomy is easiest to use when you start with the task in front of you. This guide keeps the commands short, explains when to add a currency, and points out the few input details that commonly cause a command to fail.

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

The v2 commands use an action word after the main command. For example, use `/money balance`, not an older shortened form. Text in angle brackets is a placeholder: replace `<player>`, `<amount>`, or `<currency>` with your value, and do not type the angle brackets.

If you leave out `[currency]`, AceEconomy uses the server's configured default currency. Currency IDs come from the server configuration; the display name players see can be different from the ID.

## I want to check a balance

### My own balance

Use:

```text
/money balance
```

You will see your balance in the default currency. To check a named currency, include your player name followed by its ID:

```text
/money balance <player> <currency>
```

This command requires `aceeconomy.command.money`.

### Another player's balance

Use the player's name and, when needed, the currency ID:

```text
/money balance <player> [currency]
```

For example:

```text
/money balance Alex token
```

The same `aceeconomy.command.money` permission covers this command.

## I want to pay another player

Use:

```text
/pay send <player> <amount> [currency]
```

For example:

```text
/pay send Alex 250
```

This pays `Alex` 250 units of the default currency. To pay in another currency, name it explicitly:

```text
/pay send Alex 10 token
```

`/pay` is player-only and requires `aceeconomy.command.pay`. The amount must be positive and valid for the selected currency. A successful transfer reports the amount and recipient; the receiving player gets a matching payment notification.

## I want to carry money as an item

### Withdraw a banknote

Use:

```text
/withdraw cash <amount> [currency]
```

For example:

```text
/withdraw cash 100
```

AceEconomy removes the amount from your balance and gives you a physical banknote. Keep an empty inventory slot available before you withdraw. This command is player-only and requires `aceeconomy.command.withdraw`.

### Redeem a banknote

Open the bank dashboard with `/bank open`, hold the banknote in your main hand, and click the deposit button (the top-middle slot). The amount is credited to your account and the banknote disappears from your hand. A banknote can be redeemed only once — the server remembers every redeemed note even after a restart. If the deposit is refused (invalid, already used, or unknown currency), the banknote stays in your hand; keep it and ask a server administrator for help rather than trying to duplicate the transaction.

Right-clicking a banknote to redeem it is not implemented yet; the bank dashboard deposit button is the only redemption path.

## I want to see the richest players

Use:

```text
/baltop top [currency]
```

Without a currency, the leaderboard uses the configured default currency. For a specific currency:

```text
/baltop top token
```

The command requires `aceeconomy.command.baltop`. The leaderboard displays player names, ranks, and balances for the selected currency.

## I want to use the bank dashboard

Open the dashboard with:

```text
/bank open
```

The bank is a player-only menu. It gives you a single place to view your account area and use the available banknote withdrawal actions. Close the menu when you are done. The command requires `aceeconomy.command.bank`.

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
